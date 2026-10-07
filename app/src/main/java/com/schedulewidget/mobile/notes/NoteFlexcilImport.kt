package com.schedulewidget.mobile.notes

import android.content.Context
import android.os.Build
import com.schedulewidget.mobile.notes.importer.FlexcilArchive
import com.schedulewidget.mobile.notes.importer.FlexcilDocument
import com.schedulewidget.mobile.notes.importer.FlexcilInk
import com.schedulewidget.mobile.notes.importer.FlexcilRecording
import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.notes.library.NoteFolders
import com.schedulewidget.mobile.notes.render.PdfDoc
import com.schedulewidget.mobile.notes.render.PdfThread
import com.schedulewidget.mobile.record.Recordings
import com.schedulewidget.mobile.record.RecordingItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

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
        val sourceNotes = linkedMapOf<String, String>()
        val ambiguousDocuments = hashSetOf<String>()
        val recordings = linkedMapOf<RecordingItem, Set<String>>()
        val strokeNotes = linkedMapOf<String, MutableSet<String>>()
        var persistedBytes = 0L
        var persistedPages = 0
        fun reservePages(count: Int) {
            if (count < 0 || count > FlexcilArchive.MAX_TOTAL_PAGES - persistedPages) {
                throw FlexcilArchive.ResourceLimitExceeded("복원할 페이지가 너무 많아요")
            }
            persistedPages += count
        }
        fun reserveOutput(bytes: Long) {
            if (bytes < 0 || bytes > MAX_RESTORE_OUTPUT_BYTES - persistedBytes) {
                throw IOException("복원할 데이터가 8GB보다 커요")
            }
            persistedBytes += bytes
        }
        var cancelled: CancellationException? = null
        var archiveFailure: FlexcilArchive.FlexcilError? = null
        var otherFailure: Throwable? = null
        fun originalNotice() = when {
            notes.isNotEmpty() && originals == notes.size -> "원본 .flx 파일도 노트 안에 보관했습니다."
            originals > 0 -> "원본 .flx 파일은 해당 노트 안에 보관했습니다."
            else -> "선택한 원본 백업 파일은 그대로 있습니다."
        }
        val result = try {
            FlexcilArchive.restoreCancellable(input, name, work, progress = stage, checkActive = { job.ensureActive() },
                recordingSink = { audio: FlexcilRecording ->
                    job.ensureActive()
                    val before = persistedBytes
                    val imported = try {
                        reserveOutput(audio.audio.length())
                        Recordings.importAudio(app, audio.audio, audio.title, audio.createdAt, audio.durationMs) {
                            job.ensureActive()
                        }
                    } catch (e: Throwable) {
                        persistedBytes = before
                        throw e
                    }
                    // Track ownership before the next cancellation check, so an interrupted restore can clean up.
                    recordings[imported] = audio.documentKeys
                    imported.id
                },
            ) { book ->
                job.ensureActive()
                val before = persistedBytes
                val pagesBefore = persistedPages
                val restored = try {
                    book.pdfs.values.forEach { reserveOutput(it.length()) }
                    book.original?.let { reserveOutput(it.length()) }
                    runBlocking { restoreBook(app, book, target(book), nextId, { job.ensureActive() }, ::reserveOutput, ::reservePages) }
                } catch (e: Throwable) {
                    persistedBytes = before
                    persistedPages = pagesBefore
                    throw e
                }
                notes += restored.first
                nextId += restored.second
                strokes += restored.second
                if (book.original != null) originals++
                book.sourceKey?.uppercase(Locale.ROOT)?.let { key ->
                    if (key in sourceNotes || key in ambiguousDocuments) {
                        sourceNotes.remove(key)
                        ambiguousDocuments += key
                    } else sourceNotes[key] = restored.first.id
                }
                book.pages.orEmpty().flatMap { it.strokes }.mapNotNull { it.recordingId }.toSet().forEach { id ->
                    strokeNotes.getOrPut(id) { linkedSetOf() } += restored.first.id
                }
                // Record committed associations before folder registration, which can itself fail.
                if (notes.size == 1 && root != null) NoteFolders.add(app, root)
            }.also { job.ensureActive() }
        } catch (e: CancellationException) {
            cancelled = e
            null
        } catch (e: FlexcilArchive.FlexcilError) {
            archiveFailure = e
            null
        } catch (e: Throwable) {
            otherFailure = e
            null
        }
        var recordingsKept = 0
        var unresolvedRecordings = 0
        var linkFailures = 0
        var cleanupFailures = 0
        // Finished notes remain usable after cancellation; only files owned by this restore are considered here.
        withContext(NonCancellable + Dispatchers.IO) {
            val liveNoteIds = notes.mapNotNull { NoteStore.get(app, it.id)?.id }.toSet()
            recordings.forEach { (item, documentKeys) ->
                val id = item.id
                val linkedNotes = (documentKeys.mapNotNull { sourceNotes[it.uppercase(Locale.ROOT)] } +
                    strokeNotes[id].orEmpty()).distinct()
                if (linkedNotes.isEmpty() && (documentKeys.isNotEmpty() || cancelled != null || archiveFailure != null || otherFailure != null || notes.isEmpty())) {
                    try {
                        Recordings.delete(app, item)
                        if (item.audio.exists() || File(item.audio.parentFile, "$id.json").exists()) {
                            throw IOException("가져온 녹음을 정리하지 못했어요")
                        }
                        unresolvedRecordings++
                    } catch (_: Exception) {
                        cleanupFailures++
                    }
                } else {
                    recordingsKept++
                    try {
                        Recordings.setNoteLinks(app, id, linkedNotes, liveNoteIds)
                    } catch (_: Exception) {
                        linkFailures++
                    }
                }
            }
        }
        otherFailure?.let { throw it }
        val audioNotices = buildList {
            if (unresolvedRecordings > 0) add("연결할 PDF·노트를 복원하지 못한 녹음 ${unresolvedRecordings}개는 건너뛰었어요.")
            if (linkFailures > 0) add("녹음 ${linkFailures}개의 노트 연결 정보를 저장하지 못했어요. 복원된 녹음 파일은 보존했습니다.")
            if (cleanupFailures > 0) add("연결되지 않은 녹음 ${cleanupFailures}개를 정리하지 못했어요. 녹음 목록에서 확인할 수 있습니다.")
        }
        cancelled?.let { cause ->
            if (notes.isEmpty()) throw cause
            return Result(notes, "노트 ${notes.size}개를 복원했어요 · 녹음 ${recordingsKept}개",
                (listOf("가져오기를 취소했어요. 먼저 복원한 노트와 연결된 녹음은 저장했습니다. ${originalNotice()}") + audioNotices)
                    .joinToString("\n"), cancelled = true, importedFolder = root)
        }
        archiveFailure?.let { error ->
            val diagnostic = diagnostic(app, error.report)
            if (notes.isEmpty()) throw NoteImport.ImportError(".flex/.flx 노트를 복원하지 못했어요: ${error.message}", diagnostic)
            return Result(notes, "노트 ${notes.size}개를 복원했어요 · 녹음 ${recordingsKept}개",
                (listOf("일부 데이터를 읽지 못했어요: ${error.message}") + audioNotices).joinToString("\n"), diagnostic,
                importedFolder = root)
        }
        val restoredArchive = checkNotNull(result)
        if (notes.isEmpty()) throw NoteImport.ImportError(
            when {
                restoredArchive.passwordFailures > 0 && restoredArchive.failedDocs == restoredArchive.passwordFailures -> "암호가 걸린 PDF 배경이 있어 노트를 복원하지 못했어요"
                restoredArchive.failedDocs > 0 -> "노트의 페이지나 PDF 배경을 읽지 못했어요"
                else -> "복원할 노트를 찾지 못했어요"
            },
            diagnostic(app, restoredArchive.report),
        )
        val details = buildList {
            addAll(audioNotices)
            if (restoredArchive.audioFailures > 0) add("녹음 ${restoredArchive.audioFailures}개는 읽거나 저장하지 못했어요. 원본 백업 파일은 그대로 있습니다.")
            if (restoredArchive.unmatchedAudioRefs > 0) add("현재 필기와 일치하지 않는 녹음 연결 ${restoredArchive.unmatchedAudioRefs}개는 적용하지 않았어요.")
            if (restoredArchive.inkLost > 0) add("지원하지 않는 필기·도형 ${restoredArchive.inkLost}개가 복원되지 않았어요. ${originalNotice()}")
            if (restoredArchive.imagePagesLost > 0) add("이미지 데이터가 포함된 페이지 ${restoredArchive.imagePagesLost}쪽은 이미지를 복원하지 못했어요. ${originalNotice()}")
            if (restoredArchive.passwordFailures > 0) add("암호가 걸린 PDF 배경이 있는 노트 ${restoredArchive.passwordFailures}개는 건너뛰었어요.")
            val otherFailures = restoredArchive.failedDocs - restoredArchive.passwordFailures
            if (otherFailures > 0) add("노트 ${otherFailures}개는 읽지 못했어요. 원본 백업 파일은 그대로 있습니다.")
        }.joinToString("\n").ifBlank { null }
        return Result(notes, "노트 ${notes.size}개를 복원했어요" + (if (strokes > 0) " · 필기 ${strokes}개" else "") +
            (if (recordingsKept > 0) " · 녹음 ${recordingsKept}개" else ""),
            details, details?.let { diagnostic(app, restoredArchive.report) }, importedFolder = root)
    }

    /** "<selected folder>/<backup file name>", made unique among existing folders (implied parents included). */
    private fun backupFolder(app: Context, name: String?, parent: String?): String {
        val base = name?.substringAfterLast('/')?.substringAfterLast('\\')?.let(FlexcilArchive::stripExt)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: "가져온 백업"
        val candidate = parent?.takeIf { it.isNotBlank() }?.let { "$it/$base" } ?: base
        return NoteFolders.uniquePath(candidate, NoteFolders.all(app, NoteStore.list(app)))
    }

    private suspend fun restoreBook(
        app: Context, book: FlexcilDocument, folder: String?, firstId: Long,
        checkActive: () -> Unit, reserveOutput: (Long) -> Unit, reservePages: (Int) -> Unit,
    ): Pair<NoteMeta, Int> {
        val sizes = try {
            runBlocking(PdfThread.dispatcher) {
                var sourcePages = 0
                book.pdfs.mapValues { (_, file) -> PdfDoc(file).use {
                    checkActive()
                    val pages = it.pageSizes()
                    if (pages.size > FlexcilArchive.MAX_TOTAL_PAGES - sourcePages) {
                        throw FlexcilArchive.ResourceLimitExceeded("PDF 배경의 페이지가 너무 많아요")
                    }
                    sourcePages += pages.size
                    pages
                } }
            }
        } catch (e: SecurityException) {
            throw FlexcilArchive.PasswordProtectedPdf(e)
        }
        reservePages(book.pages?.size ?: sizes.values.sumOf { it.size })
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
            checkActive()
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
            book.original, book.createdAt, book.updatedAt, checkActive, reserveOutput)
        return meta to (nextId - firstId).toInt()
    }

    private const val MAX_RESTORE_OUTPUT_BYTES = 8L shl 30

    private fun diagnostic(app: Context, report: String): File? = runCatching {
        val dir = File(app.cacheDir, "notes-share").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("flexcil-diagnostic") || f.name.startsWith("notes-import-diagnostic") }?.forEach { it.delete() }
        File(dir, "notes-import-diagnostic-${System.currentTimeMillis()}.txt").apply {
            writeText(".flex/.flx 원본 복원 진단 · 파일 이름과 크기만 포함\nandroid=${Build.VERSION.SDK_INT}\n\n$report")
        }
    }.getOrNull()
}
