package com.schedulewidget.mobile.notes

import android.content.Context
import com.schedulewidget.mobile.notes.editor.PageSources
import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.notes.ink.StoredNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Copying, inserting, and extracting notebook pages with their source PDFs and handwriting. */
internal object NoteStorePageTransfer {
    fun transfer(
        context: Context, fromId: String, toId: String, pages: List<PageInfo>, inkOf: (PageInfo) -> PageInk,
    ): List<Pair<PageInfo, PageInk>> {
        val names = HashMap<String, String>()
        return pages.map { page ->
            val copy = if (page.isPdf && fromId != toId) {
                val from = PageSources.nameOf(page)
                val name = names.getOrPut(from) {
                    val file = NoteStore.sourceFile(context, fromId, page)
                    if (!file.exists()) throw IOException("원본 PDF를 찾을 수 없어요")
                    NoteStore.storeSource(context, toId, file)
                }
                page.copy(uid = NoteStore.newPageUid(), src = name)
            } else {
                page.copy(uid = NoteStore.newPageUid())
            }
            copy to inkOf(page)
        }
    }

    suspend fun pagesFrom(context: Context, fromId: String, toId: String, indices: List<Int>): List<Pair<PageInfo, PageInk>> =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            NoteStore.pendingSaves[fromId]?.join()
            val source = NoteStore.load(app, fromId) ?: throw IOException("노트를 찾을 수 없어요")
            val pages = indices.mapNotNull { source.pages.getOrNull(it) }
            NoteStore.writeMutex.withLock {
                transfer(app, fromId, toId, pages) { NoteStore.loadInk(app, fromId, it.uid) }
            }
        }

    suspend fun copyPagesTo(
        context: Context, fromId: String, pages: List<PageInfo>, inkOf: (PageInfo) -> PageInk, toId: String,
    ): NoteMeta = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        NoteStore.pendingSaves[toId]?.join()
        val meta = NoteStore.writeMutex.withLock {
            val moved = transfer(app, fromId, toId, pages, inkOf)
            moved.forEach { (page, ink) -> NoteStore.saveInk(app, toId, page.uid, ink) }
            NoteStore.update(app, toId) { note ->
                note.copy(pages = note.pages + moved.map { it.first }, updatedAt = System.currentTimeMillis())
            }?.toMeta() ?: throw IOException("노트를 찾을 수 없어요")
        }
        NoteStore.bump()
        NoteStore.requestThumbnail(app, toId)
        meta
    }

    suspend fun extractToNew(
        context: Context, fromId: String, pages: List<PageInfo>, inkOf: (PageInfo) -> PageInk, title: String, folder: String?,
    ): NoteMeta = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val id = NoteStore.newId(context)
        val dir = NoteStore.dir(app, id).apply { mkdirs() }
        try {
            val meta = NoteStore.writeMutex.withLock {
                val moved = transfer(app, fromId, id, pages, inkOf)
                moved.forEach { (page, ink) -> NoteStore.saveInk(app, id, page.uid, ink) }
                val now = System.currentTimeMillis()
                val note = StoredNote(
                    id = id, title = title, createdAt = now, updatedAt = now,
                    source = if (moved.any { it.first.isPdf }) "pdf" else "blank", folder = folder,
                    pages = moved.map { it.first },
                )
                NoteStore.writeMeta(app, note)
                note.toMeta()
            }
            NoteStore.bump()
            NoteStore.requestThumbnail(app, id)
            meta
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }
}
