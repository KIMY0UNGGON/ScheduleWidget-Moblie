package com.schedulewidget.mobile.notes.editor

import android.os.Build
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import kotlin.math.hypot
import kotlin.math.max

/**
 * Raw touch handling of the page area. The S Pen (stylus / eraser tool types) writes; fingers scroll and pinch-zoom
 * unless "finger draws" is on. Palm rejection: finger touches are ignored while a stylus is down, hovering, or was
 * lifted a moment ago, and a stylus landing during a finger gesture takes over.
 * The S Pen side button held (BUTTON_STYLUS_PRIMARY, or Samsung's own 211–213 actions) erases.
 */
class InkInput(private val st: EditorState, private val decay: DecayAnimationSpec<Offset>) {
    private enum class Mode { NONE, INK, NAV, IGNORE }
    private var mode = Mode.NONE
    private var inkPointer = -1
    private var inkIsStylus = false
    private val barrelButton = StylusBarrelState()
    private var motionBarrelButtons = 0
    private var keyBarrelButtons = 0
    private val endPagePull = EndPagePullState(96f * st.density, 88f * st.density)
    private var activeInkEraser = false
    private var activeInkToolOverride: EditorTool? = null
    private var activeInkToolType = MotionEvent.TOOL_TYPE_UNKNOWN
    private var activeInkX = 0f
    private var activeInkY = 0f
    private var activeInkPressure = 1f

    /** Last time a stylus was seen (down, moving or hovering). */
    private var lastStylusAt = 0L
    /** The stylus is hovering over the screen (reported by the hover listener). */
    var stylusHovering = false
        private set

    // Navigation (finger) gesture.
    private var velocity: VelocityTracker? = null
    private var lastCx = 0f
    private var lastCy = 0f
    private var lastSpan = 0f
    private var navDownAt = 0L
    private var navStartX = 0f
    private var navStartY = 0f
    private var navMoved = false
    private var navMulti = false

    /** Stylus hover reported by Compose (enter / move = [active]). */
    fun hover(active: Boolean, time: Long) {
        stylusHovering = active
        lastStylusAt = time
    }

    fun onHover(ev: MotionEvent) {
        if (!isPen(ev.getToolType(0))) return
        updateBarrelFromMotion(ev, allowActiveSwitch = true)
        updateHover(ev)
    }

