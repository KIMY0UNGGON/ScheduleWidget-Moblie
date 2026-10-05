package com.schedulewidget.mobile.notes.editor

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.schedulewidget.mobile.notes.render.PageDraw

/**
 * The editor's two layers. The page layer (paper, PDF bitmaps, templates, committed ink) redraws on zoom/scroll and
 * ink changes; the overlay (the stroke being written, lasso, eraser circle) redraws on every pen sample without
 * touching the page layer.
 */
internal object EditorDraw {
    private val paper = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x22000000 }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF1E88E5.toInt(); strokeWidth = 3f
    }
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF1E88E5.toInt(); strokeWidth = 3f
    }
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x99000000.toInt() }
    private val rect = RectF()

    /** Uids drawn in the last frame (+ the next page): page renders still wanted. */
    @Volatile var wantedUids: Set<String> = emptySet()

    fun drawPages(c: Canvas, st: EditorState, renderer: PageRenderer, background: Int) {
        c.drawColor(background)
        val l = st.layout()
        val s = st.scale
        val pages = st.note.pages
        val range = st.visibleRange()
        val sel = st.selection
        // Publish the wanted pages before requesting renders (the PDF thread checks this set).
        wantedUids = buildSet { for (i in range) add(pages[i].uid); if (!range.isEmpty() && range.last + 1 < pages.size) add(pages[range.last + 1].uid) }
        edge.strokeWidth = st.density
        for (i in range) {
            val p = pages[i]
            val left = (l.margin - st.ox) * s
            val top = (l.tops[i] - st.oy) * s
            val w = l.fitW * s
            val h = l.heights[i] * s
            rect.set(left, top, left + w, top + h)
            c.drawRect(rect, paper)
            c.drawRect(rect, edge)
            val pxPerPt = st.pxPerPt(i)
            if (p.isPdf) {
                val bw = renderer.baseWidthFor(p, l.fitW, w)
                val uid = p.uid
                val bmp = renderer.page(p, bw) { uid in wantedUids }
                if (bmp != null) c.drawBitmap(bmp, null, rect, bitmapPaint)
                val tile = renderer.tile(p.uid)
                if (tile != null && (bmp == null || tile.pxPerPt > bmp.width / p.w * 1.05f)) {
                    val tr = RectF(
                        left + tile.rect.left * pxPerPt, top + tile.rect.top * pxPerPt,
                        left + tile.rect.right * pxPerPt, top + tile.rect.bottom * pxPerPt,
                    )
                    c.drawBitmap(tile.bitmap, null, tr, bitmapPaint)
                }
            }
            c.save()
            c.clipRect(rect)
            c.translate(left, top)
            c.scale(pxPerPt, pxPerPt)
            PageDraw.drawTemplate(c, p, pxPerPt)
            val ink = st.inkOf(p.uid)
            val dragging = (sel != null && sel.uid == p.uid && (st.dragDx != 0f || st.dragDy != 0f))
            val textDrag = st.dragTextUid == p.uid && st.dragTextId >= 0
            val skip = when {
                dragging -> sel!!.ids
                textDrag -> setOf(st.dragTextId)
                else -> null
            }
            PageDraw.drawInk(c, ink, skip = skip, dimUnlinked = st.linkMode)
            if (skip != null) {
                c.translate(st.dragDx, st.dragDy)
                for (pass in 0..1) for (stroke in ink.strokes) {
                    if (stroke.id in skip && PageDraw.inPass(stroke, pass)) PageDraw.drawStroke(c, stroke)
                }
                for (t in ink.texts) if (t.id in skip) PageDraw.drawText(c, t)
            }
            c.restore()
        }
        // Prefetch the page after the last visible one.
        if (!range.isEmpty() && range.last + 1 < pages.size) {
            val next = pages[range.last + 1]
            if (next.isPdf) renderer.page(next, renderer.baseWidthFor(next, l.fitW, l.fitW * s)) { next.uid in wantedUids }
        }
        if (sel != null) {
            st.selectionScreenRect(sel)?.let { r ->
                selPaint.strokeWidth = 1.5f * st.density
                selPaint.pathEffect = DashPathEffect(floatArrayOf(6f * st.density, 4f * st.density), 0f)
                c.drawRoundRect(r, 6f * st.density, 6f * st.density, selPaint)
            }
        }
    }

    fun drawOverlay(c: Canvas, st: EditorState) {
        val i = st.livePage
        if (i >= 0 && st.liveN > 0 && i < st.note.pages.size) {
            val l = st.layout()
            val s = st.scale
            val pxPerPt = st.pxPerPt(i)
            c.save()
            c.clipRect((l.margin - st.ox) * s, (l.tops[i] - st.oy) * s, (l.margin + l.fitW - st.ox) * s, (l.tops[i] + l.heights[i] - st.oy) * s)
            c.translate((l.margin - st.ox) * s, (l.tops[i] - st.oy) * s)
            c.scale(pxPerPt, pxPerPt)
            PageDraw.drawLive(c, st.livePts, st.liveN, st.liveTool, st.liveColor, st.liveWidth)
            c.restore()
        }
        val lp = st.lassoPage
        if (lp >= 0 && st.lassoN > 1) {
            val path = Path()
            for (k in 0 until st.lassoN) {
                val x = st.toScreenX(lp, st.lassoPts[k * 2]); val y = st.toScreenY(lp, st.lassoPts[k * 2 + 1])
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            lassoPaint.strokeWidth = 1.5f * st.density
            lassoPaint.pathEffect = DashPathEffect(floatArrayOf(5f * st.density, 4f * st.density), 0f)
            c.drawPath(path, lassoPaint)
        }
        if (!st.eraserX.isNaN()) {
            eraserPaint.strokeWidth = st.density
            c.drawCircle(st.eraserX, st.eraserY, st.eraserRadiusPx, eraserPaint)
        }
    }

    /** Thumbnail of page [i] for the page sheet, drawn into a [w] × [h] box. */
    fun drawThumb(c: Canvas, st: EditorState, renderer: PageRenderer, i: Int, w: Float, h: Float, wanted: () -> Boolean) {
        val p = st.note.pages.getOrNull(i) ?: return
        rect.set(0f, 0f, w, h)
        c.drawRect(rect, paper)
        val scale = w / p.w
        if (p.isPdf) {
            val bmp = renderer.page(p, thumbWidth(w), wanted)
            if (bmp != null) c.drawBitmap(bmp, null, rect, bitmapPaint)
        }
        c.save()
        c.clipRect(rect)
        c.scale(scale, scale)
        PageDraw.drawTemplate(c, p, scale)
        PageDraw.drawInk(c, st.inkOf(p.uid))
        c.restore()
        edge.strokeWidth = st.density
        c.drawRect(rect, edge)
    }

    /** Thumbnail bitmaps come in a few widths so the cache is shared between grid cells. */
    private fun thumbWidth(w: Float): Int = when {
        w <= 160f -> 160
        w <= 320f -> 320
        else -> 480
    }
}
