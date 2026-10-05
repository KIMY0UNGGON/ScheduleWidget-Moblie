package com.schedulewidget.mobile.apps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.music.AudioRoute
import com.schedulewidget.mobile.music.VolumeBoost
import kotlin.math.roundToInt

@Composable
internal fun PlainIcon(icon: ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(24.dp))
    }
}
@Composable
internal fun VolumeButton(volume: Float, p: Mp3Palette, live: Boolean, boostRoute: AudioRoute, onChange: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(volume) }
    Box {
        PlainIcon(if (volume <= 0.001f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "볼륨", p.ink) {
            local = volume
            open = true
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = p.panel) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("볼륨", style = Mp3Style.caption, color = p.ink)
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = local,
                    onValueChange = {
                        local = it
                        // System volume is cheap to set live; the widget player persists AppData (disk write +
                        // widget refresh) on every call, so it is only committed when the drag ends.
                        if (live) onChange(it)
                    },
                    onValueChangeFinished = { if (!live) onChange(local) },
                    colors = SliderDefaults.colors(
                        thumbColor = p.accent,
                        activeTrackColor = p.accent,
                        inactiveTrackColor = p.track,
                    ),
                    modifier = Modifier.width(180.dp),
                )
                Text("${(local * 100).toInt()}", style = Mp3Style.fine, color = p.muted, modifier = Modifier.width(28.dp))
            }
            BoostRow(p, boostRoute)
        }
    }
}

/**
 * "증폭" 100–300 % under the volume slider (AppData.volumeBoost, shared with recordings). Only audio this app
 * renders itself can be boosted; for YouTube in the WebView or another music app it is shown disabled with why.
 */
@Composable
private fun BoostRow(p: Mp3Palette, route: AudioRoute) {
    val ctx = LocalContext.current
    val repo = remember { Repository.get(ctx) }
    val data by repo.data.collectAsStateWithLifecycle()
    val saved = VolumeBoost.clamp(data.volumeBoost)
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(saved.toFloat()) }
    val shown = if (dragging) VolumeBoost.clamp((local / VolumeBoost.STEP).roundToInt() * VolumeBoost.STEP) else saved
    // Only our own playback (phone files, recordings) can be boosted; YouTube / other apps show it disabled.
    val enabled = route == AudioRoute.InApp
    Column(Modifier.padding(horizontal = 14.dp).width(260.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("증폭", style = Mp3Style.caption, color = if (enabled) p.ink else p.muted)
            Spacer(Modifier.width(8.dp))
            Slider(
                value = if (dragging) local else saved.toFloat(),
                onValueChange = {
                    dragging = true
                    local = it
                    // The effect follows the finger; AppData is written once when the drag ends.
                    VolumeBoost.draft.value = VolumeBoost.clamp((it / VolumeBoost.STEP).roundToInt() * VolumeBoost.STEP)
                },
                onValueChangeFinished = {
                    VolumeBoost.save(ctx, local.roundToInt())
                    dragging = false
                },
                enabled = enabled,
                valueRange = VolumeBoost.MIN.toFloat()..VolumeBoost.MAX.toFloat(),
                steps = (VolumeBoost.MAX - VolumeBoost.MIN) / VolumeBoost.STEP - 1,
                colors = SliderDefaults.colors(
                    thumbColor = p.accent,
                    activeTrackColor = p.accent,
                    inactiveTrackColor = p.track,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                    disabledThumbColor = p.muted,
                    disabledActiveTrackColor = p.muted,
                    disabledInactiveTrackColor = p.track,
                    disabledActiveTickColor = Color.Transparent,
                    disabledInactiveTickColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text("$shown%", style = Mp3Style.fine, color = p.muted, modifier = Modifier.width(34.dp))
        }
        val caption = when {
            !enabled -> "앱 안 재생(파일·녹음)에만 적용돼요 · 다른 앱 소리는 키울 수 없어요"
            shown <= VolumeBoost.MIN -> "끔"
            else -> "${VolumeBoost.label(shown)} · 앱 안 재생(파일·녹음)에만 적용"
        }
        Text(caption, style = Mp3Style.fine, color = p.muted)
        if (enabled && shown > VolumeBoost.WARN_ABOVE) {
            Text("크게 올리면 스피커·귀에 무리가 갈 수 있어요", style = Mp3Style.fine, color = Color(0xFFFF453A))
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
internal fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    palette: Mp3Palette,
    modifier: Modifier = Modifier,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    // The gesture coroutines are cancelled (no onDragCancel) when these change mid-drag; don't leave the knob stuck.
    LaunchedEffect(enabled, durationMs) { dragFraction = null }
    val fraction = (dragFraction ?: if (durationMs > 0) positionMs.toFloat() / durationMs else 0f).coerceIn(0f, 1f)
    val track = palette.track
    val fill = if (enabled) palette.fill else palette.muted
    val input = if (!enabled) Modifier else Modifier
        .pointerInput(durationMs) {
            detectTapGestures { o -> seek((o.x / size.width).coerceIn(0f, 1f).times(durationMs).toLong()) }
        }
        .pointerInput(durationMs) {
            detectHorizontalDragGestures(
                onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                onDragEnd = {
                    dragFraction?.let { seek((it * durationMs).toLong()) }
                    dragFraction = null
                },
                onDragCancel = { dragFraction = null },
                onHorizontalDrag = { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) },
            )
        }
    val dragging = dragFraction != null
    Canvas(modifier.height(16.dp).then(input)) {
        val h = 4.dp.toPx()
        val y = (size.height - h) / 2
        val r = CornerRadius(h / 2, h / 2)
        drawRoundRect(track, Offset(0f, y), Size(size.width, h), r)
        drawRoundRect(fill, Offset(0f, y), Size(size.width * fraction, h), r)
        if (dragging) drawCircle(fill, radius = 7.dp.toPx(), center = Offset(size.width * fraction, size.height / 2))
    }
}
