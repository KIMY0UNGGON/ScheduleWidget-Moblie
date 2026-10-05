package com.schedulewidget.mobile.notes

import android.content.Context
import android.graphics.Bitmap
import com.schedulewidget.mobile.notes.render.NoteExport
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream

/** Coalesced background rendering of notebook library thumbnails. */
internal object NoteStoreThumbnail {
    private const val WIDTH = 360
    private val pending = HashSet<String>()
    private val dirty = HashSet<String>()

    fun file(context: Context, id: String): File? {
        val thumb = NoteStoreFileIO.thumbFile(context, id)
        if (thumb.exists()) return thumb
        if (NoteStoreFileIO.metaFile(context, id).exists()) request(context, id)
        return null
    }

    fun request(context: Context, id: String) {
        val app = context.applicationContext
        synchronized(pending) {
            if (!pending.add(id)) { dirty.add(id); return }
        }
        NoteStore.scope.launch {
            var isPending = true
            try {
                while (true) {
                    synchronized(pending) { dirty.remove(id) }
                    NoteStore.writeMutex.withLock { render(app, id) }
                    val again = synchronized(pending) {
                        if (dirty.remove(id)) true
                        else { pending.remove(id); isPending = false; false }
                    }
                    if (!again) break
                }
            } finally {
                if (isPending) {
                    val rerun = synchronized(pending) {
                        pending.remove(id)
                        dirty.remove(id)
                    }
                    if (rerun) request(app, id)
                }
            }
        }
    }

    private suspend fun render(context: Context, id: String) {
        val note = NoteStore.load(context, id) ?: return
        val first = note.pages.firstOrNull() ?: return
        val bitmap = runCatching {
            NoteExport.thumbnail(note, NoteStore.basePdf(context, id), NoteStore.loadInk(context, id, first.uid), WIDTH)
        }.getOrNull() ?: return
        val file = NoteStoreFileIO.thumbFile(context, id)
        if (!NoteStore.dir(context, id).exists()) return
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
        bitmap.recycle()
        NoteStore.bump()
    }
}
