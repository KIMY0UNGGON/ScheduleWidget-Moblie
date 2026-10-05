package com.schedulewidget.mobile.record

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.Repository
import kotlin.math.log10
import kotlin.math.roundToInt

/** "150% · +3.5dB" */
fun micGainLabel(percent: Int): String {
    val db = 20 * log10(percent / 100.0)
    val sign = if (db >= 0.05) "+" else if (db <= -0.05) "−" else "±"
    return "$percent% · $sign${"%.1f".format(kotlin.math.abs(db))}dB"
}

/** Input level → 0..1 bar fill (-60 dBFS .. 0 dBFS). */
fun levelFraction(db: Float): Float = ((db + 60f) / 60f).coerceIn(0f, 1f)

fun levelColor(db: Float): Color = when {
    db > -3f -> RecordRed
    db > -12f -> Color(0xFFFF9F0A)
    else -> Color(0xFF30D158)
}

/** "마이크 음량 (녹음 입력)": software gain 50–400 %, applied live while recording. */
@Composable
fun MicGainSetting(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val data by repo.data.collectAsStateWithLifecycle()
    val saved = data.stt.micGain.coerceIn(RecordService.MIN_GAIN, RecordService.MAX_GAIN)
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(saved.toFloat()) }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Mic, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("마이크 음량 (녹음 입력)", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(micGainLabel(if (dragging) (value / 10f).roundToInt() * 10 else saved), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = if (dragging) value else saved.toFloat(),
            onValueChange = { dragging = true; value = it },
            onValueChangeFinished = {
                val v = (value / 10f).roundToInt() * 10
                repo.update { it.copy(stt = it.stt.copy(micGain = v.coerceIn(RecordService.MIN_GAIN, RecordService.MAX_GAIN))) }
                dragging = false
            },
            valueRange = RecordService.MIN_GAIN.toFloat()..RecordService.MAX_GAIN.toFloat(),
        )
        Text(
            "녹음되는 소리의 크기예요. 교수님 목소리가 작게 녹음되면 올리세요. 재생 음량과는 별개입니다.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "스피커 음량 (재생)": scales in-app playback of recordings only (not the system volume, not the mic). */
@Composable
fun PlaybackVolumeSetting(modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val data by repo.data.collectAsStateWithLifecycle()
    val saved = data.stt.playbackVolume.coerceIn(0, 100)
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(saved.toFloat()) }
    val shown = if (dragging) value.roundToInt() else saved
    // Live while dragging, so the change is heard right away.
    SideEffect { PlaybackVolume.draft = if (dragging) shown else null }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(
            if (compact) "스피커 음량" else "스피커 음량 (재생)",
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge,
        )
        Slider(
            value = if (dragging) value else saved.toFloat(),
            onValueChange = { dragging = true; value = it },
            onValueChangeFinished = {
                val v = value.roundToInt().coerceIn(0, 100)
                repo.update { it.copy(stt = it.stt.copy(playbackVolume = v)) }
                dragging = false
            },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text("$shown%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** The dragged (not yet saved) speaker volume, so playback follows the finger. */
internal object PlaybackVolume {
    var draft by mutableStateOf<Int?>(null)
}

/** Live input level bar (after gain) with a "too loud" warning; only meaningful while recording. */
@Composable
fun InputLevelMeter(modifier: Modifier = Modifier, track: Color = MaterialTheme.colorScheme.surfaceVariant, warnColor: Color = RecordRed) {
    val level by Recorder.level.collectAsStateWithLifecycle()
    val fill by animateFloatAsState(levelFraction(level.peakDb), tween(90), label = "level")
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(track)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fill).clip(RoundedCornerShape(3.dp)).background(levelColor(level.peakDb)))
        }
        if (level.clipping) Text(
            "소리가 너무 커요 · 마이크 음량을 낮춰 주세요",
            style = MaterialTheme.typography.labelSmall, color = warnColor, modifier = Modifier.padding(top = 2.dp),
        )
    }
}
