package com.schedulewidget.mobile.notes.ink

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Hit-testing, erasing and lasso selection of strokes (page points). Pure functions. */
object InkGeometry {
    /** Pen width at [pressure]: light touches stay visible, full pressure is the nominal width. */
    fun pressureWidth(width: Float, pressure: Float): Float =
        width * (0.35f + 0.65f * pressure.coerceIn(0f, 1f).pow(0.8f))

    // ---- per-kind width ----
    // A stroke's drawn width at a point depends on its tool: the pen follows the raw pressure (unchanged since the
    // first version, so old notes render the same), pencil and brush follow a lightly smoothed pressure, the brush
    // also tapers over the first and last [brushTaperLength] points of its length. Drawing, bounds and hit-testing
    // all go through [halfWidth], so what is erased or selected is what is drawn.

    /** Brush width at full pressure, relative to the nominal width. */
    const val BRUSH_MAX = 2.5f
    /** Brush width at zero pressure, relative to the nominal width. */
    const val BRUSH_MIN = 0.1f
    /** Width of a brush tip (the very first/last point) relative to the untapered width. */
    const val BRUSH_TIP = 0.2f

    /** Largest drawn width of a [tool] stroke relative to its nominal width (bounds use it). */
    fun maxWidthFactor(tool: Int): Float = if (tool == Tool.BRUSH) BRUSH_MAX else 1f

    /** Pencil width: pressure changes it only a little (darkness carries the pressure, see [pencilCoreWidth]). */
    fun pencilWidth(width: Float, pressure: Float): Float = width * (0.7f + 0.3f * pressure.coerceIn(0f, 1f))

    /** Width of the pencil's darker core: from a hairline at light pressure to the full pencil width. */
    fun pencilCoreWidth(width: Float, pressure: Float): Float {
        val p = pressure.coerceIn(0f, 1f)
        return pencilWidth(width, p) * (0.15f + 0.85f * p * p)
    }

    /** Untapered brush width: hairline at light pressure, [BRUSH_MAX] × [width] at full pressure. */
    fun brushWidth(width: Float, pressure: Float): Float {
        val p = pressure.coerceIn(0f, 1f)
        return width * (BRUSH_MIN + (BRUSH_MAX - BRUSH_MIN) * p * sqrt(p))
    }

    /** Length (page points) over which a brush stroke narrows to its tips. */
    fun brushTaperLength(width: Float): Float = (width * 3f).coerceIn(2f, 24f)

    /** Taper factor of a brush point [dStart] / [dEnd] points along the stroke from its ends. */
    fun brushTaper(dStart: Float, dEnd: Float, width: Float): Float {
        val t = brushTaperLength(width)
        val a = sqrt((dStart / t).coerceIn(0f, 1f))
        val b = sqrt((dEnd / t).coerceIn(0f, 1f))
        return BRUSH_TIP + (1f - BRUSH_TIP) * min(a, b)
    }

    /** Pressure at point [i] of the x, y, p triples [pts] (first [n] points), averaged 1-2-1 with its neighbours. */
    fun smoothedPressure(pts: FloatArray, n: Int, i: Int): Float {
        val a = pts[max(0, i - 1) * 3 + 2]
        val b = pts[i * 3 + 2]
        val c = pts[min(n - 1, i + 1) * 3 + 2]
        return (a + 2f * b + c) * 0.25f
    }

    /**
     * Cumulative path length at each of the first [n] points of [pts], written into [out] when it is large enough
     * (else a new array). Only brush strokes need it (for the tapers).
     */
    fun arcLengths(pts: FloatArray, n: Int, out: FloatArray? = null): FloatArray {
        val a = if (out != null && out.size >= n) out else FloatArray(max(1, n))
        if (n == 0) return a
        a[0] = 0f
        for (i in 1 until n) a[i] = a[i - 1] + dist(pts[i * 3 - 3], pts[i * 3 - 2], pts[i * 3], pts[i * 3 + 1])
        return a
    }

    /**
     * Half the drawn width of point [i] of a [tool] stroke of nominal [width] whose x, y, p triples are [pts] (first
     * [n] points). [arc] = [arcLengths] of the points; only read for the brush (may be null otherwise).
     */
    fun halfWidth(tool: Int, width: Float, pts: FloatArray, n: Int, i: Int, arc: FloatArray?): Float = when (tool) {
        Tool.HIGHLIGHTER, Tool.BALLPOINT -> width / 2f
        Tool.PENCIL -> pencilWidth(width, smoothedPressure(pts, n, i)) / 2f
        Tool.BRUSH -> {
            val w = brushWidth(width, smoothedPressure(pts, n, i))
            if (n < 2 || arc == null) w / 2f else w * brushTaper(arc[i], arc[n - 1] - arc[i], width) / 2f
        }
        else -> pressureWidth(width, pts[i * 3 + 2]) / 2f
    }

