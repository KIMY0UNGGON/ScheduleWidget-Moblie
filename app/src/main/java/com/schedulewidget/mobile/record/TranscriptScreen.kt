package com.schedulewidget.mobile.record

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.stt.Transcript
import com.schedulewidget.mobile.stt.Transcriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun speakerColor(speaker: Int?): Color = if (speaker == null) Color(0xFF8E8E93) else speakerColors[speaker.mod(speakerColors.size)]

fun copyTranscript(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("받아쓰기", text))
    Toast.makeText(context, "받아쓰기를 복사했어요", Toast.LENGTH_SHORT).show()
}
/**
 * The transcript of [item]: speaker-coloured paragraphs; tapping a timestamp plays from there, and the paragraph being
 * played is highlighted and followed. 수정 turns on correction: tap a paragraph to edit its text, tap a speaker to
 * rename it or move the paragraph to another speaker, 찾아 바꾸기, 자동 고치기 단어, 되돌리기. Changes save by themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptScreen(item: RecordingItem, player: RecordingPlayer, onBack: () -> Unit) {
    val context = LocalContext.current
    val loaded by produceState<Transcript?>(null, item.id) {
        value = withContext(Dispatchers.IO) { runCatching { Transcriber.load(context, item.id) }.getOrNull() }
    }
    val initial = loaded
    if (initial == null) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(item.meta.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        }) { padding -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        return
    }
    val editor = remember(initial) { TranscriptEditor(initial) }
    TranscriptContent(item, player, editor, onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptContent(item: RecordingItem, player: RecordingPlayer, editor: TranscriptEditor, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val t = editor.transcript
    var editMode by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var showCorrections by remember { mutableStateOf(false) }
    var editingSegment by remember { mutableIntStateOf(-1) }
    var speakerDialog by remember { mutableStateOf<Row?>(null) }

    // Autosave shortly after the last change, and right away when leaving the screen or the app.
    LaunchedEffect(editor.revision) {
        if (!editor.dirty) return@LaunchedEffect
        delay(800)
        withContext(Dispatchers.IO) { editor.saveIfDirty(app) }
    }
    fun saveNow() { if (editor.dirty) Thread { editor.saveIfDirty(app) }.start() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { saveNow() }
    DisposableEffect(editor) { onDispose { saveNow() } }

    val segmentMode = editMode || searching
    val rows = remember(t, segmentMode) { if (segmentMode) segmentRows(t) else paragraphRows(t) }
    val matches = remember(t.segments, query, searching) { if (searching) findMatches(t.segments, query) else emptyList() }
    var current by remember { mutableIntStateOf(0) }
    val currentMatch = matches.getOrNull(current.coerceIn(0, (matches.size - 1).coerceAtLeast(0)))

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = editor.transcript.asText()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                }.isSuccess
            }
            Toast.makeText(context, if (ok) "텍스트 파일로 저장했어요" else "저장하지 못했어요", Toast.LENGTH_SHORT).show()
        }
    }

    // The row being played (the last one started; it stays highlighted through pauses). Derived, so the list only
    // recomposes when it changes, not on every position tick.
    val listState = rememberLazyListState()
    val activeRow by remember(rows, player) {
        derivedStateOf { if (!player.isCurrent(item)) -1 else rows.indexOfLast { it.startMs <= player.positionMs } }
    }
    // Auto-scroll follows the playback until the user drags the list; "현재 위치로" turns it back on. Not while
    // correcting or searching, where it would pull the list away from what is being edited.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
    }
    val following = follow && !segmentMode
    LaunchedEffect(activeRow, following) {
        if (following && activeRow >= 0) listState.animateScrollToItem((activeRow - 1).coerceAtLeast(0))
    }
    // Search: bring the current match into view (segment rows: row index == segment index).
    LaunchedEffect(currentMatch?.segment, query) {
        currentMatch?.let { listState.animateScrollToItem((it.segment - 1).coerceAtLeast(0)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.meta.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
                actions = {
                    if (editMode || editor.undoCount > 0) IconButton(onClick = { editingSegment = -1; editor.undo() }, enabled = editor.undoCount > 0) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, "되돌리기")
                    }
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Outlined.Search, "찾아 바꾸기") }
                    IconButton(onClick = {
                        editMode = !editMode
                        editingSegment = -1
                        editor.endTextEdit()
                        if (!editMode) saveNow()
                    }) {
                        if (editMode) Icon(Icons.Filled.Check, "수정 끝내기") else Icon(Icons.Outlined.Edit, "수정")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "더보기") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("복사") }, onClick = { menu = false; copyTranscript(context, editor.transcript.asText()) })
                            DropdownMenuItem(text = { Text("공유") }, onClick = {
                                menu = false
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_SUBJECT, item.meta.title).putExtra(Intent.EXTRA_TEXT, editor.transcript.asText())
                                runCatching { context.startActivity(Intent.createChooser(send, "받아쓰기 공유")) }
                            })
                            DropdownMenuItem(text = { Text("텍스트 파일로 저장") }, onClick = { menu = false; saveLauncher.launch("${item.meta.title}.txt") })
                            DropdownMenuItem(text = { Text("자동 고치기 단어") }, onClick = { menu = false; showCorrections = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PlayerControls(item, player, Modifier.padding(horizontal = 8.dp))
            PlaybackVolumeSetting(Modifier.padding(start = 16.dp, end = 12.dp, bottom = 4.dp), compact = true)
            if (searching) SearchBar(
                query = query, onQuery = { query = it; current = 0 },
                count = matches.size, current = if (matches.isEmpty()) -1 else current.coerceIn(0, matches.size - 1),
                onPrev = { if (matches.isNotEmpty()) current = (current - 1 + matches.size) % matches.size },
                onNext = { if (matches.isNotEmpty()) current = (current + 1) % matches.size },
                onReplace = { to, keep ->
                    currentMatch?.let { editor.replace(it, to) }
                    if (keep) addCorrection(context, query, to)
                },
                onReplaceAll = { to, keep ->
                    val n = editor.replaceAll(query, to)
                    if (keep) addCorrection(context, query, to)
                    Toast.makeText(context, "${n}곳을 바꿨어요", Toast.LENGTH_SHORT).show()
                },
                onClose = { searching = false; query = "" },
            )
            HorizontalDivider()
            if (editMode) Text(
                when {
                    editor.dirty -> "문단을 눌러 고치세요 · 저장 중…"
                    t.editedAt > 0 -> "문단을 눌러 고치세요 · 저장됨"
                    else -> "문단을 눌러 고치세요 · 화자 이름을 누르면 이름·화자를 바꿀 수 있어요"
                },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)).padding(horizontal = 16.dp, vertical = 6.dp),
            )
            if (rows.isEmpty()) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("받아쓴 내용이 없어요 (말소리를 찾지 못했어요)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (segmentMode) 8.dp else 14.dp),
                ) {
                    itemsIndexed(rows, key = { i, _ -> i }) { i, row ->
                        val seg = row.segments.first()
                        RowView(
                            row = row,
                            names = t.speakerNames,
                            active = i == activeRow,
                            editing = editMode && editingSegment == seg,
                            editable = editMode,
                            highlights = if (searching) matches.filter { it.segment == seg } else emptyList(),
                            currentMatch = currentMatch?.takeIf { it.segment == seg },
                            onSeek = { follow = true; player.seek(item, row.startMs) },
                            onSpeaker = { speakerDialog = row },
                            onStartEdit = { editingSegment = seg; editor.beginTextEdit(seg) },
                            onText = { editor.setText(seg, it) },
                        )
                    }
                }
                if (!following && !segmentMode && activeRow >= 0) ExtendedFloatingActionButton(
                    onClick = { follow = true },
                    icon = { Icon(Icons.Outlined.MyLocation, null) },
                    text = { Text("현재 위치로") },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                )
            }
        }
    }

    speakerDialog?.let { row ->
        val speakers = remember(t.segments) { t.segments.mapNotNull { it.speaker }.distinct().sorted() }
        SpeakerDialog(
            row = row, names = t.speakerNames, speakers = speakers,
            onDismiss = { speakerDialog = null },
            onRename = { sp, name -> editor.renameSpeaker(sp, name); speakerDialog = null },
            onMove = { sp -> editor.setSpeakers(row.segments, sp); speakerDialog = null },
        )
    }
    if (showCorrections) CorrectionsDialog(
        onDismiss = { showCorrections = false },
        onApplyNow = {
            val n = editor.applyCorrections(Repository.get(context).data.value.stt.corrections)
            Toast.makeText(context, if (n > 0) "${n}개 문단을 고쳤어요 (되돌리기 가능)" else "고칠 곳이 없어요", Toast.LENGTH_SHORT).show()
            if (n > 0) editMode = true // shows 되돌리기
            showCorrections = false
        },
    )
}
