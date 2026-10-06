package com.schedulewidget.mobile.notes.ink

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Hold to shape" for writing pens: a stroke held still at its end becomes a straight line, an ellipse or a smoothed
 * curve. Works on x, y, pressure triples in page points; the result is the same format (an ordinary stroke).
 * Pure functions.
 */
object ShapeCorrection {
    // ponytail: fixed-threshold heuristics (no learned recogniser). Ambiguous strokes (corners, scribbles, polygons)
    // return null and stay as written; tune the constants below if real handwriting is corrected too rarely/often.
    private const val LINE_DEVIATION = 0.05f // max distance from the chord, relative to its length
    private const val LINE_LENGTH_RATIO = 1.12f // path length / chord
    private const val CLOSED_GAP = 0.22f // end-to-start gap relative to the bounding-box diagonal
    private const val ELLIPSE_MEAN_ERR = 0.08f // mean |normalised radius - 1|
    private const val ELLIPSE_MAX_ERR = 0.2f // a square's best fit is >= 0.235, a triangle's ~0.5
    private const val ELLIPSE_MIN_ASPECT = 0.2f
    private const val CURVE_SAMPLES = 20
    private const val CURVE_MAX_TURN = 0.5f // radians at one sample (and 0.55 over two): sharper = a corner
    // The check runs after one smoothing pass, which spreads a corner over 2-3 samples (>= ~3/4 of it in two):
    // 0.55 still rejects corners from ~45 degrees, about where the unsmoothed 0.7 did.
    private const val CURVE_MAX_PAIR_TURN = 0.55f
    private const val PI_F = 3.1415927f
    private const val TWO_PI = 2f * PI_F

    /**
     * The corrected shape of the first [n] points of [pts], or null to keep the stroke as written (too small,
     * invalid, or not clearly a line / ellipse / smooth curve). [minSize] is the smallest bounding-box diagonal
     * (page points) worth correcting; the caller derives it from screen dp so it means the same on every device.
     */
    fun correct(pts: FloatArray, n: Int, minSize: Float): FloatArray? {
        if (n < 3 || n * 3 > pts.size || !(minSize > 0f) || minSize.isInfinite()) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var pSum = 0f
        for (i in 0 until n) {
            val x = pts[i * 3]; val y = pts[i * 3 + 1]; val p = pts[i * 3 + 2]
            if (!x.isFinite() || !y.isFinite() || !p.isFinite()) return null
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            pSum += p
        }
        val diag = hypot(maxX - minX, maxY - minY)
        if (diag < minSize) return null
        val pressure = (pSum / n).coerceIn(0f, 1f)
        val step = minSize / 12f
        val len = pathLength(pts, n)
        val chord = hypot(pts[(n - 1) * 3] - pts[0], pts[(n - 1) * 3 + 1] - pts[1])
        return line(pts, n, chord, len, pressure, step)
            ?: if (chord <= CLOSED_GAP * diag) ellipse(pts, n, pressure, step) else curve(pts, n, len, step)
    }

    private fun pathLength(pts: FloatArray, n: Int): Float {
        var len = 0f
        for (i in 1 until n) len += hypot(pts[i * 3] - pts[i * 3 - 3], pts[i * 3 + 1] - pts[i * 3 - 2])
        return len
    }

    /** Start to end, evenly resampled (so the partial eraser still cuts it), one pressure. */
    private fun line(pts: FloatArray, n: Int, chord: Float, len: Float, pressure: Float, step: Float): FloatArray? {
        if (chord <= 0f || len > chord * LINE_LENGTH_RATIO) return null
        val ax = pts[0]; val ay = pts[1]; val bx = pts[(n - 1) * 3]; val by = pts[(n - 1) * 3 + 1]
        for (i in 1 until n - 1) {
            val d = abs((bx - ax) * (ay - pts[i * 3 + 1]) - (ax - pts[i * 3]) * (by - ay)) / chord
            if (d > LINE_DEVIATION * chord) return null
        }
        val m = (ceil(chord / step).toInt() + 1).coerceIn(2, 256)
        return FloatArray(m * 3).also { out ->
            for (k in 0 until m) {
                val t = k / (m - 1f)
                out[k * 3] = ax + (bx - ax) * t; out[k * 3 + 1] = ay + (by - ay) * t; out[k * 3 + 2] = pressure
            }
        }
    }

