package com.schedulewidget.mobile.notes

import android.content.Context
import android.os.Build
import com.schedulewidget.mobile.notes.importer.FlexcilArchive
import com.schedulewidget.mobile.notes.importer.FlexcilDocument
import com.schedulewidget.mobile.notes.importer.FlexcilInk
import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.notes.library.NoteFolders
import com.schedulewidget.mobile.notes.render.PdfDoc
import com.schedulewidget.mobile.notes.render.PdfThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException

/** Native backup restore: PDFs stay backgrounds, while page order and handwriting become editable note data. */
internal object NoteFlexcilImport {
    class Result(
        val notes: List<NoteMeta>, val message: String, val details: String?, val diagnostic: File? = null,
        val cancelled: Boolean = false,
        /** The backup's own library folder (holds every restored notebook); null for a single .flx document. */
        val importedFolder: String? = null,
    )

    suspend fun restore(
        app: Context, input: File, name: String?, folder: String?, work: File, stage: (String) -> Unit,
    ): Result {
        stage(".flex/.flx 원본 노트 복원 중")
        val job = currentCoroutineContext().job
        // A backup gets one folder named after the file; its notebooks keep their original subfolders below it.
        val root = if (FlexcilArchive.isBackup(input, name)) backupFolder(app, name, folder) else null
        val target: (FlexcilDocument) -> String? = { book ->
            if (root == null) book.folder ?: folder else listOfNotNull(root, book.folder).joinToString("/")
        }
        val notes = ArrayList<NoteMeta>()
        var strokes = 0
        var originals = 0
        var nextId = System.currentTimeMillis() * 1000 - 1_000_000_000L
        fun originalNotice() = when {
            notes.isNotEmpty() && originals == notes.size -> "원본 .flx 파일도 노트 안에 보관했습니다."
            originals > 0 -> "원본 .flx 파일은 해당 노트 안에 보관했습니다."
            else -> "선택한 원본 백업 파일은 그대로 있습니다."
        }
        val result = try {
            FlexcilArchive.restoreCancellable(input, name, work, progress = stage, checkActive = { job.ensureActive() }) { book ->
                job.ensureActive()
                val restored = runBlocking { restoreBook(app, book, target(book), nextId) }
                // Registered only once something was saved, so a failed backup leaves no empty folder behind.
                if (notes.isEmpty() && root != null) NoteFolders.add(app, root)
                notes += restored.first
                nextId += restored.second
                strokes += restored.second
                if (book.original != null) originals++
            }.also { job.ensureActive() }
        } catch (e: CancellationException) {
            if (notes.isEmpty()) throw e
            return Result(notes, "노트 ${notes.size}개를 복원했어요",
                "가져오기를 취소했어요. 먼저 복원한 노트 ${notes.size}개는 저장했습니다. ${originalNotice()}", cancelled = true,
                importedFolder = root)
        } catch (e: FlexcilArchive.FlexcilError) {
            val diagnostic = diagnostic(app, e.report)
            if (notes.isEmpty()) throw NoteImport.ImportError(".flex/.flx 노트를 복원하지 못했어요: ${e.message}", diagnostic)
            return Result(notes, "노트 ${notes.size}개를 복원했어요", "일부 노트를 읽지 못했어요: ${e.message}", diagnostic,
                importedFolder = root)
        }
        if (notes.isEmpty()) throw NoteImport.ImportError(
            when {
                result.passwordFailures > 0 && result.failedDocs == result.passwordFailures -> "암호가 걸린 PDF 배경이 있어 노트를 복원하지 못했어요"
                result.failedDocs > 0 -> "노트의 페이지나 PDF 배경을 읽지 못했어요"
                else -> "복원할 노트를 찾지 못했어요"
            },
            diagnostic(app, result.report),
        )
        val details = buildList {
            if (result.inkLost > 0) add("지원하지 않는 필기·도형 ${result.inkLost}개가 복원되지 않았어요. ${originalNotice()}")
            if (result.imagePagesLost > 0) add("이미지 데이터가 포함된 페이지 ${result.imagePagesLost}쪽은 이미지를 복원하지 못했어요. ${originalNotice()}")
            if (result.passwordFailures > 0) add("암호가 걸린 PDF 배경이 있는 노트 ${result.passwordFailures}개는 건너뛰었어요.")
            val otherFailures = result.failedDocs - result.passwordFailures
            if (otherFailures > 0) add("노트 ${otherFailures}개는 읽지 못했어요. 원본 백업 파일은 그대로 있습니다.")
        }.joinToString("\n").ifBlank { null }
        return Result(notes, "노트 ${notes.size}개를 복원했어요" + if (strokes > 0) " · 필기 ${strokes}개" else "",
            details, details?.let { diagnostic(app, result.report) }, importedFolder = root)
    }

