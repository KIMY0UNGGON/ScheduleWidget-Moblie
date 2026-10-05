package com.schedulewidget.mobile.record

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.music.VolumeBoost
import kotlin.math.roundToInt

/** Play/pause + seek bar + "12:34 / 1:02:03" for one recording. */
@Composable
internal fun RecordingPlayerControls(item: RecordingItem, player: RecordingPlayer, modifier: Modifier = Modifier) {
    val current = player.isCurrent(item)
    val duration = if (current && player.durationMs > 0) player.durationMs else item.meta.durationMs
    val position = if (current) player.positionMs else 0L
    // While the thumb is dragged the bar follows the finger; seeking happens on release.
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { player.toggle(item) }) {
            val playing = current && player.playing
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "일시정지" else "재생")
        }
        Slider(
            value = if (dragging) dragValue else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                dragging = false
                if (duration > 0) player.seek(item, (dragValue * duration).toLong(), play = current && player.playing || !current)
            },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(
            "${formatDuration(if (dragging) (dragValue * duration).toLong() else position)} / ${formatDuration(duration)}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * "증폭": volume boost above 100 % for recordings, next to "스피커 음량". Shares AppData.volumeBoost with the MP3
 * tool; follows the finger live (VolumeBoost.draft) and is saved when the drag ends.
 */
@Composable
internal fun RecordingPlaybackBoostSetting(modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    val saved by remember { Repository.get(context) }.data.collectAsStateWithLifecycle()
    val savedBoost = VolumeBoost.clamp(saved.volumeBoost)
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(savedBoost.toFloat()) }
    val shown = if (dragging) VolumeBoost.clamp((value / VolumeBoost.STEP).roundToInt() * VolumeBoost.STEP) else savedBoost
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.GraphicEq, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(
                if (compact) "증폭" else "증폭 (100% 이상)",
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = if (dragging) value else savedBoost.toFloat(),
                onValueChange = {
                    dragging = true
                    value = it
                    VolumeBoost.draft.value = VolumeBoost.clamp((it / VolumeBoost.STEP).roundToInt() * VolumeBoost.STEP)
                },
                onValueChangeFinished = {
                    VolumeBoost.save(context, value.roundToInt())
                    dragging = false
                },
                valueRange = VolumeBoost.MIN.toFloat()..VolumeBoost.MAX.toFloat(),
                steps = (VolumeBoost.MAX - VolumeBoost.MIN) / VolumeBoost.STEP - 1,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(
                if (shown <= VolumeBoost.MIN) "끔" else "$shown% · ${VolumeBoost.label(shown)}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            )
        }
        if (shown > VolumeBoost.WARN_ABOVE) {
            Text(
                "크게 올리면 스피커·귀에 무리가 갈 수 있어요",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 24.dp),
            )
        }
    }
}