    /**
     * A closed round stroke: ellipse along the stroke's principal axes, starting where the pen started and running
     * the same way round. Rejects strokes that leave the ellipse (polygons), go round less or more than about once,
     * or turn back (scribbles).
     */
    private fun ellipse(pts: FloatArray, n: Int, pressure: Float, step: Float): FloatArray? {
        // Axis direction from the length-weighted covariance (sampling density does not bias it).
        var w = 0f; var mx = 0f; var my = 0f
        for (i in 1 until n) {
            val l = hypot(pts[i * 3] - pts[i * 3 - 3], pts[i * 3 + 1] - pts[i * 3 - 2])
            w += l; mx += l * (pts[i * 3] + pts[i * 3 - 3]) / 2f; my += l * (pts[i * 3 + 1] + pts[i * 3 - 2]) / 2f
        }
        if (w <= 0f) return null
        mx /= w; my /= w
        var sxx = 0f; var syy = 0f; var sxy = 0f
        for (i in 1 until n) {
            val l = hypot(pts[i * 3] - pts[i * 3 - 3], pts[i * 3 + 1] - pts[i * 3 - 2])
            val dx = (pts[i * 3] + pts[i * 3 - 3]) / 2f - mx; val dy = (pts[i * 3 + 1] + pts[i * 3 - 2]) / 2f - my
            sxx += l * dx * dx; syy += l * dy * dy; sxy += l * dx * dy
        }
        val theta = 0.5f * atan2(2f * sxy, sxx - syy)
        val ca = cos(theta); val sa = sin(theta)
        // Extent along the axes gives centre and radii.
        var uMin = Float.MAX_VALUE; var uMax = -Float.MAX_VALUE; var vMin = Float.MAX_VALUE; var vMax = -Float.MAX_VALUE
        for (i in 0 until n) {
            val u = pts[i * 3] * ca + pts[i * 3 + 1] * sa; val v = -pts[i * 3] * sa + pts[i * 3 + 1] * ca
            uMin = min(uMin, u); uMax = max(uMax, u); vMin = min(vMin, v); vMax = max(vMax, v)
        }
        val a = (uMax - uMin) / 2f; val b = (vMax - vMin) / 2f
        if (a <= 0f || b <= 0f || min(a, b) < ELLIPSE_MIN_ASPECT * max(a, b)) return null
        val cu = (uMin + uMax) / 2f; val cv = (vMin + vMax) / 2f
        var errSum = 0f
        var sweep = 0f; var sweepAbs = 0f
        var prev = 0f
        for (i in 0 until n) {
            val nu = (pts[i * 3] * ca + pts[i * 3 + 1] * sa - cu) / a
            val nv = (-pts[i * 3] * sa + pts[i * 3 + 1] * ca - cv) / b
            val err = abs(sqrt(nu * nu + nv * nv) - 1f)
            if (err > ELLIPSE_MAX_ERR) return null
            errSum += err
            val ang = atan2(nv, nu)
            if (i > 0) {
                val d = wrap(ang - prev)
                sweep += d; sweepAbs += abs(d)
            }
            prev = ang
        }
        val turns = abs(sweep) / TWO_PI
        if (errSum / n > ELLIPSE_MEAN_ERR || turns < 0.85f || turns > 1.3f || sweepAbs > abs(sweep) * 1.15f) return null
        val start = run {
            val u = (pts[0] * ca + pts[1] * sa - cu) / a; val v = (-pts[0] * sa + pts[1] * ca - cv) / b
            atan2(v, u)
        }
        val dir = if (sweep >= 0f) 1f else -1f
        val perimeter = PI_F * (3f * (a + b) - sqrt((3f * a + b) * (a + 3f * b))) // Ramanujan
        val m = (ceil(perimeter / step).toInt() + 1).coerceIn(24, 256)
        return FloatArray(m * 3).also { out ->
            for (k in 0 until m) {
                val t = start + dir * TWO_PI * k / (m - 1f)
                val u = cu + a * cos(t); val v = cv + b * sin(t)
                out[k * 3] = u * ca - v * sa; out[k * 3 + 1] = u * sa + v * ca; out[k * 3 + 2] = pressure
            }
        }
    }

