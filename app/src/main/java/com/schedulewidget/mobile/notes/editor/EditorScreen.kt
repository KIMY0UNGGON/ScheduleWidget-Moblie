package com.schedulewidget.mobile.notes.editor

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.notes.NoteStore
import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.notes.ink.StoredNote
import com.schedulewidget.mobile.notes.ui.NotesIconButton
import com.schedulewidget.mobile.notes.ui.NotesPill
import com.schedulewidget.mobile.notes.ui.NotesShapes
import com.schedulewidget.mobile.notes.ui.NotesSpace
import com.schedulewidget.mobile.notes.ui.NotesTokens
import com.schedulewidget.mobile.notes.ui.NotesTopBar
import com.schedulewidget.mobile.notes.ui.PillStyle
import com.schedulewidget.mobile.record.PlayerControls
import com.schedulewidget.mobile.record.Recorder
import com.schedulewidget.mobile.record.RecordingItem
import com.schedulewidget.mobile.record.Recordings
import com.schedulewidget.mobile.record.rememberRecordingPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Loads notebook [noteId] (waiting for a save still in flight) and shows the editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorLoader(
    noteId: String,
    onBack: () -> Unit,
    registerExitHandler: (((() -> Unit) -> Unit)?) -> Unit = {},
    registerStylusKeyHandler: (((android.view.KeyEvent) -> Boolean)?) -> Unit = {},
) {
    val context = LocalContext.current
    var loaded by remember(noteId) { mutableStateOf<Pair<StoredNote, Map<String, PageInk>>?>(null) }
    var missing by remember(noteId) { mutableStateOf(false) }
    var loadFailed by remember(noteId) { mutableStateOf(false) }
    LaunchedEffect(noteId) {
        try {
            NoteStore.pendingSaves[noteId]?.join()
            val r = withContext(Dispatchers.IO) {
                NoteStore.load(context, noteId)?.let { n -> n to n.pages.associate { it.uid to NoteStore.loadInk(context, noteId, it.uid) } }
            }
            if (r == null) missing = true else loaded = r
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            loadFailed = true
        }
    }
    val l = loaded
    if (l != null) {
        EditorContent(l.first, l.second, onBack, registerExitHandler, registerStylusKeyHandler)
        return
    }
    val c = NotesTokens.colors
    Column(Modifier.fillMaxSize().background(c.canvas).systemBarsPadding()) {
        NotesTopBar(Modifier.fillMaxWidth()) {
            NotesIconButton(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", onBack)
            Spacer(Modifier.width(NotesSpace.xxs))
            Text("노트", style = NotesTokens.type.title)
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(c.desk), contentAlignment = Alignment.Center) {
            when {
                missing -> Text("노트를 찾을 수 없어요", style = NotesTokens.type.bodySm.copy(color = c.muted))
                loadFailed -> Text(
                    "필기 파일을 읽을 수 없어 노트를 열지 못했어요. 원본 파일은 그대로 있습니다.",
                    style = NotesTokens.type.bodySm.copy(color = c.muted),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = NotesSpace.lg),
                )
                else -> CircularProgressIndicator(Modifier.size(28.dp), color = c.ink, trackColor = c.field, strokeWidth = 2.5.dp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun EditorContent(
    initial: StoredNote,
    ink: Map<String, PageInk>,
    onBack: () -> Unit,
    registerExitHandler: (((() -> Unit) -> Unit)?) -> Unit,
    registerStylusKeyHandler: (((android.view.KeyEvent) -> Boolean)?) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val st = remember { EditorState(context, initial, ink, density.density, scope) }
    val renderer = remember {
        PageRenderer(NoteStore.basePdf(context, initial.id), scope) // pages may be inserted from other PDFs later
    }
    val pageFocusRequester = remember { FocusRequester() }
    val decay = remember(density) { splineBasedDecay<Offset>(density) }
    val input = remember { InkInput(st, decay) }
    val settings by remember { Repository.get(context) }.data.collectAsStateWithLifecycle()
    st.fingerDraws = settings.notes.fingerDraws

    // Autosave on pause; final save, ink cleanup and thumbnail when the editor goes away.
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> { input.clearKeyHold(); st.saveNow() }
                Lifecycle.Event.ON_STOP -> st.saveNow(thumbnail = true) // the library may be shown next
                else -> {}
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            input.clearKeyHold()
            st.onSaveError = { toast(context.applicationContext, "노트를 저장하지 못했어요. 저장 공간을 확인한 뒤 다시 시도해 주세요") }
            st.saveNow(final = true)
            renderer.close()
        }
    }

    // Sharp PDF tiles once zooming / scrolling settles.
    LaunchedEffect(st) {
        snapshotFlow { listOf(st.scale, st.ox, st.oy, st.viewW, st.viewH, st.note.pages) }.collectLatest {
            delay(160)
            renderer.updateTiles(st.tileRequests(renderer))
        }
    }

    // Recording link playback.
    val player = rememberRecordingPlayer()
    var playing by remember { mutableStateOf<RecordingItem?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    LaunchedEffect(st) {
        st.onMessage = { toast(context, it) }
        st.onSaveError = { saveFailed = true }
        st.onPlayLink = { recId, ms ->
            scope.launch {
                val rs = Recorder.state.value
                if (rs.isRecording && rs.id == recId) { toast(context, "녹음을 멈춘 뒤에 재생할 수 있어요"); return@launch }
                val item = withContext(Dispatchers.IO) { Recordings.list(context).find { it.id == recId } }
                if (item == null) toast(context, "연결된 녹음을 찾을 수 없어요")
                else { playing = item; player.seek(item, ms) }
            }
        }
    }

    var showPages by remember { mutableStateOf(false) }
    var templateFor by remember { mutableStateOf<Int?>(null) }
    var pageManagerInputReady by remember { mutableStateOf(true) }
    var exporting by remember { mutableStateOf(false) }
    // The title shown / exported; a rename here is written to the store's meta (the editor only saves pages).
    var title by remember { mutableStateOf(st.note.title) }
    var renaming by remember { mutableStateOf(false) }
    // 전체 화면: the top bar is hidden (the tool strip stays); back leaves this mode first.
    var immersive by remember { mutableStateOf(false) }
    val editorInputReady = rememberUpdatedState(
        pageManagerInputReady && !showPages && templateFor == null && st.textEdit == null &&
            !renaming && !exporting && !saving,
    )
    val requestPageFocus = rememberUpdatedState<() -> Unit> {
        if (editorInputReady.value) runCatching { pageFocusRequester.requestFocus() }
    }
    LaunchedEffect(editorInputReady.value) {
        if (editorInputReady.value) requestPageFocus.value() else input.clearKeyHold()
    }
    DisposableEffect(registerStylusKeyHandler) {
        registerStylusKeyHandler { event ->
            val isKeyUp = event.action == android.view.KeyEvent.ACTION_UP
            if (editorInputReady.value || isKeyUp) input.onNativeKeyEvent(event) else false
        }
        onDispose {
            input.clearKeyHold()
            registerStylusKeyHandler(null)
        }
    }
    suspend fun savePending(final: Boolean): Boolean {
        if (saving) return false
        saving = true
        st.saveInProgress = true
        return try {
            st.saveNow(final = final, reportError = false)?.await()
            saveFailed = false
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            saveFailed = true
            false
        } finally {
            st.saveInProgress = false
            saving = false
        }
    }
    var exitRequestInFlight by remember { mutableStateOf(false) }
    val requestExit = rememberUpdatedState<((() -> Unit) -> Unit)> { afterSave ->
        if (!exitRequestInFlight) {
            exitRequestInFlight = true
            scope.launch {
                while (saving) delay(16)
                val saved = savePending(final = true)
                exitRequestInFlight = false
                if (saved) afterSave()
            }
        }
    }
    DisposableEffect(registerExitHandler) {
        registerExitHandler { afterSave -> requestExit.value(afterSave) }
        onDispose { registerExitHandler(null) }
    }
    val saveAndLeave = {
        if (!saving) scope.launch { if (savePending(final = true)) onBack() }
    }
    BackHandler {
        if (immersive) immersive = false else saveAndLeave()
    }
    val customPen = remember { mutableStateListOf<Int>() }
    val customHl = remember { mutableStateListOf<Int>() }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            exporting = true
            if (savePending(final = false)) {
                runCatching { NoteStore.exportPdf(context, st.noteId, uri) }
                    .onSuccess { toast(context, "PDF로 저장했어요") }
                    .onFailure { toast(context, "PDF를 저장하지 못했어요: ${it.message ?: it.javaClass.simpleName}") }
            }
            exporting = false
        }
    }
    val share: () -> Unit = {
        scope.launch {
            exporting = true
            if (!savePending(final = false)) { exporting = false; return@launch }
            runCatching {
                val dir = File(context.cacheDir, "notes-share").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val f = File(dir, safeFileName(title) + ".pdf")
                NoteStore.exportPdf(context, st.noteId, Uri.fromFile(f))
                FileProvider.getUriForFile(context, context.packageName + ".files", f)
            }.onSuccess { uri ->
                val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, title)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try { context.startActivity(Intent.createChooser(send, "PDF 공유")) } catch (_: ActivityNotFoundException) {}
            }.onFailure { toast(context, "PDF를 공유하지 못했어요: ${it.message ?: it.javaClass.simpleName}") }
            exporting = false
        }
    }

    val nc = NotesTokens.colors
    val setFingerDraws: (Boolean) -> Unit = { on ->
        Repository.get(context).update { it.copy(notes = it.notes.copy(fingerDraws = on)) }
    }
    Column(Modifier.fillMaxSize().background(nc.canvas).systemBarsPadding()) {
        if (!immersive) {
            EditorTopBar(
                st, title = title, sttEnabled = settings.stt.enabled, onBack = saveAndLeave,
                onRename = { renaming = true },
                onPages = { showPages = true },
                onAddPage = { templateFor = st.currentPage },
                onShare = share,
                onSaveAs = { saveLauncher.launch(safeFileName(title) + ".pdf") },
                fingerDraws = settings.notes.fingerDraws,
                onFingerDraws = setFingerDraws,
                onImmersive = { immersive = true },
            )
        }
        if (saveFailed) Row(
            Modifier.fillMaxWidth().background(nc.field).padding(horizontal = NotesSpace.md, vertical = NotesSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "저장하지 못했어요. 다시 시도해 주세요.",
                style = NotesTokens.type.bodySm.copy(color = nc.ink), modifier = Modifier.weight(1f),
            )
            NotesPill("다시 시도", onClick = { scope.launch { savePending(final = false) } }, style = PillStyle.Soft, small = true)
        }
        Column(Modifier.weight(1f).fillMaxWidth()) {
            ToolStrip(st, customPen, customHl)
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().clipToBounds()
                    .onSizeChanged { st.setViewport(it.width.toFloat(), it.height.toFloat()) }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull() ?: continue
                                if (change.type != PointerType.Stylus && change.type != PointerType.Eraser) continue
                                when (event.type) {
                                    PointerEventType.Enter, PointerEventType.Move -> if (!change.pressed) input.hover(true, change.uptimeMillis)
                                    PointerEventType.Exit -> input.hover(false, change.uptimeMillis)
                                }
                            }
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        val isKeyUp = event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP
                        if (editorInputReady.value || isKeyUp) input.onKeyEvent(event) else false
                    }
                    .focusRequester(pageFocusRequester)
                    .focusable()
                    .pointerInteropFilter { input.onTouch(it) }
            ) {
                val pageAreaWidthPx = constraints.maxWidth
                Box(Modifier.fillMaxSize().graphicsLayer { translationY = st.endPullOffsetPx }) {
                    EditorCanvas(st, renderer, nc.desk.toArgb(), pageAreaWidthPx)
                    if (st.linkMode) {
                        Row(
                            Modifier.align(Alignment.TopCenter).padding(NotesSpace.xs)
                                .clip(NotesShapes.full).background(nc.canvas).border(1.dp, nc.hairline, NotesShapes.full)
                                .padding(start = NotesSpace.sm, end = NotesSpace.xxs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Link, null, Modifier.size(16.dp), tint = nc.ink)
                            Spacer(Modifier.width(NotesSpace.xs))
                            Text("필기를 탭하면 그때의 녹음을 재생해요", style = NotesTokens.type.label)
                            NotesIconButton(Icons.Filled.Close, "끄기", { st.inputToolOverride = null; st.linkMode = false }, size = 32.dp)
                        }
                    }
                    if (immersive) {
                        FloatingPill(Modifier.align(Alignment.TopEnd).padding(NotesSpace.xs), onClick = { immersive = false }) {
                            Icon(Icons.Filled.FullscreenExit, "전체 화면 끝내기", Modifier.size(20.dp), tint = nc.ink)
                        }
                    }
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = NotesSpace.sm, vertical = NotesSpace.sm)) {
                            PageCounter(st, Modifier.align(Alignment.Center)) { showPages = true }
                            InputModePill(
                                fingerDraws = settings.notes.fingerDraws, modifier = Modifier.align(Alignment.CenterEnd),
                                onToggle = { setFingerDraws(!settings.notes.fingerDraws) },
                            )
                        }
                        playing?.let { item ->
                            Column(
                                Modifier.fillMaxWidth().padding(start = NotesSpace.xs, end = NotesSpace.xs, bottom = NotesSpace.xs)
                                    .clip(NotesShapes.md).background(nc.canvas).border(1.dp, nc.hairline, NotesShapes.md)
                                    .padding(start = NotesSpace.md, end = NotesSpace.xxs, top = NotesSpace.xxs, bottom = NotesSpace.xxs),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.meta.title, style = NotesTokens.type.label.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    NotesIconButton(Icons.Filled.Close, "닫기", { player.release(); playing = null }, size = 36.dp)
                                }
                                PlayerControls(item, player)
                            }
                        }
                    }
                }
            }
        }
    }

    PageManager(
        st, renderer, showPages,
        onDismissPages = { showPages = false },
        insertAfter = templateFor,
        onInsertDismiss = { templateFor = null },
        onEditorInputReadyChanged = { pageManagerInputReady = it },
    )
    st.textEdit?.let { edit ->
        TextEditDialog(edit, onDone = { text, size -> st.applyText(edit, text, size) }, onDismiss = { st.textEdit = null })
    }
    if (renaming) {
        RenameDialog(
            initial = title,
            onDone = { name ->
                renaming = false
                if (name != title) {
                    title = name
                    NoteStore.scope.launch { NoteStore.rename(context.applicationContext, st.noteId, name) }
                }
            },
            onDismiss = { renaming = false },
        )
    }
    if (exporting || saving) ExportProgressDialog(
        title = if (saving) "저장하는 중…" else "PDF 만드는 중…",
        message = if (saving) "편집 내용을 저장하고 있어요" else "페이지가 많으면 시간이 걸려요",
    )
}

/** Clean bar on canvas: back, title (tap to rename), then undo / redo, record, pages and the ⋮ menu as icon pills. */
