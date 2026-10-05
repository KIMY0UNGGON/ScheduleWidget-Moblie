package com.schedulewidget.mobile.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.schedulewidget.mobile.apps.Mp3Palette
import com.schedulewidget.mobile.apps.Mp3Style
import com.schedulewidget.mobile.apps.pressable
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.data.SttSettings
import com.schedulewidget.mobile.record.InputLevelMeter
import com.schedulewidget.mobile.record.RecordRed
import com.schedulewidget.mobile.record.RecordService
import com.schedulewidget.mobile.record.Recorder
import com.schedulewidget.mobile.record.RecordingDot
import com.schedulewidget.mobile.record.RecordingItem
import com.schedulewidget.mobile.record.RecordingMic
import com.schedulewidget.mobile.record.RecordingQuality
import com.schedulewidget.mobile.record.Recordings
import com.schedulewidget.mobile.record.copyTranscript
import com.schedulewidget.mobile.record.formatDuration
import com.schedulewidget.mobile.record.label
import com.schedulewidget.mobile.record.micGainLabel
import com.schedulewidget.mobile.record.modelsReady
import com.schedulewidget.mobile.record.rememberRecordingElapsed
import com.schedulewidget.mobile.record.sizeLabel
import com.schedulewidget.mobile.record.startTranscription
import com.schedulewidget.mobile.stt.ModelState
import com.schedulewidget.mobile.stt.SttModel
import com.schedulewidget.mobile.stt.SttModelManager
import com.schedulewidget.mobile.stt.TranscribeState
import com.schedulewidget.mobile.stt.Transcriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Second page of the pet's speech bubble (swipe left): 녹음 시작 / 정지 with the elapsed time and input level, 마이크
 * 음량, 음질, 마이크, 받아쓰기 모델, 자동 받아쓰기 and 화자 분리, the latest recordings with 받아쓰기 / progress / 보기 / 복사,
 * and a link to the app's 녹음 tab. Scrolls when taller than ~60% of the screen or [maxHeight].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PetRecordPage(p: Mp3Palette, maxHeight: Dp?, onOpenRecordings: () -> Unit, onOpenTranscript: (String) -> Unit) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val data by repo.data.collectAsState()
    val stt = data.stt
    fun set(f: (SttSettings) -> SttSettings) = repo.update { it.copy(stt = f(it.stt)) }
    val rec by Recorder.state.collectAsState()
    // The screen, not the window: in the floating pet the window is only as big as the bubble.
    val screenCap = context.resources.displayMetrics.let { (it.heightPixels / it.density * 0.6f).dp }
    val cap = if (maxHeight != null) minOf(maxHeight, screenCap) else screenCap

    Column(
        Modifier.fillMaxWidth().heightIn(max = cap).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- 녹음 시작 / 정지
        if (rec.isRecording) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecordingDot(size = 10.dp)
                Spacer(Modifier.width(8.dp))
                Text(formatDuration(rememberRecordingElapsed(rec)), style = Mp3Style.header, color = p.ink, modifier = Modifier.weight(1f))
                Pill("정지", RecordRed, Color.White) { Recorder.stop(context) }
            }
            InputLevelMeter(track = p.track)
        } else {
            Box(
                Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp)).background(RecordRed)
                    .pressable { Recorder.start(context) },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White))
                    Spacer(Modifier.width(8.dp))
                    Text("녹음 시작", style = Mp3Style.title, color = Color.White)
                }
            }
        }

        // ---- 마이크 음량 (input gain; the speaker volume is in the app's 녹음 tab)
        GainSlider(p, stt.micGain) { v -> set { it.copy(micGain = v) } }

        Label(p, "음질")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RecordingQuality.entries.forEach { q -> Chip(p, q.label, q == RecordingQuality.of(stt.quality)) { set { it.copy(quality = q.id) } } }
        }
        Label(p, "마이크")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RecordingMic.entries.forEach { m -> Chip(p, m.label, m == RecordingMic.of(stt.mic)) { set { it.copy(mic = m.id) } } }
        }
        Label(p, "받아쓰기 모델")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SttModel.entries.forEach { m -> Chip(p, m.label, m == SttModel.of(stt.model)) { set { it.copy(model = m.id) } } }
        }
        SwitchLine(p, "자동 받아쓰기", stt.autoTranscribe) { on -> set { it.copy(autoTranscribe = on) } }
        SwitchLine(p, "화자 분리", stt.diarize) { on ->
            set { it.copy(diarize = on) }
            if (on && SttModelManager.diarizationState.value == ModelState.Missing) SttModelManager.downloadDiarization(context)
        }

        // ---- 받아쓰기 for the latest recordings
        LatestRecordings(p, rec.id, onOpenTranscript)

        Row(
            Modifier.fillMaxWidth().pressable(onClick = onOpenRecordings).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("녹음 목록 열기", style = Mp3Style.caption, color = p.accent, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = p.accent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun LatestRecordings(p: Mp3Palette, recordingId: String?, onOpenTranscript: (String) -> Unit) {
    val context = LocalContext.current
    val version by Recordings.changes.collectAsState()
    val transcribe by Transcriber.states.collectAsState()
    val items by produceState(emptyList<RecordingItem>(), version, recordingId) {
        value = withContext(Dispatchers.IO) { runCatching { Recordings.list(context, skipId = recordingId).take(3) }.getOrDefault(emptyList()) }
    }
    val finished = remember(transcribe) { transcribe.filterValues { it is TranscribeState.Done }.keys }
    val done by produceState(emptySet<String>(), items, finished) {
        value = withContext(Dispatchers.IO) {
            items.filter { runCatching { Transcriber.load(context, it.id) != null }.getOrDefault(false) }.map { it.id }.toSet()
        }
    }
    val states by SttModelManager.states.collectAsState()
    val diarization by SttModelManager.diarizationState.collectAsState()
    val data by remember { Repository.get(context) }.data.collectAsState()
    val model = SttModel.of(data.stt.model)
    val ready = modelsReady(context, model, states, diarization)
    // A 받아쓰기 waiting for the model download; starts as soon as it is ready.
    var pending by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ready, pending) {
        val id = pending ?: return@LaunchedEffect
        if (ready) {
            items.firstOrNull { it.id == id }?.let { startTranscription(context, it, model) }
            pending = null
        }
    }

    if (items.isEmpty()) return
    Label(p, "받아쓰기")
    items.forEach { item ->
        val state = transcribe[item.id] ?: TranscribeState.Idle
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.meta.title, style = Mp3Style.caption, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatDuration(item.meta.durationMs), style = Mp3Style.fine, color = p.muted)
                }
                when {
                    state is TranscribeState.Running -> SmallAction(p, "취소") { Transcriber.cancel(context, item.id) }
                    item.id in done || state is TranscribeState.Done -> {
                        SmallAction(p, "보기") { onOpenTranscript(item.id) }
                        SmallAction(p, "복사") {
                            val t = runCatching { Transcriber.load(context, item.id) }.getOrNull()
                            if (t != null) copyTranscript(context, t.asText())
                        }
                    }
                    pending == item.id -> SmallAction(p, "취소") { pending = null }
                    else -> SmallAction(p, if (state is TranscribeState.Failed) "다시" else "받아쓰기") {
                        if (ready) startTranscription(context, item, model) else pending = item.id
                    }
                }
            }
            when (state) {
                is TranscribeState.Running -> {
                    LinearProgressIndicator(
                        progress = { state.fraction.coerceIn(0f, 1f) },
                        color = p.accent, trackColor = p.track, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    Text("${state.stage} · ${(state.fraction * 100).roundToInt().coerceIn(0, 100)}%", style = Mp3Style.fine, color = p.muted)
                }
                is TranscribeState.Failed -> Text(state.message, style = Mp3Style.fine, color = RecordRed, maxLines = 2, overflow = TextOverflow.Ellipsis)
                else -> {}
            }
            if (pending == item.id && !ready) ModelNeeded(p, model, states[model] ?: ModelState.Missing, data.stt.diarize, diarization)
        }
    }
}

