package com.schedulewidget.mobile.pet

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.record.PetRecordingBadge
import com.schedulewidget.mobile.record.Recorder
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * The in-app pet, free to be dragged anywhere over [modifier]'s area (usually the whole mini screen) until its painted
 * pixels meet an edge (its transparent margin may go past). Only the painted silhouette takes touches.
 * Its sprite box's center is saved as a fraction of that area so it stays put across rotations and restarts.
 * Until the user moves it, it sits centered at [defaultTop].
 */
@Composable
fun DraggablePet(
    modifier: Modifier = Modifier,
    slotIndex: Int = 0,
    heightDp: Int = 96,
    defaultTop: Dp = 0.dp,
    onTap: (() -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null,
    onTripleTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    /** Shown next to the pet (above it when there is room), e.g. the MP3 tool. */
    panel: (@Composable () -> Unit)? = null,
    onPanelDismiss: () -> Unit = {},
    /** 1 = crisp; lower draws the pet see-through (used when it will not stay on screen after the app closes). */
    alpha: Float = 1f,
) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val data by repo.data.collectAsState()
    val slots = data.petSlots()
    val pet = slots.getOrNull(slotIndex) ?: return
    val visible = slots.filterNot { it.hidden }
    val defaultX = (visible.indexOfFirst { it.index == slotIndex } + 1f) / (visible.size + 1f)
    val height = (heightDp * pet.scale.coerceIn(50, 300) / 100f).dp
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxSize()) {
        val area = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val heightPx = with(density) { height.toPx() }
        var petSize by remember { mutableStateOf(IntSize.Zero) }
        val centerState = remember(area, pet.x, pet.y, heightPx, defaultX) {
            mutableStateOf(
                Offset(
                    (pet.x ?: defaultX) * area.width,
                    pet.y?.times(area.height) ?: (with(density) { defaultTop.toPx() } + heightPx / 2),
                )
            )
        }
        var center by centerState
        // The drawn frame's painted bounds, kept by the sprite; a new holder per look so another one's never apply.
        val painted = remember(pet.manifest, pet.flipped) { PaintedBounds() }
        fun clamp(p: Offset) = clampToWalls(p, area, petSize.toSize(), painted.fraction)
        // Keep the saved anchor stable; the offset fits the current frame to the walls without accumulating frame changes.

        Box(
            Modifier
                .offset {
                    val visible = clamp(center)
                    IntOffset((visible.x - petSize.width / 2f).roundToInt(), (visible.y - petSize.height / 2f).roundToInt())
                }
                .onSizeChanged { petSize = it },
        ) {
            CharacterSprite(
                pet.manifest, pet.animation, height,
                modifier = Modifier.graphicsLayer { this.alpha = alpha },
                flipped = pet.flipped,
                onTap = onTap, onDoubleTap = onDoubleTap, onTripleTap = onTripleTap, onLongPress = onLongPress,
                painted = painted,
                // On the sprite's silhouette, like its taps. Keyed on the state object too: after a drag is saved, petX/petY
                // change and a new state replaces the old one; a detector still holding the old one moved nothing on the
                // next drag (then jumped on release).
                drag = Modifier.pointerInput(area, centerState, painted) {
                    fun save() {
                        val c = center
                        if (area.width > 0f && area.height > 0f) repo.update { current ->
                            current.petSlots().getOrNull(slotIndex)?.let { active ->
                                current.withPet(active.copy(x = c.x / area.width, y = c.y / area.height))
                            } ?: current
                        }
                    }
                    detectDragGestures(onDragStart = { center = clamp(center) }, onDragEnd = ::save, onDragCancel = ::save) { change, drag ->
                        change.consume()
                        center = clamp(center + drag)
                    }
                },
            )
            // A small red dot near the pet while a lecture is being recorded.
            val rec by Recorder.state.collectAsState()
            if (data.stt.enabled && rec.isRecording) PetRecordingBadge(
                Modifier.align(Alignment.TopEnd)
                    // The box's see-through corner may now hang off the screen: keep the dot on it.
                    .offset {
                        val visible = clamp(center)
                        IntOffset(
                            -(visible.x + petSize.width / 2f - area.width).coerceAtLeast(0f).roundToInt(),
                            (petSize.height / 2f - visible.y).coerceAtLeast(0f).roundToInt(),
                        )
                    }
                    .padding(4.dp)
            )
            val position = remember { BesidePet() }
            if (panel != null) Popup(
                popupPositionProvider = position,
                onDismissRequest = onPanelDismiss,
                properties = PopupProperties(focusable = true),
            ) {
                CompositionLocalProvider(
                    LocalBubbleTail provides position.tail,
                    LocalBubbleRoom provides position.room?.let { with(density) { it.toDp() } },
                ) { panel() }
            }
        }
    }
}
