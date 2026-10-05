package com.schedulewidget.mobile.notes

import android.content.Context
import android.net.Uri
import com.schedulewidget.mobile.notes.ink.InkJson
import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.notes.ink.StoredNote
import com.schedulewidget.mobile.notes.render.NoteExport
import com.schedulewidget.mobile.notes.render.PdfDoc
import com.schedulewidget.mobile.notes.render.PdfThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Storage of notebooks under filesDir/notes/<id>/:
 *  - meta.json  ([StoredNote]: title, folder, dates and the page list with stable page uids)
 *  - base.pdf   (the imported / converted PDF, when the notebook came from a file)
 *  - ink/<pageUid>.json (strokes and text boxes of one page, [PageInk])
 *  - thumb.png  (first page with its handwriting, for the library)
 * Every file is written to a .tmp and renamed over the old one, so a crash never leaves half a file.
 */
object NoteStore {
    private val _version = MutableStateFlow(0)
    /** Bumped whenever any notebook is added, changed or removed (the library re-reads [list]). */
    val version: StateFlow<Int> = _version

    /** Background work that must outlive a screen (saves, thumbnails). */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Serializes meta read-modify-write between the editor's autosave and the library's rename / move. */
    private val lock = Any()
    /** Serializes background writes (autosave, thumbnails) per process. */
    internal val writeMutex = Mutex()
    /** The editor's last background save per notebook (re-opening waits for it). */
    internal val pendingSaves = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    /** Keep deleted ids fenced so a still-open editor cannot recreate their files. */
    private val deletedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    internal fun root(context: Context) = NoteStoreFileIO.root(context)
    internal fun dir(context: Context, id: String) = NoteStoreFileIO.dir(context, id)
    internal fun basePdf(context: Context, id: String) = NoteStoreFileIO.basePdf(context, id)
    private fun metaFile(context: Context, id: String) = NoteStoreFileIO.metaFile(context, id)

    internal fun bump() { _version.value++ }

    fun list(context: Context): List<NoteMeta> =
        root(context).listFiles { f -> f.isDirectory }.orEmpty()
            .mapNotNull { load(context, it.name)?.toMeta() }
            .sortedByDescending { it.updatedAt }

    fun get(context: Context, id: String): NoteMeta? = load(context, id)?.toMeta()

    /** New notebook whose pages are [pdf] (copied in); [source] as in [NoteMeta.source]. Throws IOException for a bad PDF. */
    fun createFromPdf(context: Context, pdf: File, title: String, source: String, folder: String? = null): NoteMeta =
        NoteStoreCreation.fromPdf(context, pdf, title, source, folder)

    /** New blank notebook; [template] "plain" | "lined" | "grid" | "dot". */
    fun createBlank(context: Context, title: String, template: String, pages: Int = 1, folder: String? = null): NoteMeta =
        NoteStoreCreation.blank(context, title, template, pages, folder)

    /** Creates a native editable notebook from validated Flexcil pages and their original sources. */
    internal suspend fun createRestored(
        context: Context,
        title: String,
        folder: String?,
        pages: List<PageInfo>,
        sources: Map<String, File>,
        inks: Map<String, PageInk>,
        original: File?,
        createdAt: Long? = null,
        updatedAt: Long? = null,
    ): NoteMeta = NoteStoreCreation.restored(context, title, folder, pages, sources, inks, original, createdAt, updatedAt)

    fun rename(context: Context, id: String, title: String) {
        if (update(context, id) { it.copy(title = title, updatedAt = System.currentTimeMillis()) } != null) bump()
    }

    fun move(context: Context, id: String, folder: String?) {
        if (update(context, id) { it.copy(folder = folder) } != null) bump()
    }

    suspend fun delete(context: Context, id: String) {
        val app = context.applicationContext
        dir(app, id) // Reject path-like ids before fencing or deleting anything.
        var deleted = false
        var fencedHere = false
        try {
            // Let the already pending save finish, then fence deletion atomically with the storage write lock.
            pendingSaves[id]?.join()
            writeMutex.withLock {
                synchronized(lock) {
                    fencedHere = deletedIds.add(id)
                    val target = dir(app, id)
                    if (target.exists() && !target.deleteRecursively()) {
                        throw IOException("노트를 삭제하지 못했어요")
                    }
                    deleted = true
                }
            }
            bump()
        } catch (e: Throwable) {
            if (!deleted && fencedHere) deletedIds.remove(id)
            throw e
        }
    }

    /** A small PNG of the first page with its handwriting (created/refreshed by the store); null if not available yet. */
    fun thumbnail(context: Context, id: String): File? = NoteStoreThumbnail.file(context, id)

    /** Writes a flattened PDF (pages + handwriting) to [out]. Throws on failure (IOException, SecurityException). */
    suspend fun exportPdf(context: Context, id: String, out: Uri) {
        pendingSaves[id]?.join()
        val note = withContext(Dispatchers.IO) { load(context, id) } ?: throw IOException("노트를 찾을 수 없어요")
        val app = context.applicationContext
        NoteStoreExport.write(app, out) { stream ->
            NoteExport.writePdf(note, basePdf(app, id), stream, inkOf = { loadInk(app, id, it.uid) })
        }
    }

    // ---- internal API of the editor ----

    internal fun load(context: Context, id: String): StoredNote? = runCatching {
        InkJson.decodeNote(metaFile(context, id).readText()).takeIf { note ->
            note.id == id && note.pages.all { NoteStoreFileIO.hasSafePagePaths(it) }
        }
    }.getOrNull()

    internal fun loadInk(context: Context, id: String, uid: String): PageInk = NoteStoreInk.load(context, id, uid)

