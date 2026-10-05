package com.schedulewidget.mobile.record

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.stt.ModelState
import com.schedulewidget.mobile.stt.SttModel
import com.schedulewidget.mobile.stt.SttModelManager
import com.schedulewidget.mobile.stt.Transcriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun SttModel.sizeLabel(): String = if (sizeMb > 0) "약 ${sizeMb}MB" else "크기 확인 중"

fun ModelState.label(): String = when (this) {
    ModelState.Missing -> "받지 않음"
    ModelState.Ready -> "받음"
    is ModelState.Downloading -> if (totalBytes > 0) "받는 중 ${(downloadedBytes * 100 / totalBytes).coerceIn(0, 100)}%" else "받는 중…"
    is ModelState.Failed -> "실패: $message"
}

/** Progress bar for a downloading model (indeterminate until the size is known). */
@Composable
fun ModelProgress(state: ModelState, modifier: Modifier = Modifier) {
    if (state !is ModelState.Downloading) return
    if (state.totalBytes > 0) LinearProgressIndicator(
        progress = { (state.downloadedBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) }, modifier = modifier.fillMaxWidth(),
    ) else LinearProgressIndicator(modifier.fillMaxWidth())
}

/** One selectable model: radio, name, description, size, download state. */
@Composable
fun ModelOption(model: SttModel, state: ModelState, selected: Boolean, onSelect: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(model.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${model.description} · ${model.sizeLabel()} · ${state.label()}",
                style = MaterialTheme.typography.bodySmall,
                color = if (state is ModelState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelProgress(state, Modifier.padding(top = 4.dp, end = 8.dp))
        }
        trailing()
    }
}

/** Starts transcribing [item] with the saved settings; speaker separation only when its models are there. */
fun startTranscription(context: Context, item: RecordingItem, model: SttModel) {
    val stt = Repository.get(context).data.value.stt
    val diarize = stt.diarize && SttModelManager.diarizationState.value == ModelState.Ready
    Transcriber.start(context, item.id, item.audio, Transcriber.Options(model, diarize, stt.speakers))
}

/** True when [model] (and, with 화자 분리 on, the diarization models) are downloaded. */
fun modelsReady(context: Context, model: SttModel, states: Map<SttModel, ModelState>, diarization: ModelState): Boolean {
    val stt = Repository.get(context).data.value.stt
    val modelReady = states[model] == ModelState.Ready || (states[model] == null && SttModelManager.isReady(context, model))
    return modelReady && (!stt.diarize || diarization == ModelState.Ready)
}

/**
 * "받아쓰기" when the chosen model isn't downloaded yet: pick a model, download it (plus the speaker-separation models
 * when 화자 분리 is on) with progress, and start as soon as everything is ready.
 */
@Composable
fun TranscribeModelDialog(item: RecordingItem, onDismiss: () -> Unit, onStarted: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val data by repo.data.collectAsStateWithLifecycle()
    val states by SttModelManager.states.collectAsStateWithLifecycle()
    val diarization by SttModelManager.diarizationState.collectAsStateWithLifecycle()
    val chosen = SttModel.of(data.stt.model)
    var waiting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { SttModelManager.refresh(context) } } }
    val ready = modelsReady(context, chosen, states, diarization)
    // Downloaded while the dialog is open after pressing 내려받기: go straight on.
    LaunchedEffect(ready, waiting) {
        if (ready && waiting) {
            startTranscription(context, item, chosen)
            onStarted()
        }
    }
    val chosenState = states[chosen] ?: ModelState.Missing
    val downloading = chosenState is ModelState.Downloading || (data.stt.diarize && diarization is ModelState.Downloading)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("받아쓰기 모델") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "인터넷 없이 휴대폰 안에서 받아써요. 처음 한 번 모델을 내려받아야 합니다 (Wi-Fi 권장).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SttModel.entries.forEach { m ->
                    ModelOption(m, states[m] ?: ModelState.Missing, selected = m == chosen, onSelect = {
                        waiting = false
                        repo.update { it.copy(stt = it.stt.copy(model = m.id)) }
                    })
                }
                if (data.stt.diarize) {
                    Text("화자 분리 모델 · ${diarization.label()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    ModelProgress(diarization, Modifier.padding(top = 2.dp))
                }
                if (waiting && !ready) Text("다 받으면 바로 받아쓰기를 시작해요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            when {
                ready -> TextButton(onClick = { startTranscription(context, item, chosen); onStarted() }) { Text("받아쓰기 시작") }
                downloading -> TextButton(onClick = {
                    waiting = false
                    if (chosenState is ModelState.Downloading) SttModelManager.cancel(context, chosen)
                }) { Text("받기 취소") }
                else -> TextButton(onClick = {
                    waiting = true
                    if (chosenState != ModelState.Ready) SttModelManager.download(context, chosen)
                    if (data.stt.diarize && diarization != ModelState.Ready) SttModelManager.downloadDiarization(context)
                }) { Text("내려받기 (${chosen.sizeLabel()})") }
            }
        },
        dismissButton = {
            // Downloads keep going in the background after closing.
            TextButton(onClick = onDismiss) { Text(if (downloading) "뒤에서 받기" else "닫기") }
        },
    )
}
