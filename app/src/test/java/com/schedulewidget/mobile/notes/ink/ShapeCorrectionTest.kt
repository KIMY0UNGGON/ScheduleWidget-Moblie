package com.schedulewidget.mobile.notes.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class ShapeCorrectionTest {
    private val minSize = 20f

    /** Polyline through [v] (x, y pairs), a point every page point, pressure 0.5. */
    private fun poly(vararg v: Float): FloatArray {
        val out = ArrayList<Float>()
        for (k in 0 until v.size / 2 - 1) {
            val ax = v[k * 2]; val ay = v[k * 2 + 1]; val bx = v[k * 2 + 2]; val by = v[k * 2 + 3]
            val steps = maxOf(1, hypot(bx - ax, by - ay).toInt())
            for (s in 0 until steps) { val t = s / steps.toFloat(); out += ax + (bx - ax) * t; out += ay + (by - ay) * t; out += 0.5f }
        }
        out += v[v.size - 2]; out += v[v.size - 1]; out += 0.5f
        return out.toFloatArray()
    }

    /** Ellipse round (cx, cy), radii [a] / [b] rotated by [rot], [turns] times round, radius wobble [wobble]. */
    private fun ellipse(cx: Float, cy: Float, a: Float, b: Float, rot: Float = 0f, turns: Float = 1.03f, wobble: Float = 0f): FloatArray {
        val n = 120
        return FloatArray(n * 3).also { out ->
            for (i in 0 until n) {
                val t = 2.0 * PI * turns * i / (n - 1)
                val w = 1f + wobble * sin(5 * t).toFloat()
                val u = a * w * cos(t).toFloat(); val v = b * w * sin(t).toFloat()
                out[i * 3] = cx + u * cos(rot) - v * sin(rot); out[i * 3 + 1] = cy + u * sin(rot) + v * cos(rot); out[i * 3 + 2] = 0.6f
            }
        }
    }

    private fun correct(p: FloatArray) = ShapeCorrection.correct(p, p.size / 3, minSize)

    private fun assertEnds(raw: FloatArray, out: FloatArray) {
        assertEquals(raw[0], out[0], 1e-3f); assertEquals(raw[1], out[1], 1e-3f)
        assertEquals(raw[raw.size - 3], out[out.size - 3], 1e-3f); assertEquals(raw[raw.size - 2], out[out.size - 2], 1e-3f)
    }

    @Test fun nearStraightStrokeBecomesLineWithSameEnds() {
        val raw = poly(0f, 0f, 50f, 2f, 100f, 1f)
        val out = checkNotNull(correct(raw)) { "line" }
        assertEnds(raw, out)
        for (i in 0 until out.size / 3) {
            // On the chord from (0, 0) to (100, 1).
            assertEquals(out[i * 3] / 100f, out[i * 3 + 1], 1e-3f)
            assertEquals(0.5f, out[i * 3 + 2], 1e-4f)
        }
        assertTrue("resampled so the partial eraser can cut it", out.size / 3 > 2)
    }

    @Test fun roundClosedStrokesBecomeEllipses() {
        val circle = checkNotNull(correct(ellipse(100f, 100f, 40f, 40f, wobble = 0.03f))) { "circle" }
        for (i in 0 until circle.size / 3) assertEquals(40f, hypot(circle[i * 3] - 100f, circle[i * 3 + 1] - 100f), 3f)
        // Closed: ends where it starts.
        assertEquals(circle[0], circle[circle.size - 3], 1e-2f); assertEquals(circle[1], circle[circle.size - 2], 1e-2f)

        val tilted = checkNotNull(correct(ellipse(100f, 100f, 60f, 30f, rot = 0.5f))) { "tilted ellipse" }
        for (i in 0 until tilted.size / 3) {
            val dx = tilted[i * 3] - 100f; val dy = tilted[i * 3 + 1] - 100f
            val u = dx * cos(0.5f) + dy * sin(0.5f); val v = -dx * sin(0.5f) + dy * cos(0.5f)
            assertEquals(1f, (u / 60f) * (u / 60f) + (v / 30f) * (v / 30f), 0.1f)
        }
    }

    @Test fun smoothOpenArcBecomesCurveKeepingEnds() {
        val n = 80
        val raw = FloatArray(n * 3) { 0f }
        for (i in 0 until n) {
            val t = PI / 2 * i / (n - 1)
            // Hand jitter across the arc.
            raw[i * 3] = (60 * cos(t)).toFloat() + if (i % 2 == 0) 0.15f else -0.15f
            raw[i * 3 + 1] = (60 * sin(t)).toFloat()
            raw[i * 3 + 2] = i / (n - 1f)
        }
        val out = checkNotNull(correct(raw)) { "arc" }
        assertEnds(raw, out)
        // Still the arc, not collapsed to its chord.
        val mid = out.size / 3 / 2
        assertEquals(60f, hypot(out[mid * 3], out[mid * 3 + 1]), 2f)
        // Pressure profile kept (rises along the stroke).
        assertTrue(out[2] < out[out.size - 1])
    }

    /** Sum of absolute and net turning along the polyline in degrees, measured like the device test. */
    private fun turning(p: FloatArray): Pair<Float, Float> {
        var total = 0.0; var net = 0.0; var prev = Double.NaN
        for (i in 1 until p.size / 3) {
            val dx = p[i * 3] - p[i * 3 - 3]; val dy = p[i * 3 + 1] - p[i * 3 - 2]
            if (hypot(dx, dy) <= 1e-3f) continue
            val a = atan2(dy, dx).toDouble()
            if (!prev.isNaN()) {
                var d = a - prev
                while (d > PI) d -= 2 * PI
                while (d < -PI) d += 2 * PI
                total += abs(d); net += d
            }
            prev = a
        }
        return Math.toDegrees(total).toFloat() to Math.toDegrees(net).toFloat()
    }

    @Test fun sparseShakyOpenArcBecomesSmoothCurve() {
        // The device test's pen input: 31 samples ~10 apart round (300, 720), r 110, 200..340 degrees, odd samples
        // pushed +-4 radially (a wobble spanning 4 samples), pressure rising.
        val n = 30
        val raw = FloatArray((n + 1) * 3)
        for (i in 0..n) {
            val a = Math.toRadians(200.0 + 140.0 * i / n)
            val r = 110f + when (i % 4) { 1 -> 4f; 3 -> -4f; else -> 0f }
            raw[i * 3] = 300f + r * cos(a).toFloat(); raw[i * 3 + 1] = 720f + r * sin(a).toFloat(); raw[i * 3 + 2] = 0.3f + 0.6f * i / n
        }
        turning(raw).let { (total, net) -> assertTrue("input is shaky", total > 3f * abs(net)) }
        // Dense (~1.7 point) and coarse (~7 point) smoothing grids.
        for (size in listOf(20f, 84f)) {
            val out = checkNotNull(ShapeCorrection.correct(raw, n + 1, size)) { "shaky arc rejected at minSize $size" }
            assertEnds(raw, out)
            val (total, net) = turning(out)
            assertTrue("net turning $net at minSize $size", abs(net) in 90f..200f)
            assertTrue("not smooth at minSize $size: total=$total net=$net", total <= 1.25f * abs(net) + 20f)
            // Still the arc, not flattened toward its chord.
            val mid = out.size / 3 / 2
            assertEquals(110f, hypot(out[mid * 3] - 300f, out[mid * 3 + 1] - 720f), 4f)
            assertTrue(out[2] < out[out.size - 1])
        }
    }

    @Test fun ambiguousStrokesStayAsWritten() {
        assertNull("zigzag", correct(poly(0f, 0f, 15f, 30f, 30f, 0f, 45f, 30f, 60f, 0f, 75f, 30f)))
        assertNull("V corner", correct(poly(0f, 0f, 50f, 80f, 100f, 0f)))
        assertNull("triangle", correct(poly(0f, 0f, 100f, 0f, 50f, 86.6f, 0f, 0f)))
        assertNull("square", correct(poly(0f, 0f, 80f, 0f, 80f, 80f, 0f, 80f, 0f, 0f)))
        assertNull("scribble going round three times", correct(ellipse(100f, 100f, 40f, 40f, turns = 3f)))
        assertNull("back and forth", correct(poly(0f, 0f, 100f, 0f, 0f, 2f)))
    }

    @Test fun tinyOrInvalidInputIsRejected() {
        assertNull("smaller than minSize", correct(ellipse(10f, 10f, 5f, 5f)))
        assertNull("dot", correct(poly(5f, 5f, 5.5f, 5f)))
        val nan = poly(0f, 0f, 100f, 0f).also { it[4] = Float.NaN }
        assertNull(correct(nan))
        val inf = poly(0f, 0f, 100f, 0f).also { it[3] = Float.POSITIVE_INFINITY }
        assertNull(correct(inf))
        val ok = poly(0f, 0f, 100f, 0f)
        assertNull(ShapeCorrection.correct(ok, ok.size / 3 + 1, minSize))
        assertNull(ShapeCorrection.correct(ok, 2, minSize))
        assertNull(ShapeCorrection.correct(ok, ok.size / 3, Float.NaN))
        assertNull(ShapeCorrection.correct(ok, ok.size / 3, 0f))
    }
}
