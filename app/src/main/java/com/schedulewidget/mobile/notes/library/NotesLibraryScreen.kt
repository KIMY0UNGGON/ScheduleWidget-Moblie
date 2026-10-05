package com.schedulewidget.mobile.notes

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
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
import com.schedulewidget.mobile.notes.importer.FileKind
import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.library.FlexcilGuideDialog
import com.schedulewidget.mobile.notes.library.ImportReportDialog
import com.schedulewidget.mobile.notes.library.NoteFolders
import com.schedulewidget.mobile.notes.library.NotesDialog
import com.schedulewidget.mobile.notes.library.NotesSnackbarHost
import com.schedulewidget.mobile.notes.library.shareDiagnostic
import com.schedulewidget.mobile.notes.ui.NotesPill
import com.schedulewidget.mobile.notes.ui.NotesSpace
import com.schedulewidget.mobile.notes.ui.NotesTheme
import com.schedulewidget.mobile.notes.ui.NotesTokens
import com.schedulewidget.mobile.notes.ui.PillStyle
import com.schedulewidget.mobile.pet.DrivePets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

private const val SORT_RECENT = "recent"
private const val SORT_NAME = "name"
internal val SORTS = listOf(SORT_RECENT to "최근 수정", SORT_NAME to "이름")

/** Side gutter of the library page (the grid, header and chips line up on it). */
internal val Gutter = 20.dp

