package com.schedulewidget.mobile.record

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.data.SttSettings
import com.schedulewidget.mobile.stt.SttModel
import com.schedulewidget.mobile.stt.TranscribeState
import com.schedulewidget.mobile.stt.Transcriber
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Top card: a big 녹음 / 정지 button with the elapsed time, and the current settings in one line. */
@Composable
internal fun RecordNowCard(rec: RecordingState) {
    val context = LocalContext.current
    val data by remember { Repository.get(context) }.data.collectAsStateWithLifecycle()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rec.isRecording) {
                    RecordingDot(size = 12.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (rec.isRecording) formatDuration(rememberRecordingElapsed(rec)) else "00:00",
                    style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold,
                    color = if (rec.isRecording) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (rec.isRecording) InputLevelMeter(Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(12.dp))
            if (rec.isRecording) Button(
                onClick = { Recorder.stop(context) },
                colors = ButtonDefaults.buttonColors(containerColor = RecordRed, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Outlined.Stop, null, Modifier.size(22.dp)); Spacer(Modifier.width(6.dp))
                Text("정지하고 저장", style = MaterialTheme.typography.titleMedium)
            } else Button(onClick = { Recorder.start(context) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Outlined.Mic, null, Modifier.size(22.dp)); Spacer(Modifier.width(6.dp))
                Text("녹음 시작", style = MaterialTheme.typography.titleMedium)
            }
            MicGainSetting(Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                settingsSummary(data.stt),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!rec.isRecording) Text(
                "화면을 꺼도 계속 녹음돼요 · 캐릭터 세 번 = 시작, 녹음 중 두 번 = 정지",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
internal fun settingsSummary(stt: SttSettings): String = buildList {
    add(RecordingQuality.of(stt.quality).label)
    add(RecordingMic.of(stt.mic).label + " 마이크")
    add(SttModel.of(stt.model).label)
    if (stt.diarize) add(if (stt.speakers > 0) "화자 ${stt.speakers}명" else "화자 분리")
    if (stt.autoTranscribe) add("자동 받아쓰기")
}.joinToString(" · ")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordingCard(
    item: RecordingItem,
    expanded: Boolean,
    onExpand: () -> Unit,
    player: RecordingPlayer,
    state: TranscribeState,
    hasTranscript: Boolean,
    onTranscribe: () -> Unit,
    onOpenTranscript: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.clickable(onClick = onExpand).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(item.meta.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${dateLabel(item.meta.createdAt)} · ${formatDuration(item.meta.durationMs)} · ${formatSize(item.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TranscriptStatus(state, hasTranscript)
        }
        if (state is TranscribeState.Running) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    LinearProgressIndicator(progress = { state.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${state.stage} · ${(state.fraction * 100).toInt().coerceIn(0, 100)}%",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                TextButton(onClick = { Transcriber.cancel(context, item.id) }) { Text("취소") }
            }
        }
        val current = player.isCurrent(item)
        if (expanded || current) {
            // Subtitles from the transcript (when there is one), above the player while this recording is loaded.
            val track = rememberSubtitleTrack(item, hasTranscript || state is TranscribeState.Done)
            val subtitles = track.segments
            if (current && SubtitlePrefs.enabled && subtitles.isNotEmpty()) {
                val index by remember(subtitles, player) { derivedStateOf { subtitleIndexAt(subtitles, player.positionMs) } }
                SubtitleView(subtitles, index, SubtitlePrefs.size, Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp), names = track.names)
            }
            PlayerControls(item, player, Modifier.padding(horizontal = 8.dp))
            PlaybackVolumeSetting(Modifier.padding(start = 16.dp, end = 12.dp), compact = true)
            PlaybackBoostSetting(Modifier.padding(start = 16.dp, end = 12.dp), compact = true)
            if (subtitles.isNotEmpty()) SubtitleControls(Modifier.padding(start = 12.dp, end = 12.dp))
        }
        if (expanded) {
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when {
                    state is TranscribeState.Running -> {}
                    hasTranscript || state is TranscribeState.Done -> FilledTonalButton(onClick = onOpenTranscript) {
                        Icon(Icons.AutoMirrored.Outlined.Notes, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("받아쓰기 보기")
                    }
                    else -> FilledTonalButton(onClick = onTranscribe) {
                        Icon(Icons.AutoMirrored.Outlined.Notes, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                        Text(if (state is TranscribeState.Failed) "다시 받아쓰기" else "받아쓰기")
                    }
                }
                if ((hasTranscript || state is TranscribeState.Done) && state !is TranscribeState.Running) {
                    OutlinedButton(onClick = onTranscribe) { Text("다시 받아쓰기") }
                }
                OutlinedButton(onClick = onRename) { Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("이름") }
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Recordings.shareIntent(context, item)) }
                        .onFailure { Toast.makeText(context, "공유할 수 없어요", Toast.LENGTH_SHORT).show() }
                }) { Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("공유") }
                DeleteButton(onDelete)
            }
        }
    }
}

@Composable
private fun TranscriptStatus(state: TranscribeState, hasTranscript: Boolean) {
    val (text, color) = when {
        state is TranscribeState.Running -> "받아쓰는 중" to MaterialTheme.colorScheme.primary
        state is TranscribeState.Failed -> "받아쓰기 실패: ${state.message}" to MaterialTheme.colorScheme.error
        hasTranscript || state is TranscribeState.Done -> "받아쓰기 완료" to MaterialTheme.colorScheme.primary
        else -> return
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(top = 2.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** Two-tap delete: the first tap arms it (red "한 번 더 누르면 삭제") for three seconds. */
@Composable
private fun DeleteButton(onDelete: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3000); armed = false } }
    if (armed) Button(
        onClick = { armed = false; onDelete() },
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
    ) { Text("한 번 더 누르면 삭제") }
    else OutlinedButton(onClick = { armed = true }) {
        Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("삭제")
    }
}

@Composable
internal fun RenameDialog(item: RecordingItem, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by remember { mutableStateOf(item.meta.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이름 바꾸기") },
        text = { OutlinedTextField(title, { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSave(title) }, enabled = title.isNotBlank()) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

private val dateFormat = DateTimeFormatter.ofPattern("yyyy.M.d (E) a h:mm", Locale.KOREAN)

internal fun dateLabel(epochMs: Long): String = dateFormat.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
