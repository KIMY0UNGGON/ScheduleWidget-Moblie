package com.schedulewidget.mobile.record

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.notes.editor.recordingBelongsToNote
import com.schedulewidget.mobile.stt.SttModel
import com.schedulewidget.mobile.stt.SttModelManager
import com.schedulewidget.mobile.stt.TranscribeState
import com.schedulewidget.mobile.stt.Transcriber
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
    onBack: (() -> Unit)? = null,
    noteId: String? = null,
    inkRecordingIds: Set<String> = emptySet(),
    onStartRecording: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rec by Recorder.state.collectAsStateWithLifecycle()
    val version by Recordings.changes.collectAsStateWithLifecycle()
    val transcribe by Transcriber.states.collectAsStateWithLifecycle()
    val items by produceState<List<RecordingItem>?>(null, version, rec.id, noteId, inkRecordingIds) {
        value = withContext(Dispatchers.IO) {
            val all = Recordings.list(context, skipId = rec.id)
            if (noteId == null) all else all.filter {
                recordingBelongsToNote(it.id, it.meta.noteId, noteId, inkRecordingIds, it.meta.noteIds)
            }
        }
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
    var exportAudioFor by remember { mutableStateOf<RecordingItem?>(null) }
    val audioSaveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri ->
        val item = exportAudioFor
        exportAudioFor = null
        if (uri == null || item == null) return@rememberLauncherForActivityResult
        if (!isSafeRecordingExportUri(uri, context.packageName + ".files")) {
            Toast.makeText(context, "내보낼 위치를 선택해 주세요", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val saved = try {
                exportRecordingAudio(context, item.audio, uri)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            Toast.makeText(context, if (saved) "녹음을 저장했어요" else "녹음을 저장하지 못했어요", Toast.LENGTH_SHORT).show()
        }
    }
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
                title = { Text(if (noteId == null) "수업 녹음" else "노트 녹음") },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "now") { RecordNowCard(rec, onStartRecording) }
            val list = items
            if (list != null && list.isEmpty()) item(key = "empty") {
                Text(
                    when {
                        noteId == null -> "아직 녹음이 없어요.\n캐릭터를 세 번 누르면 녹음이 시작되고, 녹음 중에 두 번 누르면 멈추고 저장돼요."
                        inkRecordingIds.isNotEmpty() -> "필기에 연결된 녹음을 찾지 못했어요. 녹음 파일이 삭제되었거나 옮겨졌을 수 있어요."
                        else -> "이 노트에 연결된 녹음이 없어요. 녹음 파일이나 정보가 삭제된 경우 목록에 나타나지 않을 수 있어요."
                    },
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
                    onExportAudio = { exportAudioFor = item; audioSaveLauncher.launch(item.audio.name) },
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

internal fun isSafeRecordingExportUri(uri: Uri, ownFileProviderAuthority: String): Boolean =
    isSafeRecordingExportLocation(uri.scheme, uri.authority, ownFileProviderAuthority)

internal fun isSafeRecordingExportLocation(scheme: String?, authority: String?, ownFileProviderAuthority: String): Boolean {
    val provider = authority?.substringAfterLast('@')?.takeIf { it.isNotBlank() } ?: return false
    return scheme.equals("content", ignoreCase = true) && !provider.equals(ownFileProviderAuthority, ignoreCase = true)
}

private suspend fun exportRecordingAudio(context: Context, audio: File, destination: Uri) =
    withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        job.ensureActive()
        audio.inputStream().use { source ->
            job.ensureActive()
            val output = context.contentResolver.openOutputStream(destination, "wt") ?: error("파일을 쓸 수 없어요")
            output.use { target ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    job.ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    target.write(buffer, 0, count)
                }
            }
        }
    }