/** The 노트 tab: notebooks list, import, new blank note. [onOpen] opens a notebook in the editor. */
@Composable
fun NotesLibraryScreen(onOpen: (String) -> Unit) {
    NotesTheme { LibraryContent(onOpen) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryContent(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val c = NotesTokens.colors
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val version by NoteStore.version.collectAsStateWithLifecycle()
    val folderChanges by NoteFolders.changes.collectAsStateWithLifecycle()
    val notes by produceState<List<NoteMeta>?>(null, version) {
        value = withContext(Dispatchers.IO) { runCatching { NoteStore.list(context) }.getOrDefault(emptyList()) }
    }
    val all = notes.orEmpty()
    val folders = remember(all, folderChanges) { NoteFolders.all(context, all) }

    // null = 전체 (all notebooks); new notes and imports go into the selected folder.
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    if (folder != null && notes != null && folder !in folders) folder = null
    var sort by rememberSaveable { mutableStateOf(SORT_RECENT) }
    var query by rememberSaveable { mutableStateOf("") }

    val shown = remember(all, folder, sort, query) {
        val q = query.trim()
        val base = if (q.isNotEmpty()) all.filter { it.title.contains(q, ignoreCase = true) }
        else all.filter { folder == null || it.folder == folder }
        when (sort) {
            SORT_NAME -> Collator.getInstance(Locale.KOREAN).let { c -> base.sortedWith { a, b -> c.compare(a.title, b.title) } }
            else -> base.sortedByDescending { it.updatedAt }
        }
    }

    // ---- import ----
    val importState by NoteImport.state.collectAsStateWithLifecycle()
    val done by NoteImport.done.collectAsStateWithLifecycle()
    val consent by NoteImport.consent.collectAsStateWithLifecycle()
    // Several files may be picked; they are imported one after another.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val convert = NoteImport.Convert.of(Repository.get(context).data.value.notes.convert)
        val started = if (uris.size == 1) NoteImport.start(context, uris[0], convert, folder, open = false)
        else NoteImport.startBatch(context, uris, convert, folder, open = false)
        if (!started) scope.launch { snackbar.showSnackbar("이미 가져오는 중이에요") }
    }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        val convert = NoteImport.Convert.of(Repository.get(context).data.value.notes.convert)
        if (!NoteImport.startTree(context, tree, convert, folder)) scope.launch { snackbar.showSnackbar("이미 가져오는 중이에요") }
    }
    val pickFiles = { runCatching { pick.launch(FileKind.PICKER_MIMES + "application/octet-stream") }; Unit }
    var flexcilGuide by rememberSaveable { mutableStateOf(false) }
    var report by remember { mutableStateOf<NoteImport.Done?>(null) }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val activity = context.findActivity()
        val token = if (r.resultCode == Activity.RESULT_OK && activity != null) {
            (DrivePets.tokenFrom(activity, r.data) as? DrivePets.Auth.Token)?.value
        } else null
        NoteImport.consentResult(token)
    }
    LaunchedEffect(consent) {
        val request = consent ?: return@LaunchedEffect
        NoteImport.consentLaunched()
        runCatching { consentLauncher.launch(request) }.onFailure { NoteImport.consentResult(null) }
    }
    LaunchedEffect(done) {
        val d = done ?: return@LaunchedEffect
        NoteImport.consumeDone()
        if (d.details != null || d.diagnostic != null) {
            report = d
        } else if (d.open) {
            onOpen(d.note.id)
        } else {
            val r = snackbar.showSnackbar(d.message, actionLabel = "열기", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) onOpen(d.note.id)
        }
    }

    // ---- export ----
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val id = exportId
        exportId = null
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            launch { snackbar.showSnackbar("PDF로 내보내는 중…", duration = SnackbarDuration.Indefinite) }
            val ok = runCatching { NoteStore.exportPdf(context.applicationContext, id, uri) }.isSuccess
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(if (ok) "PDF로 내보냈어요" else "PDF로 내보내지 못했어요")
        }
    }

    // ---- dialogs ----
    var addSheet by remember { mutableStateOf(false) }
    var newNote by rememberSaveable { mutableStateOf(false) }
    var newFolder by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<NoteMeta?>(null) }
    var moving by remember { mutableStateOf<NoteMeta?>(null) }
    var renamingFolder by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = c.canvas,
        contentColor = c.ink,
        floatingActionButton = {
            // Flat ink pill instead of a Material FAB (no shadow); opens the add sheet.
            NotesPill("새 노트", onClick = { addSheet = true }, icon = Icons.Filled.Add)
        },
        snackbarHost = { NotesSnackbarHost(snackbar) },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(156.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Gutter, end = Gutter, top = NotesSpace.md, bottom = 112.dp),
            horizontalArrangement = Arrangement.spacedBy(NotesSpace.md),
            verticalArrangement = Arrangement.spacedBy(NotesSpace.lg),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                LibraryHeader(
                    count = when {
                        notes == null -> "불러오는 중…"
                        query.isNotBlank() -> "검색 결과 ${shown.size}개"
                        folder != null -> "$folder · 노트 ${shown.size}개"
                        else -> "노트 ${all.size}개"
                    },
                    query = query, onQuery = { query = it },
                    sort = sort, onSort = { sort = it },
                    running = importState as? NoteImport.State.Running,
                    showFolders = query.isBlank(),
                    folders = folders, folder = folder, onFolder = { folder = it },
                    onNewFolder = { newFolder = true },
                    onRenameFolder = { renamingFolder = it },
                    onRemoveFolder = { f ->
                        if (folder == f) folder = null
                        NoteStore.scope.launch { NoteFolders.remove(context, f) }
                    },
                )
            }
            if (notes != null && shown.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    when {
                        query.isNotBlank() -> EmptyState(
                            "검색 결과가 없어요", "\"${query.trim()}\" 제목의 노트가 없어요.",
                            action = "검색 지우기", primary = false, onAction = { query = "" },
                        )
                        folder != null -> EmptyState(
                            "이 폴더는 비어 있어요", "노트를 길게 눌러 \"폴더로 이동\"으로 옮기거나, 여기서 새 노트를 만들 수 있어요.",
                            action = "새 노트", onAction = { addSheet = true },
                        )
                        else -> EmptyState(
                            "아직 노트가 없어요", "PDF·PPT·Word·Flexcil 파일을 가져오거나 새 노트를 만들어 보세요.",
                            action = "새 노트", onAction = { addSheet = true },
                        )
                    }
                }
            }
            items(shown, key = { it.id }) { note ->
                NotebookCard(
                    note = note, version = version,
                    onOpen = { onOpen(note.id) },
                    onRename = { renaming = note },
                    onMove = { moving = note },
                    onExport = {
                        exportId = note.id
                        runCatching { exportLauncher.launch(safeFileName(note.title) + ".pdf") }.onFailure { exportId = null }
                    },
                    onDelete = {
                        scope.launch {
                            val deleted = runCatching { withContext(Dispatchers.IO) { NoteStore.delete(context, note.id) } }.isSuccess
                            if (!deleted) snackbar.showSnackbar("노트를 삭제하지 못했어요")
                        }
                    },
                )
            }
        }
    }

    if (addSheet) AddSheet(
        onDismiss = { addSheet = false },
        onNewNote = { newNote = true },
        onPickFiles = pickFiles,
        onFlexcil = { flexcilGuide = true },
        onPickTree = { runCatching { pickTree.launch(null) } },
    )
    (importState as? NoteImport.State.Failed)?.let { failed ->
        NotesDialog(
            onDismissRequest = { NoteImport.dismissFailure() },
            title = "가져오지 못했어요",
            confirmButton = { NotesPill("확인", onClick = { NoteImport.dismissFailure() }, small = true) },
            dismissButton = if (failed.diagnostic != null) {
                { NotesPill("진단 정보 공유", onClick = { shareDiagnostic(context, failed.diagnostic) }, style = PillStyle.Soft, small = true) }
            } else null,
        ) {
            Text(failed.message, style = NotesTokens.type.body.copy(color = c.muted), modifier = Modifier.verticalScroll(rememberScrollState()))
        }
    }
    if (flexcilGuide) FlexcilGuideDialog(
        onDismiss = { flexcilGuide = false },
        // Flexcil's files have no registered type on most phones, so show everything; the import checks the content.
        onPickFile = { runCatching { pick.launch(arrayOf("*/*")) } },
    )
    report?.let { d ->
        ImportReportDialog(d, onOpen = { onOpen(d.note.id) }, onDismiss = { report = null })
    }
    if (newNote) NewNoteDialog(
        onDismiss = { newNote = false },
        onCreate = { title, template, landscape ->
            newNote = false
            val target = folder
            scope.launch {
                val meta = withContext(Dispatchers.IO) { runCatching { createBlank(context, title, template, landscape, target) }.getOrNull() }
                if (meta != null) onOpen(meta.id) else snackbar.showSnackbar("노트를 만들지 못했어요")
            }
        },
    )
    if (newFolder) NameDialog(
        title = "새 폴더", initial = "", confirm = "만들기", placeholder = "폴더 이름",
        onDismiss = { newFolder = false },
        onDone = { name -> newFolder = false; NoteFolders.add(context, name); folder = name },
    )
    renamingFolder?.let { f ->
        NameDialog(
            title = "폴더 이름 바꾸기", initial = f, confirm = "바꾸기", placeholder = "폴더 이름",
            onDismiss = { renamingFolder = null },
            onDone = { name ->
                renamingFolder = null
                if (name != f) NoteStore.scope.launch { NoteFolders.rename(context, f, name) }
                if (folder == f) folder = name
            },
        )
    }
    renaming?.let { note ->
        NameDialog(
            title = "이름 바꾸기", initial = note.title, confirm = "바꾸기", placeholder = "노트 제목",
            onDismiss = { renaming = null },
            onDone = { name -> renaming = null; scope.launch(Dispatchers.IO) { NoteStore.rename(context, note.id, name) } },
        )
    }
    moving?.let { note ->
        MoveDialog(
            note = note, folders = folders,
            onDismiss = { moving = null },
            onMove = { target ->
                moving = null
                if (target != null) NoteFolders.add(context, target)
                scope.launch(Dispatchers.IO) { NoteStore.move(context, note.id, target) }
            },
        )
    }
}

/** Blank notebook; landscape pages are the A4 page turned (the store makes portrait A4 pages). */
private fun createBlank(context: Context, title: String, template: String, landscape: Boolean, folder: String?): NoteMeta {
    val meta = NoteStore.createBlank(context, title, template, 1, folder)
    if (landscape) {
        NoteStore.load(context, meta.id)?.let { stored ->
            NoteStore.savePages(context, meta.id, stored.pages.map { it.copy(w = PageInfo.A4_H, h = PageInfo.A4_W) })
            NoteStore.requestThumbnail(context, meta.id)
        }
    }
    return meta
}

private fun safeFileName(title: String) = title.filterNot { it in "\\/:*?\"<>|" || it.code < 32 }.trim().ifBlank { "노트" }

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
