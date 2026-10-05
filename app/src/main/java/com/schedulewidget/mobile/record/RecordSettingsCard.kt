package com.schedulewidget.mobile.record

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.stt.ModelState
import com.schedulewidget.mobile.stt.SttModel
import com.schedulewidget.mobile.stt.SttModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Settings section "수업 녹음": the feature switch, 음질, 녹음 마이크, 마이크 음량, 스피커 음량, 받아쓰기 모델 (download /
 * delete, storage used), 화자 분리 + 화자 수, 자동 받아쓰기, and a link to the recordings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordSettingsCard(onOpenRecordings: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val data by repo.data.collectAsStateWithLifecycle()
    val stt = data.stt
    fun set(f: (com.schedulewidget.mobile.data.SttSettings) -> com.schedulewidget.mobile.data.SttSettings) =
        repo.update { it.copy(stt = f(it.stt)) }

    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val micAllowed = remember(resumed) { Recorder.hasPermission(context) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumed++ }

    Column(modifier.fillMaxWidth()) {
        Text(
            "수업 녹음", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        SwitchLine("수업 녹음 기능", "캐릭터 세 번 = 녹음 시작, 녹음 중 두 번 = 정지 · 말풍선 2쪽 · 녹음 탭", stt.enabled) { on ->
            // Turning it off while recording: stop and save first.
            if (!on && Recorder.isRecording) Recorder.stop(context)
            set { it.copy(enabled = on) }
        }
        if (!stt.enabled) return@Column

        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!micAllowed) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "마이크 권한이 아직 없어요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) }) { Text("허용하기") }
            }

            Text("음질", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RecordingQuality.entries.forEach { q ->
                    FilterChip(selected = q.id == RecordingQuality.of(stt.quality).id, onClick = { set { it.copy(quality = q.id) } }, label = { Text(q.label) })
                }
            }
            Hint(RecordingQuality.of(stt.quality).description)

            Text("녹음 마이크", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RecordingMic.entries.forEach { m ->
                    FilterChip(selected = m == RecordingMic.of(stt.mic), onClick = { set { it.copy(mic = m.id) } }, label = { Text(m.label) })
                }
            }
            Hint(RecordingMic.of(stt.mic).description)

            MicGainSetting(Modifier.padding(top = 12.dp))
            PlaybackVolumeSetting(Modifier.padding(top = 8.dp))
            Hint("녹음 목록에서 들을 때의 소리 크기예요 (휴대폰 음량·마이크 음량과 별개).")
            PlaybackBoostSetting(Modifier.padding(top = 8.dp))
            Hint("휴대폰 최대 음량보다 크게 들려요. 앱 안 재생(파일·녹음)에만 적용되고, MP3 도구와 같은 설정이에요.")

            Text("받아쓰기 모델", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
            Hint("인터넷 없이 휴대폰 안에서 받아써요. 쓰려는 모델을 한 번 내려받아 두세요 (Wi-Fi 권장).")
            ModelList()
        }

        SwitchLine("화자 분리", "누가 말했는지 나눠서 적어요 (추가 모델 필요)", stt.diarize) { on ->
            set { it.copy(diarize = on) }
            if (on && SttModelManager.diarizationState.value == ModelState.Missing) SttModelManager.downloadDiarization(context)
        }
        if (stt.diarize) Column(Modifier.padding(horizontal = 16.dp)) {
            Text("화자 수", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 2, 3, 4, 5, 6).forEach { n ->
                    FilterChip(selected = stt.speakers == n, onClick = { set { it.copy(speakers = n) } }, label = { Text(if (n == 0) "자동" else "${n}명") })
                }
            }
            DiarizationRow()
        }
        CorrectionsEditor(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        SwitchLine("녹음 끝나면 자동 받아쓰기", "정지하면 바로 받아쓰기를 시작해요", stt.autoTranscribe) { on -> set { it.copy(autoTranscribe = on) } }
        ListItem(
            leadingContent = { Icon(Icons.Outlined.GraphicEq, null) },
            headlineContent = { Text("녹음 목록") },
            supportingContent = { Text("듣기 · 받아쓰기 · 공유 · 삭제") },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(onClick = onOpenRecordings),
        )
    }
}

@Composable
private fun ModelList() {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val data by repo.data.collectAsStateWithLifecycle()
    val states by SttModelManager.states.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { SttModelManager.refresh(context) } } }
    val used by produceState(0L, states) { value = withContext(Dispatchers.IO) { runCatching { SttModelManager.usedBytes(context) }.getOrDefault(0L) } }
    val chosen = SttModel.of(data.stt.model)
    SttModel.entries.forEach { m ->
        val state = states[m] ?: ModelState.Missing
        ModelOption(m, state, selected = m == chosen, onSelect = { repo.update { it.copy(stt = it.stt.copy(model = m.id)) } }) {
            when (state) {
                ModelState.Missing, is ModelState.Failed -> TextButton(onClick = { SttModelManager.download(context, m) }) {
                    Text(if (state is ModelState.Failed) "다시" else "받기")
                }
                is ModelState.Downloading -> TextButton(onClick = { SttModelManager.cancel(context, m) }) { Text("취소") }
                ModelState.Ready -> TwoTapDelete { SttModelManager.delete(context, m) }
            }
        }
    }
    Hint("모델이 차지하는 공간: ${formatSize(used)}")
}

@Composable
private fun DiarizationRow() {
    val context = LocalContext.current
    val state by SttModelManager.diarizationState.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("화자 분리 모델 · ${state.label()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ModelProgress(state, Modifier.padding(top = 4.dp, end = 8.dp))
        }
        if (state == ModelState.Missing || state is ModelState.Failed) {
            TextButton(onClick = { SttModelManager.downloadDiarization(context) }) { Text("받기") }
        }
    }
}

/** "삭제" that needs a second tap within three seconds. */
@Composable
private fun TwoTapDelete(onDelete: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3000); armed = false } }
    if (armed) OutlinedButton(onClick = { armed = false; onDelete() }) { Text("정말 삭제", color = MaterialTheme.colorScheme.error) }
    else TextButton(onClick = { armed = true }) { Text("삭제") }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchLine(label: String, supporting: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChange(!checked) },
    )
}
