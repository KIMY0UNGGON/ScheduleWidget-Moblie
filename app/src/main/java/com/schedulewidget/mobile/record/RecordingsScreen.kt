package com.schedulewidget.mobile.record

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.stt.SttModel
import com.schedulewidget.mobile.stt.SttModelManager
import com.schedulewidget.mobile.stt.TranscribeState
import com.schedulewidget.mobile.stt.Transcriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val rec by Recorder.state.collectAsStateWithLifecycle()
    val version by Recordings.changes.collectAsStateWithLifecycle()
    val transcribe by Transcriber.states.collectAsStateWithLifecycle()
    val items by produceState<List<RecordingItem>?>(null, version, rec.id) {
        value = withContext(Dispatchers.IO) { Recordings.list(context, skipId = rec.id) }
    }
    // Which recordings already have a saved transcript (re-checked when a transcription changes state).
    // Keyed on the finished ids only: progress ticks must not re-read every transcript.
    val finished = remember(transcribe) { transcribe.filterValues { it is TranscribeState.Done }.keys }
    val withTranscript by produceState(emptySet<String>(), items, finished) {
        val list = items ?: return@produceState
        value = withContext(Dispatchers.IO) { list.filter { runCatching { Transcriber.load(context, it.id) != null }.getOrDefault(false) }.map { it.id }.toSet() }
    }
    val player = rememberRecordingPlayer()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var transcriptOf by rememberSaveable { mutableStateOf<String?>(null) }
    var askModelFor by remember { mutableStateOf<RecordingItem?>(null) }
    var renaming by remember { mutableStateOf<RecordingItem?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { SttModelManager.refresh(context) } } }
    // "보기" in the pet's bubble: open that transcript once.
    val requested by Recorder.openTranscript.collectAsStateWithLifecycle()
    LaunchedEffect(requested) {
        requested?.let { transcriptOf = it; expanded = it; Recorder.openTranscript.value = null }
    }

    // The transcript replaces the list (same player, so playback continues when going back).
    val shownTranscript = transcriptOf?.let { id -> items?.firstOrNull { it.id == id } }
    if (shownTranscript != null) {
        BackHandler { transcriptOf = null }
        TranscriptScreen(shownTranscript, player, onBack = { transcriptOf = null })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("수업 녹음") },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "now") { RecordNowCard(rec) }
            val list = items
            if (list != null && list.isEmpty()) item(key = "empty") {
                Text(
                    "아직 녹음이 없어요.\n캐릭터를 세 번 누르면 녹음이 시작되고, 녹음 중에 두 번 누르면 멈추고 저장돼요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp, horizontal = 8.dp),
                )
            }
            if (list != null) items(list, key = { it.id }) { item ->
                RecordingCard(
                    item = item,
                    expanded = expanded == item.id,
                    onExpand = { expanded = if (expanded == item.id) null else item.id },
                    player = player,
                    state = transcribe[item.id] ?: TranscribeState.Idle,
                    hasTranscript = item.id in withTranscript,
                    onTranscribe = {
                        val model = SttModel.of(Repository.get(context).data.value.stt.model)
                        if (modelsReady(context, model, SttModelManager.states.value, SttModelManager.diarizationState.value)) {
                            startTranscription(context, item, model)
                        } else askModelFor = item
                    },
                    onOpenTranscript = { transcriptOf = item.id },
                    onRename = { renaming = item },
                    onDelete = {
                        player.stopIf(item)
                        Recordings.delete(context, item)
                    },
                )
            }
        }
    }

    askModelFor?.let { item -> TranscribeModelDialog(item, onDismiss = { askModelFor = null }, onStarted = { askModelFor = null }) }
    renaming?.let { item -> RenameDialog(item, onDismiss = { renaming = null }) { title -> Recordings.rename(context, item, title); renaming = null } }
}
