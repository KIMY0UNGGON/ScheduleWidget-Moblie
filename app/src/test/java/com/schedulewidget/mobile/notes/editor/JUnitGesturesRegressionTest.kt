package com.schedulewidget.mobile.notes.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JUnitGesturesRegressionTest {
    @Test fun barrelHoldIsTemporaryAndDoubleReleaseSticks() {
        val button = StylusBarrelState()
        assertTrue(button.update(true, 100, EditorTool.PEN))
        assertTrue(button.isEraser(EditorTool.PEN))
        button.update(false, 140, EditorTool.PEN)
        assertFalse(button.isEraser(EditorTool.PEN))

        button.update(true, 180, EditorTool.PEN)
        button.update(false, 220, EditorTool.PEN)
        assertEquals(EditorTool.ERASER, button.effectiveTool(EditorTool.PEN))
        assertFalse(button.pressed)
        assertTrue(button.isEraser(EditorTool.PEN))
    }

    @Test fun secondDoubleClickTogglesBackToThePreferredTool() {
        val button = StylusBarrelState()
        repeat(2) { click ->
            val down = 100L + click * 80
            button.update(true, down, EditorTool.ERASER)
            button.update(false, down + 30, EditorTool.ERASER)
        }
        assertEquals(EditorTool.PEN, button.effectiveTool(EditorTool.ERASER))
        assertEquals(EditorTool.PEN, button.toolOverride(EditorTool.ERASER))
        assertFalse(button.isEraser(EditorTool.ERASER))
        assertTrue(button.isEraser(EditorTool.ERASER, eraserTip = true))
    }

    @Test fun longBarrelHoldRestoresThePreferredToolOnRelease() {
        val button = StylusBarrelState()
        button.update(true, 100, EditorTool.HIGHLIGHTER)
        assertTrue(button.isEraser(EditorTool.HIGHLIGHTER))
        assertEquals(EditorTool.HIGHLIGHTER, button.effectiveTool(EditorTool.HIGHLIGHTER))
        button.update(false, 800, EditorTool.HIGHLIGHTER)
        assertEquals(EditorTool.HIGHLIGHTER, button.effectiveTool(EditorTool.HIGHLIGHTER))
        assertFalse(button.isEraser(EditorTool.HIGHLIGHTER))
    }

    @Test fun manuallySelectingAnotherPenClearsStickyEraserEvenWhenToolKindStaysPen() {
        val button = StylusBarrelState()
        button.update(true, 100, EditorTool.PEN)
        button.update(false, 130, EditorTool.PEN)
        button.update(true, 180, EditorTool.PEN)
        button.update(false, 210, EditorTool.PEN)
        assertTrue(button.isEraser(EditorTool.PEN))

        assertEquals(EditorTool.PEN, button.effectiveTool(EditorTool.PEN, toolRevision = 1))
        assertFalse(button.isEraser(EditorTool.PEN, toolRevision = 1))
    }

    @Test fun presetSelectionBreaksAPendingDoubleClick() {
        val button = StylusBarrelState()
        button.update(true, 100, EditorTool.PEN)
        button.update(false, 130, EditorTool.PEN)
        button.update(true, 180, EditorTool.PEN, toolRevision = 1)
        button.update(false, 210, EditorTool.PEN, toolRevision = 1)
        assertEquals(EditorTool.PEN, button.effectiveTool(EditorTool.PEN, toolRevision = 1))
    }

    @Test fun legacySpenContactTransitionsDoNotBecomeDoubleClick() {
        val button = StylusBarrelState()
        button.update(true, 100, EditorTool.PEN, trackClicks = false)
        assertTrue(button.isEraser(EditorTool.PEN))
        button.update(false, 140, EditorTool.PEN, trackClicks = false)
        button.update(true, 180, EditorTool.PEN, trackClicks = false)
        button.update(false, 220, EditorTool.PEN, trackClicks = false)
        assertEquals(EditorTool.PEN, button.effectiveTool(EditorTool.PEN))
    }

    @Test fun shortEndPullBouncesAndLongPullAppendsOnlyOnce() {
        val pull = EndPagePullState(thresholdPx = 100f, maxOffsetPx = 80f)
        pull.begin(eligible = true)
        val short = pull.drag(fingerDeltaY = -40f, eligible = true, overflowPx = 40f)
        assertEquals(-40f, short.panDeltaY, 0f)
        assertTrue(short.offsetY < 0f)
        assertEquals(EndPagePullFinish(appendPage = false, pulled = true), pull.finish(eligible = true))

        pull.begin(eligible = true)
        pull.drag(fingerDeltaY = -120f, eligible = true, overflowPx = 120f)
        assertEquals(EndPagePullFinish(appendPage = true, pulled = true), pull.finish(eligible = true))
        assertEquals(EndPagePullFinish(appendPage = false, pulled = false), pull.finish(eligible = true))
    }

    @Test fun endPullDoesNotAppendAfterLeavingTheBoundaryOrAddingAPointer() {
        val pull = EndPagePullState(thresholdPx = 100f, maxOffsetPx = 80f)
        pull.begin(eligible = true)
        val resumedPan = pull.drag(fingerDeltaY = 30f, eligible = true)
        assertEquals(30f, resumedPan.panDeltaY, 0f)
        assertEquals(EndPagePullFinish(appendPage = false, pulled = false), pull.finish(eligible = true))

        pull.begin(eligible = true)
        pull.drag(fingerDeltaY = -120f, eligible = false)
        assertEquals(EndPagePullFinish(appendPage = false, pulled = false), pull.finish(eligible = true))
    }

    @Test fun aContinuousScrollCanArmOnlyAfterItReachesTheLastBoundary() {
        val pull = EndPagePullState(thresholdPx = 100f, maxOffsetPx = 80f)
        pull.begin(eligible = true)
        val scroll = pull.drag(fingerDeltaY = -300f, eligible = true)
        assertEquals(-300f, scroll.panDeltaY, 0f)
        assertEquals(0f, scroll.offsetY, 0f)
        val crossed = pull.drag(fingerDeltaY = -40f, eligible = true, overflowPx = 20f)
        assertEquals(-40f, crossed.panDeltaY, 0f)
        assertTrue(crossed.offsetY < 0f)
        pull.drag(fingerDeltaY = -90f, eligible = true)
        assertEquals(EndPagePullFinish(appendPage = true, pulled = true), pull.finish(eligible = true))
    }
}
