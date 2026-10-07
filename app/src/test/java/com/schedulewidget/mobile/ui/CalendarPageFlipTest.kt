package com.schedulewidget.mobile.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarPageFlipTest {
    @Test fun desktopEffectsKeepThreeBackExposureLimits() {
        assertEquals(300f, calendarBackAboveLimitPx(1, 300), 0f)
        assertEquals(300f, calendarBackAboveLimitPx(2, 300), 0f)
        assertEquals(100f, calendarBackAboveLimitPx(3, 300), 0f)
        assertEquals(100f, calendarBackAboveLimitPx(4, 300), 0f)
        assertEquals(0f, calendarBackAboveLimitPx(5, 300), 0f)
        assertEquals(0f, calendarBackAboveLimitPx(6, 300), 0f)
    }

    @Test fun foldStartsOutsideCornerEndsAtTopAndReflectsAcrossMovingCrease() {
        for ((width, height) in listOf(200f to 320f, 700f to 120f)) {
            val corner = Offset(width, height)
            val first = calendarFold(width, height, 0f)
            assertTrue(first.normal.x * corner.x + first.normal.y * corner.y < first.distance)
            val last = calendarFold(width, height, 1f)
            assertEquals(0f, last.distance, 0.001f)
            assertEquals(0f, last.normal.x, 0.001f)
            assertEquals(1f, last.normal.y, 0.001f)
            for (phase in listOf(0.01f, 0.25f, 0.5f, 0.9f, 1f)) {
                val fold = calendarFold(width, height, phase)
                assertEquals(1f, fold.normal.getDistance(), 0.001f)
                val b = Offset(width, height * (1f - phase))
                val mirroredB = fold.reflection().map(b)
                assertEquals(b.x, mirroredB.x, 0.001f)
                assertEquals(b.y, mirroredB.y, 0.001f)
                val mirrored = fold.reflection().map(corner)
                val past = fold.normal.x * corner.x + fold.normal.y * corner.y - fold.distance
                val reflectedPast = fold.normal.x * mirrored.x + fold.normal.y * mirrored.y - fold.distance
                assertEquals(-past, reflectedPast, 0.001f)
                val restored = fold.reflection().map(mirrored)
                assertEquals(corner.x, restored.x, 0.002f)
                assertEquals(corner.y, restored.y, 0.002f)
            }
        }
        assertEquals(0f, calendarFlipPhase(0f, true), 0f)
        assertEquals(1f, calendarFlipPhase(1f, true), 0f)
        assertEquals(1f, calendarFlipPhase(0f, false), 0f)
        assertEquals(0f, calendarFlipPhase(1f, false), 0f)
        assertEquals(1f, calendarFlipPhase(0.35f, true) + calendarFlipPhase(0.35f, false), 0.0001f)
    }
}