    /** "<selected folder>/<backup file name>", made unique among existing folders (implied parents included). */
    private fun backupFolder(app: Context, name: String?, parent: String?): String {
        val base = name?.substringAfterLast('/')?.substringAfterLast('\\')?.let(FlexcilArchive::stripExt)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: "가져온 백업"
        val candidate = parent?.takeIf { it.isNotBlank() }?.let { "$it/$base" } ?: base
        return NoteFolders.uniquePath(candidate, NoteFolders.all(app, NoteStore.list(app)))
    }

    private suspend fun restoreBook(app: Context, book: FlexcilDocument, folder: String?, firstId: Long): Pair<NoteMeta, Int> {
        val sizes = try {
            runBlocking(PdfThread.dispatcher) {
                book.pdfs.mapValues { (_, file) -> PdfDoc(file).use { it.pageSizes() } }
            }
        } catch (e: SecurityException) {
            throw FlexcilArchive.PasswordProtectedPdf(e)
        }
        val sourceNames = book.pdfs.keys.mapIndexed { i, key -> key to if (i == 0) "base.pdf" else "src-flex-$i.pdf" }.toMap()
        val sources = book.pdfs.mapKeys { (key, _) -> sourceNames.getValue(key) }
        val pages = ArrayList<PageInfo>()
        val inks = linkedMapOf<String, PageInk>()
        val usedIds = HashSet<String>()
        var nextId = firstId
        fun uid(key: String?): String {
            val candidate = key?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) && usedIds.add(it) }
            return candidate ?: NoteStore.newPageUid().also { usedIds.add(it) }
        }
        if (book.pages == null) {
            sizes.forEach { (key, list) -> list.forEachIndexed { index, (w, h) ->
                pages += PageInfo(uid(null), PageInfo.KIND_PDF, pdf = index, w = w, h = h,
                    src = sourceNames.getValue(key).takeUnless { it == "base.pdf" })
            } }
        } else for (p in book.pages) {
            val pdfSize = p.pdfKey?.let { sizes[it]?.getOrNull(p.pdfIndex) ?: throw IOException("PDF 페이지를 찾을 수 없어요") }
            val rotated = p.rotation == 90 || p.rotation == 270
            val w = p.width ?: pdfSize?.let { if (rotated) it.second else it.first } ?: PageInfo.A4_W
            val h = p.height ?: pdfSize?.let { if (rotated) it.first else it.second } ?: PageInfo.A4_H
            val page = PageInfo(uid(p.key), if (p.pdfKey == null) PageInfo.KIND_BLANK else PageInfo.KIND_PDF,
                pdf = p.pdfIndex, w = w, h = h,
                src = p.pdfKey?.let { sourceNames.getValue(it).takeUnless { name -> name == "base.pdf" } }, rot = p.rotation)
            pages += page
            if (p.strokes.isNotEmpty()) {
                val ink = FlexcilInk.toPageInk(p.strokes, w, h, nextId)
                nextId += ink.strokes.size
                inks[page.uid] = ink
            }
        }
        if (pages.isEmpty()) throw IOException("노트에 페이지가 없어요")
        val meta = NoteStore.createRestored(app, book.title, folder, pages, sources, inks,
            book.original, book.createdAt, book.updatedAt)
        return meta to (nextId - firstId).toInt()
    }

    private fun diagnostic(app: Context, report: String): File? = runCatching {
        val dir = File(app.cacheDir, "notes-share").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("flexcil-diagnostic") || f.name.startsWith("notes-import-diagnostic") }?.forEach { it.delete() }
        File(dir, "notes-import-diagnostic-${System.currentTimeMillis()}.txt").apply {
            writeText(".flex/.flx 원본 복원 진단 · 파일 이름과 크기만 포함\nandroid=${Build.VERSION.SDK_INT}\n\n$report")
        }
    }.getOrNull()
}
