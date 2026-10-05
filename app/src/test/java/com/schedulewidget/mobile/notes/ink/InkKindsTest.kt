package com.schedulewidget.mobile.notes.ink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-kind widths (pen, highlighter, ballpoint, pencil, brush) and the bounds / hit-tests built on them. */
class InkKindsTest {
    private val kinds = intArrayOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.BALLPOINT, Tool.PENCIL, Tool.BRUSH)

    /** Horizontal stroke from (x0, y) to (x1, y), a point every [step], all at [pressure]. */
    private fun line(tool: Int, x0: Float, x1: Float, y: Float, width: Float, pressure: Float = 1f, step: Float = 1f): Stroke {
        val pts = ArrayList<Float>()
        var x = x0
        while (x <= x1 + 1e-3f) { pts += x; pts += y; pts += pressure; x += step }
        return Stroke(1, tool, 0xFF000000.toInt(), width, pts.toFloatArray())
    }

    @Test fun penAndHighlighterKeepTheirOriginalWidths() {
        val pts = floatArrayOf(0f, 0f, 0.2f, 5f, 0f, 0.9f, 10f, 0f, 0.5f)
        val pen = Stroke(1, Tool.PEN, 0, 3f, pts)
        for (i in 0 until 3) assertEquals(InkGeometry.pressureWidth(3f, pts[i * 3 + 2]) / 2f, pen.halfWidth(i), 0f)
        val hl = Stroke(1, Tool.HIGHLIGHTER, 0, 12f, pts)
        for (i in 0 until 3) assertEquals(6f, hl.halfWidth(i), 0f)
    }

    @Test fun ballpointIgnoresPressure() {
        val pts = floatArrayOf(0f, 0f, 0.05f, 5f, 0f, 1f, 10f, 0f, 0.3f)
        val s = Stroke(1, Tool.BALLPOINT, 0, 2f, pts)
        for (i in 0 until 3) assertEquals(1f, s.halfWidth(i), 0f)
    }

    @Test fun pencilWidthVariesLittleCoreVariesMuch() {
        val light = InkGeometry.pencilWidth(2f, 0f)
        val firm = InkGeometry.pencilWidth(2f, 1f)
        assertEquals(2f, firm, 1e-5f)
        assertTrue(light >= 0.65f * firm)
        val coreLight = InkGeometry.pencilCoreWidth(2f, 0.1f)
        val coreFirm = InkGeometry.pencilCoreWidth(2f, 1f)
        assertEquals(firm, coreFirm, 1e-5f)
        assertTrue(coreLight < 0.25f * coreFirm)
        // The core never pokes out of the halo.
        for (k in 0..10) {
            val p = k / 10f
            assertTrue(InkGeometry.pencilCoreWidth(2f, p) <= InkGeometry.pencilWidth(2f, p) + 1e-6f)
        }
    }

    @Test fun brushRangeIsStrong() {
        assertEquals(InkGeometry.BRUSH_MAX * 2f, InkGeometry.brushWidth(2f, 1f), 1e-5f)
        assertEquals(InkGeometry.BRUSH_MIN * 2f, InkGeometry.brushWidth(2f, 0f), 1e-5f)
        var prev = 0f
        for (k in 0..10) {
            val w = InkGeometry.brushWidth(2f, k / 10f)
            assertTrue(w >= prev)
            prev = w
        }
    }

    @Test fun brushTapersAtBothEnds() {
        val s = line(Tool.BRUSH, 0f, 100f, 0f, width = 2f)
        val full = InkGeometry.brushWidth(2f, 1f) / 2f
        assertEquals(full * InkGeometry.BRUSH_TIP, s.halfWidth(0), 1e-4f)
        assertEquals(full * InkGeometry.BRUSH_TIP, s.halfWidth(s.count - 1), 1e-4f)
        assertEquals(full, s.halfWidth(s.count / 2), 1e-4f)
        assertTrue(s.halfWidth(2) < s.halfWidth(4))
        // A single tap is not tapered away.
        val dot = Stroke(1, Tool.BRUSH, 0, 2f, floatArrayOf(5f, 5f, 1f))
        assertEquals(full, dot.halfWidth(0), 1e-4f)
    }

    @Test fun pressureSmoothingAveragesNeighbours() {
        val pts = floatArrayOf(0f, 0f, 0f, 1f, 0f, 1f, 2f, 0f, 0f)
        assertEquals(0.5f, InkGeometry.smoothedPressure(pts, 3, 1), 1e-6f)
        assertEquals(0.25f, InkGeometry.smoothedPressure(pts, 3, 0), 1e-6f)
        assertEquals(0.25f, InkGeometry.smoothedPressure(pts, 3, 2), 1e-6f)
    }

    @Test fun arcLengthsReuseTheBuffer() {
        val pts = floatArrayOf(0f, 0f, 1f, 3f, 4f, 1f, 3f, 10f, 1f)
        val buf = FloatArray(8)
        val a = InkGeometry.arcLengths(pts, 3, buf)
        assertTrue(a === buf)
        assertArrayEquals(floatArrayOf(0f, 5f, 11f), a.copyOf(3), 1e-5f)
        assertEquals(3, InkGeometry.arcLengths(pts, 3, FloatArray(1)).size)
    }

    @Test fun everyHalfWidthFitsTheBounds() {
        for (tool in kinds) for (pressure in floatArrayOf(0f, 0.4f, 1f)) {
            val s = line(tool, 0f, 50f, 20f, width = 4f, pressure = pressure)
            val b = s.bounds()
            for (i in 0 until s.count) {
                val hw = s.halfWidth(i)
                assertTrue("tool $tool", s.y(i) - hw >= b[1] - 1e-4f && s.y(i) + hw <= b[3] + 1e-4f)
                assertTrue("tool $tool", s.x(i) - hw >= b[0] - 1e-4f && s.x(i) + hw <= b[2] + 1e-4f)
            }
        }
    }

    @Test fun boundsIncludeTheMaximumWidthOfEachKind() {
        for (tool in kinds) {
            val s = line(tool, 0f, 10f, 5f, width = 4f)
            val hw = 2f * InkGeometry.maxWidthFactor(tool)
            assertArrayEquals("tool $tool", floatArrayOf(-hw, 5f - hw, 10f + hw, 5f + hw), s.bounds(), 1e-5f)
        }
        // The brush's full-pressure middle is wider than the nominal width and must still be inside.
        val brush = line(Tool.BRUSH, 0f, 100f, 0f, width = 4f)
        assertTrue(brush.halfWidth(50) > 2f)
        assertTrue(brush.bounds()[3] >= brush.halfWidth(50))
    }

    @Test fun hitTestFollowsEachKindsWidth() {
        // Eraser radius 1 at distance d above the middle: hit iff d <= 1 + half width there.
        for (tool in kinds) {
            val s = line(tool, 0f, 100f, 50f, width = 4f, step = 2f)
            val hw = s.halfWidth(25)
            assertTrue("tool $tool", InkGeometry.hits(s, 50f, 50f - (1f + hw) + 0.05f, 1f))
            assertFalse("tool $tool", InkGeometry.hits(s, 50f, 50f - (1f + hw) - 0.2f, 1f))
        }
        // A wide brush is hit where a pen of the same nominal width is not.
        val pen = line(Tool.PEN, 0f, 100f, 50f, width = 4f, step = 2f)
        val brush = line(Tool.BRUSH, 0f, 100f, 50f, width = 4f, step = 2f)
        assertFalse(InkGeometry.hits(pen, 50f, 54.5f, 1f))
        assertTrue(InkGeometry.hits(brush, 50f, 54.5f, 1f))
        // A light brush stroke is thin: the same spot misses it.
        val lightBrush = line(Tool.BRUSH, 0f, 100f, 50f, width = 4f, pressure = 0.1f, step = 2f)
        assertFalse(InkGeometry.hits(lightBrush, 50f, 54.5f, 1f))
    }

    @Test fun partialEraserKeepsKindAndWidth() {
        for (tool in kinds) {
            val s = line(tool, 0f, 100f, 50f, width = 3f)
            var next = 100L
            val pieces = InkGeometry.eraseSplit(s, 50f, 50f, 50f, 50f, 4f) { next++ }
            assertNotNull(pieces)
            assertEquals("tool $tool", 2, pieces!!.size)
            assertTrue(pieces.all { it.tool == tool && it.width == 3f })
            // Cut ends are where the eraser + the stroke's own width stop reaching.
            assertTrue(pieces[0].x(pieces[0].count - 1) < 50f - 4f)
            assertTrue(pieces[1].x(0) > 50f + 4f)
        }
    }

    @Test fun lassoWorksForEveryKind() {
        val poly = floatArrayOf(-5f, 0f, 60f, 0f, 60f, 40f, -5f, 40f)
        for (tool in kinds) assertTrue("tool $tool", InkGeometry.inLasso(line(tool, 0f, 50f, 20f, width = 3f), poly))
    }

    @Test fun unknownKindDrawsLikeThePen() {
        val pts = floatArrayOf(0f, 0f, 0.3f)
        assertEquals(Stroke(1, Tool.PEN, 0, 3f, pts).halfWidth(0), Stroke(1, 99, 0, 3f, pts).halfWidth(0), 0f)
    }
}
