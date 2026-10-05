package com.schedulewidget.mobile.notes

import android.content.Context
import com.schedulewidget.mobile.notes.editor.PageSources
import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.notes.ink.StoredNote
import com.schedulewidget.mobile.notes.ink.Template
import com.schedulewidget.mobile.notes.render.PdfDoc
import com.schedulewidget.mobile.notes.render.PdfThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Notebook creation paths, including restore from validated native imports. */
internal object NoteStoreCreation {
    /** New notebook whose pages are [pdf] (copied in); [source] as in [NoteMeta.source]. Throws IOException for a bad PDF. */
    fun fromPdf(context: Context, pdf: File, title: String, source: String, folder: String? = null): NoteMeta {
        val id = NoteStore.newId(context)
        val dir = NoteStore.dir(context, id).apply { mkdirs() }
        try {
            val base = NoteStore.basePdf(context, id)
            val tmp = File(dir, "base.pdf.tmp")
            pdf.inputStream().use { input -> FileOutputStream(tmp).use { input.copyTo(it); it.fd.sync() } }
            if (!tmp.renameTo(base)) throw IOException("rename failed")
            val sizes = runBlocking(PdfThread.dispatcher) { PdfDoc(base).use { it.pageSizes() } }
            if (sizes.isEmpty()) throw IOException("PDF has no pages")
            val now = System.currentTimeMillis()
            val note = StoredNote(
                id = id, title = title, createdAt = now, updatedAt = now, source = source, folder = folder,
                pages = sizes.mapIndexed { i, (w, h) -> PageInfo(NoteStore.newPageUid(), PageInfo.KIND_PDF, pdf = i, w = w, h = h) },
            )
            NoteStore.writeMeta(context, note)
            NoteStore.bump()
            NoteStore.requestThumbnail(context, id)
            return note.toMeta()
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw if (e is IOException) e else IOException(e.message ?: "PDF를 열 수 없어요", e)
        }
    }

    /** New blank notebook; [template] "plain" | "lined" | "grid" | "dot". */
    fun blank(context: Context, title: String, template: String, pages: Int = 1, folder: String? = null): NoteMeta {
        val id = NoteStore.newId(context)
        NoteStore.dir(context, id).mkdirs()
        val t = template.takeIf { it in Template.all } ?: Template.PLAIN
        val now = System.currentTimeMillis()
        val note = StoredNote(
            id = id, title = title, createdAt = now, updatedAt = now, source = "blank", folder = folder,
            pages = List(pages.coerceAtLeast(1)) { PageInfo(NoteStore.newPageUid(), PageInfo.KIND_BLANK, template = t) },
        )
        NoteStore.writeMeta(context, note)
        NoteStore.bump()
        NoteStore.requestThumbnail(context, id)
        return note.toMeta()
    }

    /** Creates a native editable notebook from validated Flexcil pages and their original sources. */
    suspend fun restored(
        context: Context,
        title: String,
        folder: String?,
        pages: List<PageInfo>,
        sources: Map<String, File>,
        inks: Map<String, PageInk>,
        original: File?,
        createdAt: Long? = null,
        updatedAt: Long? = null,
    ): NoteMeta = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) throw IOException("노트 페이지가 없어요")
        val pageUids = pages.mapTo(HashSet()) { it.uid }
        if (pageUids.size != pages.size || !pageUids.containsAll(inks.keys) ||
            pages.any { !NoteStoreFileIO.hasSafePagePaths(it) }) {
            throw IOException("복원할 필기 페이지가 올바르지 않아요")
        }
        val neededSources = pages.filter { it.isPdf }.mapTo(HashSet()) { PageSources.nameOf(it) }
        if (!sources.keys.containsAll(neededSources)) throw IOException("원본 PDF를 찾을 수 없어요")
        if (sources.keys.any { it != "base.pdf" && !it.matches(Regex("src-flex-[A-Za-z0-9_-]+\\.pdf")) }) {
            throw IOException("원본 PDF 이름이 올바르지 않아요")
        }
        if (sources.values.any { !it.isFile }) throw IOException("원본 PDF를 읽을 수 없어요")
        if (original != null && !original.isFile) throw IOException("원본 .flx 파일을 읽을 수 없어요")

        val app = context.applicationContext
        val id = NoteStore.newId(app)
        val noteDir = NoteStore.dir(app, id)
        var created = false
        try {
            NoteStore.writeMutex.withLock {
                if (noteDir.exists() || !noteDir.mkdirs()) throw IOException("노트 저장 공간을 만들 수 없어요")
                created = true
                sources.forEach { (name, source) ->
                    val target = File(noteDir, name)
                    source.inputStream().use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() }
                    }
                }
                original?.let { source ->
                    FileOutputStream(File(noteDir, "original.flx")).use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                        output.fd.sync()
                    }
                }
                inks.forEach { (uid, ink) -> if (!ink.isEmpty) NoteStore.saveInk(app, id, uid, ink) }
                val now = System.currentTimeMillis()
                val savedCreatedAt = createdAt?.takeIf { it > 0 } ?: now
                val savedUpdatedAt = updatedAt?.takeIf { it >= savedCreatedAt } ?: maxOf(now, savedCreatedAt)
                val note = StoredNote(
                    id = id,
                    title = title,
                    createdAt = savedCreatedAt,
                    updatedAt = savedUpdatedAt,
                    source = "flexcil",
                    folder = folder,
                    pages = pages,
                )
                NoteStore.writeMeta(app, note)
                NoteStore.bump()
                NoteStore.requestThumbnail(app, id)
                note.toMeta()
            }
        } catch (e: Throwable) {
            if (created) noteDir.deleteRecursively()
            throw e
        }
    }
}