    private fun updateHover(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> { stylusHovering = true; lastStylusAt = ev.eventTime }
            MotionEvent.ACTION_HOVER_EXIT -> { stylusHovering = false; lastStylusAt = ev.eventTime }
        }
    }

    fun onTouch(ev: MotionEvent): Boolean {
        var action = ev.actionMasked
        var syntheticButton: Boolean? = null
        // Samsung S Pen with the side button held (older One UI): 211 down, 212 up, 213 move.
        when (action) {
            SPEN_DOWN -> { action = MotionEvent.ACTION_DOWN; syntheticButton = true }
            SPEN_UP -> { action = MotionEvent.ACTION_UP; syntheticButton = false }
            SPEN_MOVE -> { action = MotionEvent.ACTION_MOVE; syntheticButton = true }
        }
        val endsInk = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ||
            (action == MotionEvent.ACTION_POINTER_UP && ev.getPointerId(ev.actionIndex) == inkPointer)
        updateBarrelFromMotion(ev, syntheticButton, allowActiveSwitch = !endsInk)
        when (action) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT -> { updateHover(ev); return true }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> return true
            MotionEvent.ACTION_DOWN -> down(ev)
            MotionEvent.ACTION_POINTER_DOWN -> pointerDown(ev)
            MotionEvent.ACTION_MOVE -> move(ev)
            MotionEvent.ACTION_POINTER_UP -> pointerUp(ev)
            MotionEvent.ACTION_UP -> up(ev)
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    /** Key fallback is scoped to the page area and only handles Android's stylus barrel key codes (API 34+). */
    fun onKeyEvent(event: ComposeKeyEvent): Boolean {
        val pressed = when (event.type) {
            KeyEventType.KeyDown -> true
            KeyEventType.KeyUp -> false
            else -> return false
        }
        return onStylusKeyEvent(event.nativeKeyEvent, pressed)
    }

    /** MainActivity forwards stylus button keys when the editor page is not the focused Compose target. */
    fun onNativeKeyEvent(event: AndroidKeyEvent): Boolean {
        val pressed = when (event.action) {
            AndroidKeyEvent.ACTION_DOWN -> true
            AndroidKeyEvent.ACTION_UP -> false
            else -> return false
        }
        return onStylusKeyEvent(event, pressed)
    }

    /** Clears a key-only hold if focus leaves the editor before its native KEY_UP arrives. */
    fun clearKeyHold() {
        if (keyBarrelButtons == 0) return
        keyBarrelButtons = 0
        updateBarrel(
            pressed = motionBarrelButtons != 0,
            time = android.os.SystemClock.uptimeMillis(),
            ev = null,
            allowActiveSwitch = true,
            trackClicks = false,
        )
    }

    private fun onStylusKeyEvent(native: AndroidKeyEvent, pressed: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        if (native.keyCode != AndroidKeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY &&
            native.keyCode != AndroidKeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY
        ) return false
        val button = if (native.keyCode == AndroidKeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY)
            MotionEvent.BUTTON_STYLUS_PRIMARY else MotionEvent.BUTTON_STYLUS_SECONDARY
        keyBarrelButtons = if (pressed) keyBarrelButtons or button else keyBarrelButtons and button.inv()
        updateBarrel((motionBarrelButtons or keyBarrelButtons) != 0, native.eventTime, null, allowActiveSwitch = true)
        return true
    }

    private fun isPen(toolType: Int) =
        toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER || toolType == MotionEvent.TOOL_TYPE_MOUSE

    private fun updateBarrelFromMotion(
        ev: MotionEvent,
        syntheticPressed: Boolean? = null,
        allowActiveSwitch: Boolean,
    ) {
        if (syntheticPressed == null && (0 until ev.pointerCount).none { isPen(ev.getToolType(it)) }) return
        val buttons = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY
        var state = syntheticPressed?.let { if (it) MotionEvent.BUTTON_STYLUS_PRIMARY else 0 } ?: (ev.buttonState and buttons)
        if (syntheticPressed == null && ev.actionMasked in BUTTON_ACTIONS) {
            val changed = ev.actionButton and buttons
            if (changed != 0) {
                state = if (ev.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) state or changed else state and changed.inv()
            }
        }
        motionBarrelButtons = state
        updateBarrel(
            (motionBarrelButtons or keyBarrelButtons) != 0,
            ev.eventTime,
            ev,
            allowActiveSwitch,
            trackClicks = syntheticPressed == null,
        )
    }

    private fun updateBarrel(
        pressed: Boolean,
        time: Long,
        ev: MotionEvent?,
        allowActiveSwitch: Boolean,
        trackClicks: Boolean = true,
    ) {
        barrelButton.update(pressed, time, st.tool, st.penSelectionRevision, trackClicks)
        refreshDisplayTool(eraserTip = mode == Mode.INK && activeInkToolType == MotionEvent.TOOL_TYPE_ERASER)
        if (!allowActiveSwitch || mode != Mode.INK || !inkIsStylus) return
        if (ev != null) {
            val idx = ev.findPointerIndex(inkPointer)
            if (idx >= 0) {
                activeInkX = ev.getX(idx)
                activeInkY = ev.getY(idx)
                activeInkPressure = pressureOf(ev, idx)
                activeInkToolType = ev.getToolType(idx)
            }
        }
        val eraserTip = activeInkToolType == MotionEvent.TOOL_TYPE_ERASER
        val nextEraser = barrelButton.isEraser(st.tool, eraserTip, st.penSelectionRevision)
        val nextOverride = barrelButton.toolOverride(st.tool, st.penSelectionRevision).takeUnless { nextEraser }
        if (nextEraser == activeInkEraser && nextOverride == activeInkToolOverride) return
        st.switchInkMode(activeInkX, activeInkY, activeInkPressure, nextEraser, nextOverride)
        activeInkEraser = nextEraser
        activeInkToolOverride = nextOverride
    }

    // A hover that stopped reporting (pen taken away without an exit event) stops counting after a while.
    private fun palmGuard(now: Long) =
        barrelButton.pressed || (stylusHovering && now - lastStylusAt < HOVER_STALE_MS) || inkIsStylusDown() ||
            now - lastStylusAt < PALM_GUARD_MS

    private fun inkIsStylusDown() = mode == Mode.INK && inkIsStylus

    private fun pressureOf(ev: MotionEvent, idx: Int, h: Int = -1): Float {
        if (ev.getToolType(idx) == MotionEvent.TOOL_TYPE_FINGER) return 1f
        return if (h >= 0) ev.getHistoricalPressure(idx, h) else ev.getPressure(idx)
    }

    private fun down(ev: MotionEvent) {
        st.stopFling()
        val tool = ev.getToolType(0)
        if (isPen(tool)) {
            startInk(ev, 0, eraser = tool == MotionEvent.TOOL_TYPE_ERASER, stylus = true)
            return
        }
        if (palmGuard(ev.eventTime)) { mode = Mode.IGNORE; return }
        if (st.fingerDraws && !st.linkMode) {
            startInk(ev, 0, eraser = false, stylus = false)
            return
        }
        startNav(ev)
    }

    private fun startInk(ev: MotionEvent, idx: Int, eraser: Boolean, stylus: Boolean) {
        endPagePull.cancel()
        st.endPullJob?.cancel(); st.endPullJob = null
        st.endPullOffsetPx = 0f
        mode = Mode.INK
        inkPointer = ev.getPointerId(idx)
        inkIsStylus = stylus
        activeInkToolType = ev.getToolType(idx)
        activeInkX = ev.getX(idx); activeInkY = ev.getY(idx); activeInkPressure = pressureOf(ev, idx)
        activeInkEraser = eraser || (stylus && barrelButton.isEraser(
            st.tool, eraserTip = activeInkToolType == MotionEvent.TOOL_TYPE_ERASER,
            toolRevision = st.penSelectionRevision,
        ))
        activeInkToolOverride = if (stylus) barrelButton.toolOverride(st.tool, st.penSelectionRevision).takeUnless { activeInkEraser } else null
        if (stylus) lastStylusAt = ev.eventTime
        refreshDisplayTool(eraserTip = activeInkToolType == MotionEvent.TOOL_TYPE_ERASER)
        st.beginInkWithTool(activeInkX, activeInkY, activeInkPressure, activeInkEraser, activeInkToolOverride)
    }

    private fun refreshDisplayTool(eraserTip: Boolean = false) {
        val selected = when {
            st.linkMode -> st.tool
            eraserTip || barrelButton.pressed -> EditorTool.ERASER
            else -> barrelButton.effectiveTool(st.tool, st.penSelectionRevision)
        }
        st.inputToolOverride = selected.takeIf { it != st.tool }
    }

    private fun pointerDown(ev: MotionEvent) {
        val idx = ev.actionIndex
        val tool = ev.getToolType(idx)
        when (mode) {
            Mode.INK -> {
                if (inkIsStylus) return // palm or second finger while writing: ignored
                if (isPen(tool)) {
                    // A stylus takes over a finger gesture, including finger-draw mode.
                    // Keep any already-applied finger erase changes committed before starting pen input.
                    st.endInk()
                    velocity?.recycle(); velocity = null
                    endPagePull.cancel()
                    startInk(ev, idx, eraser = tool == MotionEvent.TOOL_TYPE_ERASER, stylus = true)
                } else if (st.inkGestureIsYoung) {
                    // Finger writing that turns out to be a two-finger zoom / scroll.
                    st.cancelInk()
                    startNav(ev)
                }
            }
            Mode.NAV, Mode.IGNORE, Mode.NONE -> {
                if (isPen(tool)) {
                    // The pen landed while the palm rests on the screen: the pen wins.
                    velocity?.recycle(); velocity = null
                    endPagePull.cancel()
                    startInk(ev, idx, eraser = tool == MotionEvent.TOOL_TYPE_ERASER, stylus = true)
                } else if (mode == Mode.NAV) {
                    velocity?.addMovement(ev)
                    navMulti = true
                    endPagePull.cancel()
                    st.animateEndPullBack()
                    rebaseline(ev, -1)
                }
            }
        }
    }

    private fun move(ev: MotionEvent) {
        when (mode) {
            Mode.INK -> {
                val idx = ev.findPointerIndex(inkPointer)
                if (idx < 0) return
                if (inkIsStylus) lastStylusAt = ev.eventTime
                for (h in 0 until ev.historySize) {
                    st.moveInk(ev.getHistoricalX(idx, h), ev.getHistoricalY(idx, h), pressureOf(ev, idx, h))
                }
                st.moveInk(ev.getX(idx), ev.getY(idx), pressureOf(ev, idx))
            }
            Mode.NAV -> {
                velocity?.addMovement(ev)
                val (cx, cy, span) = centroid(ev, -1)
                val dx = cx - lastCx; val dy = cy - lastCy
                if (!navMoved && hypot(cx - navStartX, cy - navStartY) > 8f * st.density) navMoved = true
                if (navMoved || navMulti) {
                    val pullEligible = canPullGesture(ev)
                    val pull = endPagePull.drag(dy, pullEligible && st.endPullJob == null, endBoundaryOverflowPx(dy))
                    if (pullEligible && st.endPullJob == null) st.endPullOffsetPx = pull.offsetY
                    else if (st.endPullJob == null) st.animateEndPullBack()
                    st.panBy(dx, pull.panDeltaY)
                    if (ev.pointerCount >= 2 && lastSpan > 0f && span > 0f) st.zoomBy(span / lastSpan, cx, cy)
                } else {
                    st.endPullOffsetPx = 0f
                }
                lastCx = cx; lastCy = cy; lastSpan = span
            }
            else -> {}
        }
    }

    private fun pointerUp(ev: MotionEvent) {
        val idx = ev.actionIndex
        when (mode) {
            Mode.INK -> if (ev.getPointerId(idx) == inkPointer) {
                st.endInk()
                if (inkIsStylus) lastStylusAt = ev.eventTime
                activeInkToolType = MotionEvent.TOOL_TYPE_UNKNOWN
                refreshDisplayTool()
                mode = Mode.IGNORE // the other pointers (palm) are ignored until all are up
            }
            Mode.NAV -> {
                velocity?.addMovement(ev)
                rebaseline(ev, idx)
            }
            else -> {}
        }
    }

    private fun up(ev: MotionEvent) {
        when (mode) {
            Mode.INK -> {
                if (ev.getPointerId(0) == inkPointer) st.endInk() else st.cancelInk()
                if (inkIsStylus) lastStylusAt = ev.eventTime
                activeInkToolType = MotionEvent.TOOL_TYPE_UNKNOWN
                refreshDisplayTool()
            }
            Mode.NAV -> {
                val pull = endPagePull.finish(canPullGesture(ev) && st.canPullAddPage)
                st.animateEndPullBack()
                if (pull.appendPage && st.canPullAddPage) st.addPageAtEnd()
                velocity?.addMovement(ev)
                val duration = ev.eventTime - navDownAt
                if (!navMoved && !navMulti && duration < TAP_MS) {
                    st.onFingerTap(ev.x, ev.y)
                } else {
                    velocity?.takeUnless { pull.pulled }?.let { vt ->
                        vt.computeCurrentVelocity(1000)
                        val vx = vt.getXVelocity(ev.getPointerId(0)); val vy = vt.getYVelocity(ev.getPointerId(0))
                        if (hypot(vx, vy) > 300f * st.density / 2f) st.fling(vx, vy, decay)
                    }
                }
                velocity?.recycle(); velocity = null
            }
            else -> {}
        }
        mode = Mode.NONE
        inkPointer = -1
    }

    private fun cancel() {
        if (mode == Mode.INK) st.cancelInk()
        activeInkToolType = MotionEvent.TOOL_TYPE_UNKNOWN
        refreshDisplayTool()
        velocity?.recycle(); velocity = null
        endPagePull.cancel()
        st.animateEndPullBack()
        mode = Mode.NONE
        inkPointer = -1
    }

    private fun startNav(ev: MotionEvent) {
        mode = Mode.NAV
        navDownAt = ev.downTime
        navMoved = false
        navMulti = ev.pointerCount > 1
        endPagePull.begin(canPullGesture(ev))
        st.endPullJob?.cancel(); st.endPullJob = null
        st.endPullOffsetPx = 0f
        velocity?.recycle()
        velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
        val (cx, cy, span) = centroid(ev, -1)
        navStartX = cx; navStartY = cy
        lastCx = cx; lastCy = cy; lastSpan = span
    }

    private fun canPullGesture(ev: MotionEvent): Boolean =
        ev.pointerCount == 1 && !navMulti && ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER &&
            !barrelButton.pressed && st.tool != EditorTool.LASSO && st.tool != EditorTool.TEXT &&
            !st.linkMode

    private fun endBoundaryOverflowPx(fingerDeltaY: Float): Float {
        if (fingerDeltaY >= 0f || st.note.pages.isEmpty()) return 0f
        val l = st.layout()
        val visibleHeight = st.viewH / st.scale
        if (l.totalH <= visibleHeight) return -fingerDeltaY
        val maxOffset = l.totalH - visibleHeight
        return max(0f, st.oy - fingerDeltaY / st.scale - maxOffset) * st.scale
    }

    /** Pointer count changed: restart the pan/zoom deltas from the remaining pointers (skipping [skipIdx]). */
    private fun rebaseline(ev: MotionEvent, skipIdx: Int) {
        val (cx, cy, span) = centroid(ev, skipIdx)
        lastCx = cx; lastCy = cy; lastSpan = span
    }

    private data class Centroid(val x: Float, val y: Float, val span: Float)

    /** Centroid and average distance from it of the finger pointers (pens and [skipIdx] left out). */
    private fun centroid(ev: MotionEvent, skipIdx: Int): Centroid {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until ev.pointerCount) {
            if (i == skipIdx || isPen(ev.getToolType(i))) continue
            sx += ev.getX(i); sy += ev.getY(i); n++
        }
        if (n == 0) return Centroid(lastCx, lastCy, 0f)
        val cx = sx / n; val cy = sy / n
        var d = 0f
        for (i in 0 until ev.pointerCount) {
            if (i == skipIdx || isPen(ev.getToolType(i))) continue
            d += hypot(ev.getX(i) - cx, ev.getY(i) - cy)
        }
        return Centroid(cx, cy, if (n >= 2) d / n else 0f)
    }

    companion object {
        private const val SPEN_DOWN = 211
        private const val SPEN_UP = 212
        private const val SPEN_MOVE = 213
        private const val PALM_GUARD_MS = 500L
        private const val HOVER_STALE_MS = 2000L
        private const val TAP_MS = 350L
        private val BUTTON_ACTIONS = setOf(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE)
    }
}
