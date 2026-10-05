package com.schedulewidget.mobile.notes.editor

import kotlin.math.min

/** Temporary barrel hold and quick double-press sticky override. */
internal class StylusBarrelState {
    var pressed = false
        private set
    private var pressedAt = 0L
    private var firstClickAt: Long? = null
    private var firstClickTool: EditorTool? = null
    private var firstClickRevision: Int? = null
    private var secondClick = false
    private var stickyBase: EditorTool? = null
    private var stickyBaseRevision: Int? = null
    private var stickyTool: EditorTool? = null

    fun update(
        pressed: Boolean,
        time: Long,
        preferredTool: EditorTool,
        toolRevision: Int = 0,
        trackClicks: Boolean = true,
    ): Boolean {
        if (this.pressed == pressed) return false
        this.pressed = pressed
        if (!trackClicks) {
            clearFirstClick()
            secondClick = false
            return true
        }
        if (pressed) {
            pressedAt = time
            if (firstClickTool != preferredTool || firstClickRevision != toolRevision) clearFirstClick()
            secondClick = firstClickAt?.let { time - it in 0L..DOUBLE_CLICK_MS } == true
        } else {
            val quick = time - pressedAt in 0L..QUICK_PRESS_MS
            if (secondClick && quick) {
                val active = effectiveTool(preferredTool, toolRevision)
                val pen = preferredTool.takeUnless { it == EditorTool.ERASER || it == EditorTool.LASSO || it == EditorTool.TEXT }
                    ?: EditorTool.PEN
                stickyTool = if (active == EditorTool.ERASER) pen else EditorTool.ERASER
                stickyBase = preferredTool
                stickyBaseRevision = toolRevision
                clearFirstClick()
            } else {
                firstClickAt = time.takeIf { !secondClick && quick }
                firstClickTool = preferredTool.takeIf { firstClickAt != null }
                firstClickRevision = toolRevision.takeIf { firstClickAt != null }
            }
            secondClick = false
        }
        return true
    }

    fun effectiveTool(preferredTool: EditorTool, toolRevision: Int = 0): EditorTool {
        if (stickyBase != null && (stickyBase != preferredTool || stickyBaseRevision != toolRevision)) {
            stickyBase = null
            stickyBaseRevision = null
            stickyTool = null
        }
        return stickyTool ?: preferredTool
    }

    fun isEraser(preferredTool: EditorTool, eraserTip: Boolean = false, toolRevision: Int = 0): Boolean =
        pressed || eraserTip || effectiveTool(preferredTool, toolRevision) == EditorTool.ERASER

    fun toolOverride(preferredTool: EditorTool, toolRevision: Int = 0): EditorTool? =
        effectiveTool(preferredTool, toolRevision).takeIf { it != preferredTool }

    private fun clearFirstClick() {
        firstClickAt = null
        firstClickTool = null
        firstClickRevision = null
    }

    private companion object {
        const val DOUBLE_CLICK_MS = 350L
        const val QUICK_PRESS_MS = 350L
    }
}

internal data class EndPagePullDrag(val panDeltaY: Float, val offsetY: Float)
internal data class EndPagePullFinish(val appendPage: Boolean, val pulled: Boolean)

/** Accumulates only a one-finger pull past the last-page scroll boundary. */
internal class EndPagePullState(private val thresholdPx: Float, private val maxOffsetPx: Float) {
    private var eligibleGesture = false
    private var distancePx = 0f

    fun begin(eligible: Boolean) {
        eligibleGesture = eligible
        distancePx = 0f
    }

    fun drag(fingerDeltaY: Float, eligible: Boolean, overflowPx: Float = 0f): EndPagePullDrag {
        if (!eligible || !eligibleGesture) {
            cancel()
            return EndPagePullDrag(fingerDeltaY, 0f)
        }

        var panDelta = fingerDeltaY
        if (distancePx > 0f && fingerDeltaY < 0f) {
            distancePx -= fingerDeltaY
            panDelta = 0f
        } else if (distancePx > 0f) {
            val consumed = min(fingerDeltaY, distancePx)
            distancePx -= consumed
            panDelta -= consumed
            if (distancePx <= 0f && panDelta > 0f) eligibleGesture = false
        } else if (overflowPx > 0f) {
            distancePx = overflowPx
        } else if (fingerDeltaY > 0f) {
            eligibleGesture = false
        }
        return EndPagePullDrag(panDelta, -min(distancePx * RESISTANCE, maxOffsetPx))
    }

    fun finish(eligible: Boolean): EndPagePullFinish {
        val pulled = distancePx > 0f
        val append = eligibleGesture && eligible && distancePx >= thresholdPx
        cancel()
        return EndPagePullFinish(append, pulled)
    }

    fun cancel() {
        eligibleGesture = false
        distancePx = 0f
    }

    private companion object {
        const val RESISTANCE = 0.35f
    }
}
