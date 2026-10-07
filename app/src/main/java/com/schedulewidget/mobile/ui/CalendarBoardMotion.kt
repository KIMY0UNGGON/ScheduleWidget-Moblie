package com.schedulewidget.mobile.ui

import android.animation.ValueAnimator
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableFloatStateOf
import com.schedulewidget.mobile.data.AppData
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.schedulewidget.mobile.data.ScheduleItem
import java.time.LocalDate

/**
 * Drag & drop of schedule blocks between days on the board: a long press picks a block up, the day cell under the finger
 * (bounds registered by [rememberDropTarget]) is the target, and dropping moves the schedule there (time kept).
 */
internal class BoardDrag {
    var item by mutableStateOf<ScheduleItem?>(null)
        private set
    var color by mutableStateOf(Color.Gray)
        private set
    /** Finger position in root coordinates. */
    var pointer by mutableStateOf(Offset.Zero)
        private set
    /** Where the finger grabbed the block, inside it. */
    var grab = Offset.Zero
        private set
    var chipSize = IntSize.Zero
        private set
    /** Day cells on screen, in root coordinates (clipped to what is visible). */
    val dayBounds = HashMap<LocalDate, Rect>()

    val target: LocalDate? get() = if (item == null) null else dayBounds.entries.firstOrNull { it.value.contains(pointer) }?.key

    fun start(item: ScheduleItem, color: Color, pointer: Offset, grab: Offset, size: IntSize) {
        this.color = color
        this.grab = grab
        this.chipSize = size
        this.pointer = pointer
        this.item = item
    }

    fun move(delta: Offset) { pointer += delta }

    /** The schedule and its new day, or null when it was dropped outside the days or on its own day. */
    fun end(): Pair<ScheduleItem, LocalDate>? {
        val i = item
        val to = target
        item = null
        return if (i != null && to != null && to != i.date) i to to else null
    }

    fun cancel() { item = null }
}

internal val LocalBoardDrag = staticCompositionLocalOf<BoardDrag?> { null }

private data class BoardPageState(
    val start: LocalDate, val view: String, val theme: String, val data: AppData, val effect: Int, val hidden: Boolean,
)

/** Animates the live Compose page layer only; data, style, view, and hidden changes snap into place. */
@Composable
internal fun rememberBoardPageMotion(view: String, start: LocalDate, theme: MiniTheme, data: AppData, hidden: Boolean): Modifier {
    val context = LocalContext.current
    val effect = data.miniFlipEffect.coerceIn(0, 6)
    val progress = remember { Animatable(1f) }
    val density = LocalDensity.current.density
    var direction by remember { mutableFloatStateOf(1f) }
    var previous by remember { mutableStateOf<BoardPageState?>(null) }

    LaunchedEffect(start, view, theme.id, data, effect, hidden) {
        val old = previous
        val current = BoardPageState(start, view, theme.id, data, effect, hidden)
        val pageChanged = old != null && old.start != start && old.view == view && old.theme == theme.id &&
            old.data == data && old.effect == effect && old.hidden == hidden && !hidden
        previous = current
        if (pageChanged && effect != 0 && calendarAnimationsEnabled(context)) {
            direction = if (start > old!!.start) 1f else -1f
            progress.snapTo(0f)
            progress.animateTo(1f, tween(260))
        } else progress.snapTo(1f)
    }
    return Modifier.graphicsLayer {
        val p = progress.value
        translationX = 0f; translationY = 0f
        rotationX = 0f; rotationY = 0f; rotationZ = 0f
        scaleX = 1f; scaleY = 1f; alpha = 1f
        when (effect) {
            1 -> { translationY = (1f - p) * 22f * density * direction; alpha = p }
            2 -> { rotationY = (1f - p) * -24f * direction; translationX = (1f - p) * 14f * density * direction; cameraDistance = 24f * density }
            3 -> { rotationX = (1f - p) * 18f * direction; alpha = 0.65f + 0.35f * p; cameraDistance = 24f * density }
            4 -> { rotationY = (1f - p) * 38f * direction; alpha = 0.55f + 0.45f * p; cameraDistance = 24f * density }
            5 -> { translationY = (1f - p) * -12f * density * direction; scaleX = 0.98f + 0.02f * p; scaleY = 0.98f + 0.02f * p; alpha = p }
            6 -> { rotationZ = (1f - p) * 5f * direction; translationX = (1f - p) * -12f * density * direction; alpha = p }
        }
    }
}

internal fun calendarAnimationsEnabled(context: android.content.Context): Boolean =
    ValueAnimator.areAnimatorsEnabled() &&
        Settings.Global.getFloat(context.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f) > 0f &&
        Settings.Global.getFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) > 0f

/** Registers a day cell as a drop target; returns the modifier and whether a dragged block is over it now. */
@Composable
internal fun rememberDropTarget(date: LocalDate): Pair<Modifier, Boolean> {
    val drag = LocalBoardDrag.current ?: return Modifier to false
    DisposableEffect(drag, date) { onDispose { drag.dayBounds.remove(date) } }
    // Only recomposes the cell when the finger enters or leaves it, not on every move.
    val over by remember(drag, date) { derivedStateOf { drag.target == date } }
    return Modifier.onGloballyPositioned { drag.dayBounds[date] = it.boundsInRoot() } to over
}

private class CoordsRef { var value: LayoutCoordinates? = null }

/** Long press + drag on a schedule block (see [BoardDrag]); a plain Modifier off the board. */
@Suppress("ModifierFactoryExtensionFunction") // needs composition locals and remembered state, like dayCellModifier
@Composable
internal fun rememberDragSource(item: ScheduleItem, color: Color): Modifier {
    val drag = LocalBoardDrag.current ?: return Modifier
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(item)
    val currentColor by rememberUpdatedState(color)
    val coords = remember { CoordsRef() }
    return Modifier.onGloballyPositioned { coords.value = it }.pointerInput(item.id) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                val c = coords.value
                if (c != null && c.isAttached) {
                    drag.start(current, currentColor, c.localToRoot(offset), offset, c.size)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            },
            onDrag = { change, amount -> change.consume(); drag.move(amount) },
            onDragEnd = { drag.end()?.let { (s, day) -> ScheduleActions.move(context, s, day) } },
            onDragCancel = { drag.cancel() },
        )
    }
}
