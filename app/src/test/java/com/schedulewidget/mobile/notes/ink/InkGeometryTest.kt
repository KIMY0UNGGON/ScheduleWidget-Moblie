package com.schedulewidget.mobile.notes.ink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkGeometryTest {
    /** A horizontal pen line from (x0, y) to (x1, y) with a point every [step] points, full pressure. */
    private fun line(id: Long, x0: Float, x1: Float, y: Float, step: Float = 1f, width: Float = 2f, tool: Int = Tool.PEN): Stroke {
        val pts = ArrayList<Float>()
        var x = x0
        while (x <= x1 + 1e-3f) { pts += x; pts += y; pts += 1f; x += step }
        return Stroke(id, tool, 0xFF000000.toInt(), width, pts.toFloatArray())
    }

    @Test fun pressureWidthRange() {
        assertEquals(2f, InkGeometry.pressureWidth(2f, 1f), 1e-4f)
        assertEquals(0.7f, InkGeometry.pressureWidth(2f, 0f), 1e-4f)
        assertTrue(InkGeometry.pressureWidth(2f, 0.5f) in 0.7f..2f)
    }

    @Test fun pointSegmentDistance() {
        assertEquals(25f, InkGeometry.distSqPointSegment(5f, 5f, 0f, 0f, 10f, 0f), 1e-4f)
        // Beyond the end: distance to the end point.
        assertEquals(4f, InkGeometry.distSqPointSegment(12f, 0f, 0f, 0f, 10f, 0f), 1e-4f)
        // Degenerate segment.
        assertEquals(2f, InkGeometry.distSqPointSegment(1f, 1f, 0f, 0f, 0f, 0f), 1e-4f)
    }

    @Test fun crossingSegmentsHaveZeroDistance() {
        assertTrue(InkGeometry.segmentsIntersect(0f, 0f, 10f, 10f, 0f, 10f, 10f, 0f))
        assertEquals(0f, InkGeometry.distSqSegments(0f, 0f, 10f, 10f, 0f, 10f, 10f, 0f), 0f)
        assertEquals(9f, InkGeometry.distSqSegments(0f, 0f, 10f, 0f, 0f, 3f, 10f, 3f), 1e-4f)
    }

    @Test fun hitTestUsesRadiusAndStrokeWidth() {
        val s = line(1, 0f, 100f, 50f, step = 10f, width = 4f)
        assertTrue(InkGeometry.hits(s, 55f, 50f, 1f))
        // 5 away: radius 3 + half width 2 = 5 reaches it.
        assertTrue(InkGeometry.hits(s, 55f, 55f, 3f))
        assertFalse(InkGeometry.hits(s, 55f, 56f, 3f))
        // Bounds rejection far away.
        assertFalse(InkGeometry.hits(s, 500f, 500f, 10f))
    }

    @Test fun fastEraserSegmentCrossingAStrokeHitsIt() {
        // Eraser jumps from above to below the line between two samples: still erases.
        val s = line(1, 0f, 100f, 50f, step = 10f)
        assertTrue(InkGeometry.hits(s, 45f, 0f, 45f, 100f, 1f))
        assertNull(InkGeometry.eraseWhole(listOf(s), 200f, 0f, 200f, 100f, 1f))
        assertEquals(emptyList<Stroke>(), InkGeometry.eraseWhole(listOf(s), 45f, 0f, 45f, 100f, 1f))
    }

    @Test fun strokeEraserKeepsUntouchedStrokes() {
        val a = line(1, 0f, 100f, 10f)
        val b = line(2, 0f, 100f, 80f)
        val kept = InkGeometry.eraseWhole(listOf(a, b), 50f, 10f, 50f, 10f, 3f)
        assertNotNull(kept)
        assertEquals(listOf(2L), kept!!.map { it.id })
    }

    @Test fun partialEraserSplitsStroke() {
        val s = line(1, 0f, 100f, 50f, step = 1f, width = 1f)
        var next = 100L
        val pieces = InkGeometry.eraseSplit(s, 50f, 50f, 50f, 50f, 5f) { next++ }
        assertNotNull(pieces)
        assertEquals(2, pieces!!.size)
        val left = pieces[0]; val right = pieces[1]
        assertTrue(left.x(left.count - 1) < 45f)
        assertTrue(right.x(0) > 55f)
        assertEquals(0f, left.x(0), 0f)
        assertEquals(100f, right.x(right.count - 1), 1e-3f)
        assertTrue(pieces.all { it.id >= 100L })
        // Untouched: null.
        assertNull(InkGeometry.eraseSplit(s, 50f, 90f, 50f, 90f, 5f) { next++ })
    }

    @Test fun partialEraserCutsLongSegmentBetweenSamples() {
        // Only two far-apart samples; the eraser crosses the middle of the segment.
        val s = Stroke(1, Tool.PEN, 0, 1f, floatArrayOf(0f, 0f, 1f, 100f, 0f, 1f))
        val pieces = InkGeometry.eraseSplit(s, 50f, -10f, 50f, 10f, 2f) { 9L }
        assertNotNull(pieces)
        // Both halves are single points → dropped.
        assertEquals(0, pieces!!.size)
    }

    @Test fun partialEraserCutsThickSegmentAtItsRenderedEdge() {
        val s = Stroke(1, Tool.PEN, 0, 20f, floatArrayOf(0f, 0f, 1f, 100f, 0f, 1f))
        var next = 2L
        assertTrue(InkGeometry.hits(s, 50f, 10.5f, 50f, 10.5f, 1f))
        val pieces = InkGeometry.eraseSplit(s, 50f, 10.5f, 50f, 10.5f, 1f) { next++ }
        assertNotNull(pieces)
        assertTrue(pieces!!.isEmpty())
    }

    @Test fun partialEraserAtTheEndLeavesOnePiece() {
        val s = line(1, 0f, 100f, 0f, step = 1f, width = 1f)
        val pieces = InkGeometry.eraseSplit(s, 100f, 0f, 100f, 0f, 3f) { 7L }!!
        assertEquals(1, pieces.size)
        assertTrue(pieces[0].count < s.count)
    }

    @Test fun pointInPolygon() {
        val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertTrue(InkGeometry.pointInPolygon(5f, 5f, square))
        assertFalse(InkGeometry.pointInPolygon(15f, 5f, square))
        // Concave "U": the notch is outside.
        val u = floatArrayOf(0f, 0f, 3f, 0f, 3f, 7f, 7f, 7f, 7f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertFalse(InkGeometry.pointInPolygon(5f, 3f, u))
        assertTrue(InkGeometry.pointInPolygon(5f, 8.5f, u))
        assertFalse(InkGeometry.pointInPolygon(1f, 1f, floatArrayOf(0f, 0f, 2f, 2f)))
    }

    @Test fun lassoSelectsMostlyInsideStrokes() {
        val inside = line(1, 10f, 40f, 20f)
        val half = line(2, 30f, 90f, 30f) // about a third inside
        val outside = line(3, 100f, 150f, 100f)
        val poly = floatArrayOf(0f, 0f, 50f, 0f, 50f, 50f, 0f, 50f)
        assertEquals(setOf(1L), InkGeometry.lassoSelect(listOf(inside, half, outside), poly))
        assertTrue(InkGeometry.inLasso(half, poly, fraction = 0.3f))
    }

    @Test fun translateMovesPointsKeepsIdentityFields() {
        val s = Stroke(5, Tool.HIGHLIGHTER, 0x11223344, 9f, floatArrayOf(1f, 2f, 0.5f, 3f, 4f, 0.7f), rec = "r", recMs = 1234)
        val t = InkGeometry.translate(s, 10f, -1f)
        assertArrayEquals(floatArrayOf(11f, 1f, 0.5f, 13f, 3f, 0.7f), t.pts, 1e-5f)
        assertEquals(5L, t.id); assertEquals(Tool.HIGHLIGHTER, t.tool); assertEquals("r", t.rec); assertEquals(1234L, t.recMs)
        // The original is untouched (strokes are immutable; undo snapshots share them).
        assertArrayEquals(floatArrayOf(1f, 2f, 0.5f, 3f, 4f, 0.7f), s.pts, 0f)
    }

    @Test fun boundsIncludeWidth() {
        val s = line(1, 0f, 10f, 5f, width = 4f)
        assertArrayEquals(floatArrayOf(-2f, 3f, 12f, 7f), s.bounds(), 1e-5f)
        val u = InkGeometry.unionBounds(listOf(s, line(2, 20f, 30f, 40f, width = 2f)))!!
        assertArrayEquals(floatArrayOf(-2f, 3f, 31f, 41f), u, 1e-5f)
        assertNull(InkGeometry.unionBounds(emptyList()))
    }
}