    /** Saves one page's ink (an empty page just loses its file). */
    internal fun saveInk(context: Context, id: String, uid: String, ink: PageInk) = NoteStoreInk.save(context, id, uid, ink)

    /** Replaces the page list (editor saves); title / folder changed meanwhile by the library are kept. */
    internal fun savePages(context: Context, id: String, pages: List<PageInfo>): StoredNote? {
        if (pages.any { !NoteStoreFileIO.hasSafePagePaths(it) }) throw IOException("페이지 경로가 올바르지 않아요")
        return update(context, id) { it.copy(pages = pages, updatedAt = System.currentTimeMillis()) }
    }

    /**
     * Deletes ink files of pages no longer in the notebook (kept while the editor is open, for undo), and inserted
     * page sources ("src-*.pdf") no page refers to any more. base.pdf is never deleted.
     */
    internal fun pruneInk(context: Context, id: String) = NoteStoreInk.prune(context, id)

    // ---- page sources: PDFs that inserted pages come from (PageInfo.src) ----

    /** The PDF file behind page [page] of notebook [id] (base.pdf or the page's own source). */
    internal fun sourceFile(context: Context, id: String, page: PageInfo): File = NoteStoreFileIO.sourceFile(context, id, page)

    /** Copies [pdf] into notebook [id]'s folder as a page source (unless already there); returns its name. */
    internal fun storeSource(context: Context, id: String, pdf: File): String = NoteStoreFileIO.storeSource(context, id, pdf)

    /**
     * Adds [pdf] to notebook [id] as a page source and returns its name with the page sizes (points). Throws
     * IOException with a user-facing message for a PDF that doesn't open (password, damaged) or has no pages.
     */
    internal suspend fun addSourcePdf(context: Context, id: String, pdf: File): Pair<String, List<Pair<Float, Float>>> {
        val app = context.applicationContext
        val sizes = try {
            withContext(PdfThread.dispatcher) { PdfDoc(pdf).use { it.pageSizes() } }
        } catch (e: SecurityException) {
            throw IOException("암호가 걸린 PDF는 열 수 없어요", e)
        } catch (e: Exception) {
            throw IOException("PDF 파일이 손상되어 열 수 없어요", e)
        }
        if (sizes.isEmpty()) throw IOException("페이지가 없는 PDF예요")
        val name = withContext(Dispatchers.IO) { writeMutex.withLock { storeSource(app, id, pdf) } }
        return name to sizes
    }

    /**
     * Copies of [pages] (of notebook [fromId]) for notebook [toId]: new uids, their PDF sources copied into [toId]'s
     * folder, and their handwriting from [inkOf]. Nothing is written to [toId]'s page list.
     */
    internal fun transferPages(
        context: Context, fromId: String, toId: String, pages: List<PageInfo>, inkOf: (PageInfo) -> PageInk,
    ): List<Pair<PageInfo, PageInk>> = NoteStorePageTransfer.transfer(context, fromId, toId, pages, inkOf)

    /** Pages [indices] of notebook [fromId] (as stored) prepared for insertion into [toId] (see [transferPages]). */
    internal suspend fun pagesFrom(context: Context, fromId: String, toId: String, indices: List<Int>): List<Pair<PageInfo, PageInk>> =
        NoteStorePageTransfer.pagesFrom(context, fromId, toId, indices)

    /** Appends copies of [pages] (with [inkOf]) to the end of the existing notebook [toId]. */
    internal suspend fun copyPagesTo(
        context: Context, fromId: String, pages: List<PageInfo>, inkOf: (PageInfo) -> PageInk, toId: String,
    ): NoteMeta = NoteStorePageTransfer.copyPagesTo(context, fromId, pages, inkOf, toId)

    /** A new notebook [title] (in [folder]) made of copies of [pages]. */
    internal suspend fun extractToNew(
        context: Context, fromId: String, pages: List<PageInfo>, inkOf: (PageInfo) -> PageInk, title: String, folder: String?,
    ): NoteMeta = NoteStorePageTransfer.extractToNew(context, fromId, pages, inkOf, title, folder)

    /** Writes [pages] of notebook [id] (with [inkOf], e.g. the editor's unsaved ink) as a flattened PDF to [out]. */
    suspend fun exportPages(context: Context, id: String, pages: List<PageInfo>, inkOf: (PageInfo) -> PageInk, out: Uri) {
        val note = withContext(Dispatchers.IO) { load(context, id) } ?: throw IOException("노트를 찾을 수 없어요")
        val app = context.applicationContext
        NoteStoreExport.write(app, out) { stream ->
            NoteExport.writePdf(note, basePdf(app, id), stream, inkOf = inkOf, pages = pages)
        }
    }

    internal fun update(context: Context, id: String, transform: (StoredNote) -> StoredNote): StoredNote? = synchronized(lock) {
        if (id in deletedIds) return null
        val cur = load(context, id) ?: return null
        val next = transform(cur)
        writeMeta(context, next)
        next
    }

    internal fun writeMeta(context: Context, note: StoredNote) {
        NoteStoreFileIO.writeAtomic(metaFile(context, note.id), InkJson.encodeNote(note).toByteArray())
    }

    internal fun newId(context: Context): String {
        while (true) {
            val id = System.currentTimeMillis().toString(36) + "-" + UUID.randomUUID().toString().take(6)
            if (id !in deletedIds && !dir(context, id).exists()) return id
        }
    }

    internal fun ensureNotDeleted(id: String) {
        if (id in deletedIds) throw IOException("노트를 찾을 수 없어요")
    }

    internal fun newPageUid(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    /** Re-renders thumb.png in the background, then bumps [version]. */
    internal fun requestThumbnail(context: Context, id: String) = NoteStoreThumbnail.request(context, id)
}
