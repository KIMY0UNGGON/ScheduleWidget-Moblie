package com.schedulewidget.mobile.notes.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import com.schedulewidget.mobile.notes.ink.InkGeometry
import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.notes.ink.Stroke
import com.schedulewidget.mobile.notes.ink.TextBox
import com.schedulewidget.mobile.notes.ink.Tool
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Drawing of page templates, strokes and text boxes as vectors. All functions draw in page points: the caller scales
 * the canvas (screen: points → pixels at the current zoom; PdfDocument export: 1:1).
 */
object PageDraw {
    /** Highlighter ink opacity. */
    const val HIGHLIGHT_ALPHA = 0.42f

    // Paints are mutable; editor drawing (main thread) and export/thumbnails (PdfThread) each get their own.
    private class Paints {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG)
    }
    private val paints = ThreadLocal.withInitial { Paints() }
    private val inkFill: Paint get() = paints.get()!!.fill
    private val inkStroke: Paint get() = paints.get()!!.stroke
    private val textPaint: Paint get() = paints.get()!!.text

    // Committed strokes are immutable, so their outlines are built once (main-thread editor drawing only).
    private val pathCache = WeakHashMap<Stroke, StrokeShape>()

    /** [core] is set for pencil strokes only: [path] is then the halo and [core] the dark core (both filled). */
    private class StrokeShape(val path: Path, val filled: Boolean, val strokeWidth: Float, val core: Path? = null)

    /** Template lines of a blank page. [pxPerPt] keeps hairlines visible when zoomed out. */
    fun drawTemplate(c: Canvas, page: PageInfo, pxPerPt: Float) {
        if (page.isPdf) return
        if (!PageTemplates.draw(c, page, pxPerPt)) PageTemplates.drawBasic(c, page, pxPerPt)
    }

    /**
     * Draws [ink]: highlighters first (under the pen), then every other kind (the pen layer), then text. [skip]
     * strokes/texts are left out (e.g. a lasso selection being dragged, drawn separately). [dimUnlinked] fades strokes
     * without a recording link.
     */
    fun drawInk(
        c: Canvas, ink: PageInk, cache: Boolean = true,
        skip: Set<Long>? = null, dimUnlinked: Boolean = false,
    ) {
        for (pass in 0..1) {
            for (s in ink.strokes) {
                if (!inPass(s, pass) || (skip != null && s.id in skip)) continue
                drawStroke(c, s, cache, if (dimUnlinked && s.rec == null) 0.25f else 1f)
            }
        }
        for (t in ink.texts) {
            if (skip != null && t.id in skip) continue
            drawText(c, t, if (dimUnlinked) 0.25f else 1f)
        }
    }

    /** Layer pass of [s]: 0 = highlighter (under), 1 = pen layer (every other kind). */
    fun inPass(s: Stroke, pass: Int): Boolean = (s.tool == Tool.HIGHLIGHTER) == (pass == 0)

    fun drawStroke(c: Canvas, s: Stroke, cache: Boolean = true, alpha: Float = 1f) {
        val shape = if (cache) synchronized(pathCache) { pathCache.getOrPut(s) { buildShape(s) } } else buildShape(s)
        if (shape.core != null) {
            drawPencil(c, shape.path, shape.core, s.color, alpha)
            return
        }
        val paint = if (shape.filled) inkFill else inkStroke
        paint.color = s.color
        applyTool(paint, s.tool, alpha)
        if (!shape.filled) paint.strokeWidth = shape.strokeWidth
        c.drawPath(shape.path, paint)
        resetBlend(paint)
    }

    /** Colour, opacity and blending of [tool] ink on [paint]. */
    fun applyTool(paint: Paint, tool: Int, alpha: Float = 1f) {
        val base = Color.alpha(paint.color) / 255f
        if (tool == Tool.HIGHLIGHTER) {
            paint.alpha = (255 * base * HIGHLIGHT_ALPHA * alpha).toInt()
        } else {
            paint.alpha = (255 * base * alpha).toInt()
        }
    }

    fun resetBlend(paint: Paint) {
        if (Build.VERSION.SDK_INT >= 29) paint.blendMode = null
    }

    private fun buildShape(s: Stroke): StrokeShape {
        val n = s.count
        when (s.tool) {
            Tool.HIGHLIGHTER, Tool.BALLPOINT -> return StrokeShape(smoothPath(s.pts, n), false, s.width)
            Tool.PENCIL -> {
                val hw = FloatArray(n) { s.halfWidth(it) }
                val core = FloatArray(n) { pencilCoreHalfWidth(s.width, s.pts, n, it) }
                return StrokeShape(outlinePath(s.pts, n, hw), true, 0f, outlinePath(s.pts, n, core))
            }
            Tool.BRUSH -> return StrokeShape(outlinePath(s.pts, n, FloatArray(n) { s.halfWidth(it) }), true, 0f)
        }
        // Fountain pen (and unknown kinds): unchanged since the first version.
        val hw = FloatArray(n) { s.halfWidth(it) }
        var minW = Float.MAX_VALUE; var maxW = 0f
        for (w in hw) { minW = min(minW, w); maxW = max(maxW, w) }
        // Nearly constant width (finger, mouse, steady pen): one smooth stroked path is cheaper and looks the same.
        if (n < 2 || maxW <= minW * 1.12f) return StrokeShape(smoothPath(s.pts, n), false, (minW + maxW))
        return StrokeShape(outlinePath(s.pts, n, hw), true, 0f)
    }

    private fun pencilCoreHalfWidth(width: Float, pts: FloatArray, n: Int, i: Int): Float =
        InkGeometry.pencilCoreWidth(width, InkGeometry.smoothedPressure(pts, n, i)) / 2f

    /** Polyline through x, y, p triples, smoothed with quadratic curves through the segment midpoints. */
    fun smoothPath(pts: FloatArray, n: Int): Path = Path().also { appendSmooth(it, pts, n) }

    private fun appendSmooth(path: Path, pts: FloatArray, n: Int) {
        if (n == 0) return
        path.moveTo(pts[0], pts[1])
        if (n == 1) { path.lineTo(pts[0] + 0.01f, pts[1]); return }
        if (n == 2) { path.lineTo(pts[3], pts[4]); return }
        for (i in 1 until n - 1) {
            val x = pts[i * 3]; val y = pts[i * 3 + 1]
            val nx = pts[(i + 1) * 3]; val ny = pts[(i + 1) * 3 + 1]
            path.quadTo(x, y, (x + nx) / 2f, (y + ny) / 2f)
        }
        path.lineTo(pts[(n - 1) * 3], pts[(n - 1) * 3 + 1])
    }

    private fun outlinePath(pts: FloatArray, n: Int, hw: FloatArray): Path =
        Path().apply { fillType = Path.FillType.WINDING; appendOutline(this, pts, n, 0, n, hw) }

    /**
     * Variable-width outline of points [from] until [to] of a stroke of [n] points with half widths [hw]: a disc at
     * every point plus a quad joining it to the next one, all wound the same way so the non-zero fill unions them
     * without seams.
     */
    private fun appendOutline(path: Path, pts: FloatArray, n: Int, from: Int, to: Int, hw: FloatArray) {
        for (i in from until to) {
            val ax = pts[i * 3]; val ay = pts[i * 3 + 1]
            path.addCircle(ax, ay, hw[i], Path.Direction.CW)
            if (i == n - 1) break
            val bx = pts[i * 3 + 3]; val by = pts[i * 3 + 4]
            val dx = bx - ax; val dy = by - ay
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-3f) continue
            val nx = -dy / len; val ny = dx / len
            val ra = hw[i]; val rb = hw[i + 1]
            // a-n, b-n, b+n, a+n runs clockwise on screen (y down), the same way as the discs.
            path.moveTo(ax - nx * ra, ay - ny * ra)
            path.lineTo(bx - nx * rb, by - ny * rb)
            path.lineTo(bx + nx * rb, by + ny * rb)
            path.lineTo(ax + nx * ra, ay + ny * ra)
            path.close()
        }
    }

    // ---- pencil ----
    // A pencil stroke is two grainy translucent fills: a faint full-width halo and a darker core whose width follows
    // the pressure, so light strokes are pale with a thin dark line and firm strokes dark all across. Each layer is a
    // single path (no alpha build-up at joints) filled with a tiled noise bitmap in the ink colour; that is plain
    // vector + image-pattern drawing, so PdfDocument export keeps it.

    /** Opacity of the pencil's full-width halo and of its pressure-wide core. */
    private const val PENCIL_HALO_ALPHA = 0.5f
    private const val PENCIL_CORE_ALPHA = 0.75f

    private fun drawPencil(c: Canvas, halo: Path, core: Path, color: Int, alpha: Float) {
        val p = inkFill
        resetBlend(p)
        p.color = color
        p.shader = PencilGrain.shader(color)
        val base = Color.alpha(color) * alpha
        p.alpha = (base * PENCIL_HALO_ALPHA).toInt()
        c.drawPath(halo, p)
        p.alpha = (base * PENCIL_CORE_ALPHA).toInt()
        c.drawPath(core, p)
        p.shader = null
    }

    /** Tiled graphite grain, one shader per ink colour (built once, shared by all threads and strokes). */
    private object PencilGrain {
        private const val SIZE = 64
        /** Page points per grain texel. */
        private const val TEXEL = 0.35f
        private const val MAX_COLORS = 24

        /** Grain opacity per texel (0..255): fine white noise over a coarser cloud, never fully transparent. */
        private val grain: IntArray by lazy {
            val rnd = java.util.Random(0x5EED_1234L)
            val coarseN = 16
            val coarse = FloatArray(coarseN * coarseN) { rnd.nextFloat() }
            val step = SIZE / coarseN
            IntArray(SIZE * SIZE) { k ->
                val x = k % SIZE; val y = k / SIZE
                // Bilinear sample of the coarse grid, wrapping so the tile repeats seamlessly.
                val gx = x / step; val gy = y / step
                val fx = (x % step) / step.toFloat(); val fy = (y % step) / step.toFloat()
                fun cg(i: Int, j: Int) = coarse[(j % coarseN) * coarseN + (i % coarseN)]
                val cv = (cg(gx, gy) * (1 - fx) + cg(gx + 1, gy) * fx) * (1 - fy) +
                    (cg(gx, gy + 1) * (1 - fx) + cg(gx + 1, gy + 1) * fx) * fy
                val v = 0.6f * rnd.nextFloat() + 0.4f * cv
                (255 * (0.35f + 0.65f * v)).toInt().coerceIn(0, 255)
            }
        }

        private val shaders = object : LinkedHashMap<Int, BitmapShader>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, BitmapShader>?) = size > MAX_COLORS
        }

        private val matrix = Matrix().apply { setScale(TEXEL, TEXEL); postRotate(17f) }

        fun shader(color: Int): BitmapShader {
            val rgb = color and 0xFFFFFF
            synchronized(shaders) {
                shaders[rgb]?.let { return it }
                val g = grain
                val px = IntArray(g.size) { (g[it] shl 24) or rgb }
                val bmp = Bitmap.createBitmap(px, SIZE, SIZE, Bitmap.Config.ARGB_8888)
                val sh = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                sh.setLocalMatrix(matrix)
                shaders[rgb] = sh
                return sh
            }
        }
    }

    // ---- live (in-progress) stroke ----
    // The live stroke is built with the same shapes as the committed one, so nothing changes when it is committed.
    // Opaque outline kinds (pen, brush) freeze finished chunks of their outline as separate paths, so a long stroke
    // only rebuilds its tail each frame; translucent kinds (pencil, see-through colours) are one path per frame so
    // their ink doesn't stack where chunks would overlap. Main thread only.

    private const val LIVE_CHUNK = 32

    private object Live {
        var tool = -1; var color = 0; var width = 0f; var x0 = Float.NaN; var y0 = Float.NaN; var n = 0
        val chunks = ArrayList<Path>()
        val spare = ArrayList<Path>()
        /** Points [0, frozen) have their disc and forward quad in [chunks]. */
        var frozen = 0
        val tail = Path()
        val core = Path()
        var hw = FloatArray(512)
        var hw2 = FloatArray(512)
        var arc = FloatArray(512)

        fun reset() {
            spare.addAll(chunks); chunks.clear(); frozen = 0
        }

        fun ensure(n: Int) {
            if (hw.size < n) { val s = max(n, hw.size * 2); hw = FloatArray(s); hw2 = FloatArray(s); arc = FloatArray(s) }
        }
    }

    /** Draws the stroke being written: [n] x, y, p triples of [pts] as a [tool] stroke. */
    fun drawLive(c: Canvas, pts: FloatArray, n: Int, tool: Int, color: Int, width: Float) {
        if (n <= 0) return
        if (tool == Tool.HIGHLIGHTER) { drawLiveHighlighter(c, pts, n, color, width); return }
        val L = Live
        // A new stroke (points are only appended within one): drop the frozen chunks.
        if (tool != L.tool || color != L.color || width != L.width || n < L.n || pts[0] != L.x0 || pts[1] != L.y0) {
            L.reset()
            L.tool = tool; L.color = color; L.width = width; L.x0 = pts[0]; L.y0 = pts[1]
        }
        L.n = n
        L.ensure(n)
        val tail = L.tail
        tail.rewind()
        when (tool) {
            Tool.BALLPOINT -> {
                appendSmooth(tail, pts, n)
                inkStroke.color = color
                applyTool(inkStroke, tool)
                inkStroke.strokeWidth = width
                c.drawPath(tail, inkStroke)
            }
            Tool.PENCIL -> {
                for (i in 0 until n) {
                    L.hw[i] = InkGeometry.halfWidth(tool, width, pts, n, i, null)
                    L.hw2[i] = pencilCoreHalfWidth(width, pts, n, i)
                }
                tail.fillType = Path.FillType.WINDING
                appendOutline(tail, pts, n, 0, n, L.hw)
                val core = L.core
                core.rewind()
                core.fillType = Path.FillType.WINDING
                appendOutline(core, pts, n, 0, n, L.hw2)
                drawPencil(c, tail, core, color, 1f)
            }
            else -> {
                val arc = if (tool == Tool.BRUSH) InkGeometry.arcLengths(pts, n, L.arc) else null
                for (i in L.frozen until n) L.hw[i] = InkGeometry.halfWidth(tool, width, pts, n, i, arc)
                if (Color.alpha(color) == 255) {
                    // Point i is final once neither its smoothed pressure nor its neighbour's can change (the last
                    // sample's pressure still can) and, for the brush, it is beyond the end taper.
                    var stable = n - 3
                    if (arc != null) {
                        val t = InkGeometry.brushTaperLength(width)
                        while (stable > L.frozen && arc[n - 1] - arc[stable] < t) stable--
                    }
                    while (stable - L.frozen >= LIVE_CHUNK) {
                        val p = if (L.spare.isEmpty()) Path() else L.spare.removeAt(L.spare.size - 1)
                        p.rewind()
                        p.fillType = Path.FillType.WINDING
                        appendOutline(p, pts, n, L.frozen, L.frozen + LIVE_CHUNK, L.hw)
                        L.chunks += p
                        L.frozen += LIVE_CHUNK
                    }
                }
                tail.fillType = Path.FillType.WINDING
                appendOutline(tail, pts, n, L.frozen, n, L.hw)
                inkFill.color = color
                applyTool(inkFill, tool)
                for (p in L.chunks) c.drawPath(p, inkFill)
                c.drawPath(tail, inkFill)
            }
        }
    }

    /** Live highlighter stroke (one path so the translucent ink doesn't stack at the joints). */
    fun drawLiveHighlighter(c: Canvas, pts: FloatArray, n: Int, color: Int, width: Float) {
        inkStroke.color = color
        applyTool(inkStroke, Tool.HIGHLIGHTER)
        inkStroke.strokeWidth = width
        c.drawPath(smoothPath(pts, n), inkStroke)
        resetBlend(inkStroke)
    }

    // ---- text boxes ----

    private const val LINE_HEIGHT = 1.3f

    fun drawText(c: Canvas, t: TextBox, alpha: Float = 1f) {
        textPaint.color = t.color
        textPaint.alpha = (Color.alpha(t.color) * alpha).toInt()
        textPaint.textSize = t.size
        val fm = textPaint.fontMetrics
        var baseline = t.y - fm.ascent
        for (line in t.text.split('\n')) {
            c.drawText(line, t.x, baseline, textPaint)
            baseline += t.size * LINE_HEIGHT
        }
    }

    /** Bounds of [t] in page points. */
    fun textBounds(t: TextBox): RectF {
        val p = textPaint.apply { textSize = t.size }
        val lines = t.text.split('\n')
        val w = lines.maxOf { p.measureText(it) }.coerceAtLeast(t.size)
        val h = t.size * LINE_HEIGHT * lines.size
        return RectF(t.x, t.y, t.x + w, t.y + h)
    }
}