    fun distSqPointSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len = dx * dx + dy * dy
        var t = if (len <= 0f) 0f else ((px - ax) * dx + (py - ay) * dy) / len
        t = t.coerceIn(0f, 1f)
        val cx = ax + t * dx - px; val cy = ay + t * dy - py
        return cx * cx + cy * cy
    }

    private fun cross(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float =
        (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    /** True when the segments a-b and c-d cross (proper or touching). */
    fun segmentsIntersect(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Boolean {
        val d1 = cross(cx, cy, dx, dy, ax, ay)
        val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy)
        val d4 = cross(ax, ay, bx, by, dx, dy)
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true
        return false
    }

    /** Squared distance between the segments a-b and c-d. */
    fun distSqSegments(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float): Float {
        if (segmentsIntersect(ax, ay, bx, by, cx, cy, dx, dy)) return 0f
        return min(
            min(distSqPointSegment(ax, ay, cx, cy, dx, dy), distSqPointSegment(bx, by, cx, cy, dx, dy)),
            min(distSqPointSegment(cx, cy, ax, ay, bx, by), distSqPointSegment(dx, dy, ax, ay, bx, by)),
        )
    }

    /**
     * Does the eraser, moved from (ax, ay) to (bx, by) with radius [r], touch [s]? A tap is a = b.
     * The stroke's own width counts, so thick strokes are easier to hit.
     */
    fun hits(s: Stroke, ax: Float, ay: Float, bx: Float, by: Float, r: Float): Boolean {
        val b = s.bounds()
        if (max(ax, bx) + r < b[0] || min(ax, bx) - r > b[2] || max(ay, by) + r < b[1] || min(ay, by) - r > b[3]) return false
        val n = s.count
        if (n == 1) {
            val rr = r + s.halfWidth(0)
            return distSqPointSegment(s.x(0), s.y(0), ax, ay, bx, by) <= rr * rr
        }
        for (i in 0 until n - 1) {
            val rr = r + max(s.halfWidth(i), s.halfWidth(i + 1))
            if (distSqSegments(s.x(i), s.y(i), s.x(i + 1), s.y(i + 1), ax, ay, bx, by) <= rr * rr) return true
        }
        return false
    }

    fun hits(s: Stroke, x: Float, y: Float, r: Float): Boolean = hits(s, x, y, x, y, r)

    /** Stroke eraser: [strokes] without the ones the eraser segment touches; null when nothing was hit. */
    fun eraseWhole(strokes: List<Stroke>, ax: Float, ay: Float, bx: Float, by: Float, r: Float): List<Stroke>? {
        var hit = false
        val kept = strokes.filter { s -> (!hits(s, ax, ay, bx, by, r)).also { if (!it) hit = true } }
        return if (hit) kept else null
    }

    /**
     * Partial (pixel-like) eraser: cuts the parts of [s] under the eraser segment out, leaving 0..n pieces.
     * Returns null when [s] is untouched. Pieces get ids from [newId]; single leftover points are dropped.
     */
    fun eraseSplit(s: Stroke, ax: Float, ay: Float, bx: Float, by: Float, r: Float, newId: () -> Long): List<Stroke>? {
        if (!hits(s, ax, ay, bx, by, r)) return null
        val n = s.count
        val removed = BooleanArray(n) { i ->
            val rr = r + s.halfWidth(i)
            distSqPointSegment(s.x(i), s.y(i), ax, ay, bx, by) <= rr * rr
        }
        // A long segment can pass under the eraser with both ends outside: cut it there.
        val cut = BooleanArray(max(0, n - 1)) { i ->
            if (removed[i] || removed[i + 1]) false else {
                val radius = r + max(s.halfWidth(i), s.halfWidth(i + 1))
                distSqSegments(s.x(i), s.y(i), s.x(i + 1), s.y(i + 1), ax, ay, bx, by) <= radius * radius
            }
        }
        val pieces = ArrayList<Stroke>()
        var start = -1
        fun flush(endExclusive: Int) {
            if (start >= 0 && endExclusive - start >= 2) {
                pieces += s.copy(id = newId(), pts = s.pts.copyOfRange(start * 3, endExclusive * 3))
            }
            start = -1
        }
        for (i in 0 until n) {
            if (removed[i]) { flush(i); continue }
            if (start < 0) start = i
            if (i < n - 1 && cut[i]) flush(i + 1)
        }
        flush(n)
        if (pieces.size == 1 && pieces[0].count == n) return null
        return pieces
    }

    /** Even-odd point-in-polygon; [poly] is x, y pairs (closed implicitly). */
    fun pointInPolygon(x: Float, y: Float, poly: FloatArray): Boolean {
        val n = poly.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]; val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]; val yj = poly[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** A stroke is lassoed when at least [fraction] of its points are inside [poly]. */
    fun inLasso(s: Stroke, poly: FloatArray, fraction: Float = 0.6f): Boolean {
        val n = s.count
        if (n == 0) return false
        var inside = 0
        for (i in 0 until n) if (pointInPolygon(s.x(i), s.y(i), poly)) inside++
        return inside >= max(1f, n * fraction)
    }

    fun lassoSelect(strokes: List<Stroke>, poly: FloatArray): Set<Long> =
        strokes.asSequence().filter { inLasso(it, poly) }.map { it.id }.toSet()

    fun translate(s: Stroke, dx: Float, dy: Float): Stroke {
        val p = s.pts.copyOf()
        var i = 0
        while (i < p.size) { p[i] += dx; p[i + 1] += dy; i += 3 }
        return s.copy(pts = p)
    }

    /** Union of the strokes' bounds (left, top, right, bottom) or null when empty. */
    fun unionBounds(strokes: Collection<Stroke>): FloatArray? {
        if (strokes.isEmpty()) return null
        val u = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (s in strokes) {
            val b = s.bounds()
            u[0] = min(u[0], b[0]); u[1] = min(u[1], b[1]); u[2] = max(u[2], b[2]); u[3] = max(u[3], b[3])
        }
        return u
    }

    /** Distance between two points. */
    fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))
}