/** Inline "모델을 먼저 받아야 해요" row with the size and download progress (no dialogs in the floating bubble). */
@Composable
private fun ModelNeeded(p: Mp3Palette, model: SttModel, state: ModelState, diarize: Boolean, diarization: ModelState) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${model.label} 모델(${model.sizeLabel()})이 필요해요 · ${state.label()}", style = Mp3Style.fine, color = p.secondary)
        if (diarize && diarization != ModelState.Ready) Text("화자 분리 모델 · ${diarization.label()}", style = Mp3Style.fine, color = p.secondary)
        val busy = state is ModelState.Downloading || (diarize && diarization is ModelState.Downloading)
        if (busy) LinearProgressIndicator(color = p.accent, trackColor = p.track, modifier = Modifier.fillMaxWidth())
        else Pill("받기 · 다 받으면 바로 시작", p.accent, Color.White) {
            if (state != ModelState.Ready) SttModelManager.download(context, model)
            if (diarize && diarization != ModelState.Ready) SttModelManager.downloadDiarization(context)
        }
    }
}

@Composable
private fun GainSlider(p: Mp3Palette, saved: Int, onSave: (Int) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(saved.toFloat()) }
    val current = saved.coerceIn(RecordService.MIN_GAIN, RecordService.MAX_GAIN)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("마이크 음량 (녹음 입력)", style = Mp3Style.fine, color = p.secondary, modifier = Modifier.weight(1f))
            Text(micGainLabel(if (dragging) ((value / 10f).roundToInt() * 10) else current), style = Mp3Style.fine, color = p.accent, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = if (dragging) value else current.toFloat(),
            onValueChange = { dragging = true; value = it },
            onValueChangeFinished = { onSave(((value / 10f).roundToInt() * 10).coerceIn(RecordService.MIN_GAIN, RecordService.MAX_GAIN)); dragging = false },
            valueRange = RecordService.MIN_GAIN.toFloat()..RecordService.MAX_GAIN.toFloat(),
            colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.track),
            modifier = Modifier.height(28.dp),
        )
    }
}

@Composable
private fun Label(p: Mp3Palette, text: String) {
    Text(text, style = Mp3Style.fine, color = p.muted, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun Chip(p: Mp3Palette, label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, style = Mp3Style.fine, color = if (selected) Color.White else p.ink, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) p.accent else p.chip)
            .pressable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun Pill(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Text(
        label, style = Mp3Style.caption, color = fg, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).pressable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun SmallAction(p: Mp3Palette, label: String, onClick: () -> Unit) {
    Text(
        label, style = Mp3Style.fine, color = p.accent, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).pressable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

@Composable
private fun SwitchLine(p: Mp3Palette, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().pressable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Mp3Style.caption, color = p.ink, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = p.accent, checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White, uncheckedTrackColor = p.track, uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}