    /**
     * An open stroke without corners: resampled evenly and smoothed, ends and pressure profile kept.
     * Rejects corners (V, L, zigzag) and strokes that wind round more than once.
     */
    private fun curve(pts: FloatArray, n: Int, len: Float, step: Float): FloatArray? {
        if (len <= 0f) return null
        val m = (ceil(len / step).toInt() + 1).coerceIn(2 * CURVE_SAMPLES - 1, 256)
        // Moving-average radius of about one coarse step of path (>= 2 samples): wide enough to cancel a wobble that
        // spans only a few sparse pen samples, narrow enough that a corner stays within 2-3 coarse samples.
        val r = ((m - 1f) / (CURVE_SAMPLES - 1)).roundToInt()
        var cur = smooth(resample(pts, n, len, m), m, r)
        // Corner check on a coarse resample of the once-smoothed stroke, so hand jitter does not count as turning.
        val coarse = resample(cur, m, pathLength(cur, m), CURVE_SAMPLES)
        var total = 0f
        var last = 0f
        for (k in 1 until CURVE_SAMPLES - 1) {
            val d = turn(coarse, k)
            if (abs(d) > CURVE_MAX_TURN || abs(d + last) > CURVE_MAX_PAIR_TURN) return null
            total += abs(d)
            last = d
        }
        if (total > TWO_PI) return null
        // Three more passes: removes the remaining wobble, keeps the overall bend.
        repeat(3) { cur = smooth(cur, m, r) }
        return cur
    }

    /** One moving-average pass over [m] points (radius [r], shrinking at the ends; both ends pinned). */
    private fun smooth(q: FloatArray, m: Int, r: Int): FloatArray = q.copyOf().also { next ->
        for (k in 1 until m - 1) {
            val w = min(r, min(k, m - 1 - k))
            for (c in 0 until 3) {
                var s = 0f
                for (j in k - w..k + w) s += q[j * 3 + c]
                next[k * 3 + c] = s / (2 * w + 1)
            }
        }
    }

    private fun turn(q: FloatArray, k: Int): Float {
        val a1 = atan2(q[k * 3 + 1] - q[k * 3 - 2], q[k * 3] - q[k * 3 - 3])
        val a2 = atan2(q[k * 3 + 4] - q[k * 3 + 1], q[k * 3 + 3] - q[k * 3])
        return wrap(a2 - a1)
    }

    private fun wrap(d: Float): Float = if (d > PI_F) d - TWO_PI else if (d < -PI_F) d + TWO_PI else d

    /** [m] points evenly spaced along the polyline (x, y and pressure interpolated); exact first and last points. */
    private fun resample(pts: FloatArray, n: Int, len: Float, m: Int): FloatArray {
        val out = FloatArray(m * 3)
        var seg = 1
        var acc = 0f
        var segLen = hypot(pts[3] - pts[0], pts[4] - pts[1])
        for (k in 0 until m) {
            val target = len * k / (m - 1f)
            while (seg < n - 1 && acc + segLen < target) {
                acc += segLen; seg++
                segLen = hypot(pts[seg * 3] - pts[seg * 3 - 3], pts[seg * 3 + 1] - pts[seg * 3 - 2])
            }
            val t = if (segLen > 0f) ((target - acc) / segLen).coerceIn(0f, 1f) else 1f
            for (c in 0 until 3) out[k * 3 + c] = pts[seg * 3 - 3 + c] + (pts[seg * 3 + c] - pts[seg * 3 - 3 + c]) * t
        }
        for (c in 0 until 3) { out[c] = pts[c]; out[(m - 1) * 3 + c] = pts[(n - 1) * 3 + c] }
        return out
    }
}
