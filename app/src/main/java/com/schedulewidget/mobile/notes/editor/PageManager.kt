package com.schedulewidget.mobile.notes.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.schedulewidget.mobile.notes.NoteStore
import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.ink.PageInk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where "다른 파일에서 삽입" takes pages from. */
internal enum class InsertSource { PDF, IMAGES, CAMERA, NOTEBOOK, OFFICE }

/** What the pages sheet asks the manager to do with a set of pages (indices into the current page list). */
internal interface PageActions {
    fun insert(anchor: Int)
    fun export(indices: List<Int>)
    fun copyToNotebook(indices: List<Int>)
    fun changeBackground(indices: List<Int>)
}

/**
 * Page management of the editor: hosts the pages sheet, the "페이지 추가" sheet, the file pickers (kept alive while a
 * picker is open), the conversions with their progress, and the dialogs that follow (page range, notebook / page
 * choice, copy to another notebook, background). [insertAfter] != null opens the add sheet after that page.
 */
@Composable
internal fun PageManager(
    st: EditorState,
    renderer: PageRenderer,
    showPages: Boolean,
    onDismissPages: () -> Unit,
    insertAfter: Int?,
    onInsertDismiss: () -> Unit,
    onEditorInputReadyChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var insertAnchor by remember { mutableStateOf<Int?>(null) }
    var pendingAt by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var busyJob by remember { mutableStateOf<Job?>(null) }
    var ready by remember { mutableStateOf<Pair<PageImport.Ready, Int>?>(null) }
    var notebookPickAt by remember { mutableStateOf<Int?>(null) }
    var copyPages by remember { mutableStateOf<List<Int>?>(null) }
    var backgroundPages by remember { mutableStateOf<List<Int>?>(null) }
    var exportPages by rememberSaveable { mutableStateOf<IntArray?>(null) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }

    val editorInputReady = !showPages && insertAfter == null && insertAnchor == null && ready == null && notebookPickAt == null &&
        copyPages == null && backgroundPages == null && exportPages == null && cameraPath == null && busy == null
    LaunchedEffect(showPages, insertAfter, insertAnchor, ready, notebookPickAt, copyPages, backgroundPages, exportPages, cameraPath, busy) {
        onEditorInputReadyChanged(editorInputReady)
    }

    fun run(stage: String, block: suspend ((String) -> Unit) -> Unit) {
        busy = stage
        busyJob = scope.launch {
            try {
                block { s -> scope.launch(Dispatchers.Main.immediate) { if (busy != null) busy = s } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast(context, e.message ?: "넣지 못했어요")
            } finally {
                busy = null
                busyJob = null
            }
        }
    }

    fun insertReady(r: PageImport.Ready, at: Int) {
        if (r.sizes.size <= 1) {
            st.insertPages(at, PageImport.pages(r, r.sizes.indices.toList()))
            toast(context, "페이지를 넣었어요")
        } else {
            ready = r to at
        }
    }

    fun inkSnapshot(pages: List<PageInfo>): Map<String, PageInk> =
        pages.associate { it.uid to st.inkOf(it.uid) }

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val at = pendingAt
            run("파일 읽는 중") { stage -> insertReady(PageImport.prepare(context, st.noteId, uri, stage), at) }
        }
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            val at = pendingAt
            run("사진 읽는 중") { stage ->
                val r = PageImport.prepareImages(context, st.noteId, uris, stage)
                st.insertPages(at, PageImport.pages(r, r.sizes.indices.toList()))
                toast(context, "사진 ${r.sizes.size}장을 페이지로 넣었어요")
            }
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraPath?.let(::File)
        cameraPath = null
        if (ok && f != null && f.length() > 0) {
            val at = pendingAt
            run("사진 읽는 중") { stage ->
                try {
                    val r = PageImport.prepareImages(context, st.noteId, listOf(Uri.fromFile(f)), stage)
                    st.insertPages(at, PageImport.pages(r, r.sizes.indices.toList()))
                    toast(context, "사진을 페이지로 넣었어요")
                } finally {
                    withContext(Dispatchers.IO) { f.delete() }
                }
            }
        } else {
            f?.delete()
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val indices = exportPages?.toList()
        exportPages = null
        if (uri != null && indices != null) {
            val pages = indices.mapNotNull { st.note.pages.getOrNull(it) }
            val inks = inkSnapshot(pages)
            run("PDF 만드는 중") {
                NoteStore.exportPages(context, st.noteId, pages, { inks[it.uid] ?: PageInk.EMPTY }, uri)
                toast(context, "${pages.size}쪽을 PDF로 저장했어요")
            }
        }
    }

    fun pick(source: InsertSource, at: Int) {
        pendingAt = at
        insertAnchor = null
        onInsertDismiss()
        runCatching {
            when (source) {
                InsertSource.PDF -> pdfLauncher.launch(arrayOf("application/pdf"))
                InsertSource.OFFICE -> pdfLauncher.launch(OFFICE_MIMES)
                InsertSource.IMAGES -> imageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                InsertSource.CAMERA -> {
                    val dir = File(context.cacheDir, "notes-camera").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val f = File(dir, "page-${System.currentTimeMillis()}.jpg")
                    cameraPath = f.path
                    cameraLauncher.launch(FileProvider.getUriForFile(context, context.packageName + ".files", f))
                }
                InsertSource.NOTEBOOK -> notebookPickAt = at
            }
        }.onFailure { toast(context, "파일 선택 화면을 열 수 없어요") }
    }

    val actions = remember(st) {
        object : PageActions {
            override fun insert(anchor: Int) { insertAnchor = anchor }
            override fun export(indices: List<Int>) {
                if (indices.isEmpty()) return
                exportPages = indices.toIntArray()
                val name = safeName(st.note.title) + if (indices.size < st.note.pages.size) " (${indices.size}쪽)" else ""
                runCatching { exportLauncher.launch("$name.pdf") }.onFailure { exportPages = null }
            }
            override fun copyToNotebook(indices: List<Int>) { if (indices.isNotEmpty()) copyPages = indices }
            override fun changeBackground(indices: List<Int>) { if (indices.isNotEmpty()) backgroundPages = indices }
        }
    }

    if (showPages) PagesSheet(st, renderer, actions, onDismiss = onDismissPages)

    val anchor = insertAnchor ?: insertAfter
    if (anchor != null) {
        PageInsertSheet(
            st, anchor,
            onBlank = { at, template, size, paper ->
                insertAnchor = null; onInsertDismiss()
                st.insertBlank(at, template, size, paper, ref = anchor)
            },
            onSource = { source, at -> pick(source, at) },
            onDismiss = { insertAnchor = null; onInsertDismiss() },
        )
    }

    ready?.let { (r, at) ->
        PageRangeDialog(
            title = r.title, count = r.sizes.size,
            onInsert = { indices ->
                ready = null
                st.insertPages(at, PageImport.pages(r, indices))
                toast(context, "${indices.size}쪽을 넣었어요")
            },
            onDismiss = { ready = null },
        )
    }

    notebookPickAt?.let { at ->
        NotebookPagePicker(
            excludeId = st.noteId,
            onPick = { fromId, indices ->
                notebookPickAt = null
                run("페이지 가져오는 중") {
                    val pages = NoteStore.pagesFrom(context, fromId, st.noteId, indices)
                    st.insertPages(at, pages)
                    toast(context, "${pages.size}쪽을 넣었어요")
                }
            },
            onDismiss = { notebookPickAt = null },
        )
    }

    copyPages?.let { indices ->
        CopyToNotebookSheet(
            excludeId = st.noteId, count = indices.size, defaultTitle = st.note.title + " (발췌)",
            onCopy = { toId, title ->
                copyPages = null
                val pages = indices.mapNotNull { st.note.pages.getOrNull(it) }
                val inks = inkSnapshot(pages)
                run("페이지 복사 중") {
                    val meta = if (toId != null) NoteStore.copyPagesTo(context, st.noteId, pages, { inks[it.uid] ?: PageInk.EMPTY }, toId)
                    else NoteStore.extractToNew(context, st.noteId, pages, { inks[it.uid] ?: PageInk.EMPTY }, title, st.note.folder)
                    toast(context, if (toId != null) "'${meta.title}'에 ${pages.size}쪽을 복사했어요" else "새 노트 '${meta.title}'를 만들었어요")
                }
            },
            onDismiss = { copyPages = null },
        )
    }

    backgroundPages?.let { indices ->
        BackgroundDialog(
            st, indices,
            onApply = { template, paper ->
                backgroundPages = null
                val n = st.setBackground(indices, template, paper, setPaper = true)
                if (n > 0 && n < indices.size) toast(context, "빈 페이지 ${n}쪽만 바꿨어요 (PDF 페이지는 그대로예요)")
            },
            onDismiss = { backgroundPages = null },
        )
    }

    busy?.let { stage -> BusyDialog(stage, onCancel = { busyJob?.cancel() }) }
}

private val OFFICE_MIMES = arrayOf(
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-powerpoint",
    "application/msword",
)

private fun safeName(title: String): String =
    title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().ifEmpty { "노트" }.take(80)
