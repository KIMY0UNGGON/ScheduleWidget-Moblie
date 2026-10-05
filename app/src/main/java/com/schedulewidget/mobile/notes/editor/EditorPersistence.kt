package com.schedulewidget.mobile.notes.editor

import com.schedulewidget.mobile.notes.NoteStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

internal fun EditorState.scheduleSaveFeature() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(EditorState.AUTOSAVE_MS)
            saveNow()
        }
    }

    /**
     * Writes pending changes now (in the background, outliving the screen). [thumbnail] also refreshes the library
     * thumbnail when anything was saved since the last one; [final] (leaving the editor) also prunes deleted pages' ink.
     */
internal fun EditorState.saveNowFeature(final: Boolean = false, thumbnail: Boolean = final, reportError: Boolean = true): Deferred<Unit>? {
        saveJob?.cancel()
        saveJob = null
        val inks = dirtyPages.associateWith { inkOf(it) }
        val pages = note.pages
        val changed = inks.isNotEmpty() || pagesDirty
        if (changed) thumbDirty = true
        if (!changed && !final && !(thumbnail && thumbDirty)) return null
        val app = context.applicationContext
        val id = noteId
        val previous = NoteStore.pendingSaves[id]
        val revision = ++saveRevision
        val job = NoteStore.scope.async(start = CoroutineStart.LAZY) {
            try {
                previous?.join() // keep saves in order
                NoteStore.writeMutex.withLock {
                    if (changed) {
                        inks.forEach { (uid, ink) -> NoteStore.saveInk(app, id, uid, ink) }
                        NoteStore.savePages(app, id, pages) ?: throw IOException("노트를 찾을 수 없어요")
                    }
                    if (final) NoteStore.pruneInk(app, id)
                }
                if (changed) NoteStore.bump()
                val refreshThumbnail = withContext(Dispatchers.Main.immediate) {
                    if (saveRevision != revision) false else {
                        inks.forEach { (uid, ink) -> if (inkOf(uid) == ink) dirtyPages.remove(uid) }
                        if (note.pages == pages) pagesDirty = false
                        (thumbnail && thumbDirty && dirtyPages.isEmpty() && !pagesDirty).also { if (it) thumbDirty = false }
                    }
                }
                if (refreshThumbnail) NoteStore.requestThumbnail(app, id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (reportError) withContext(Dispatchers.Main.immediate) { onSaveError(e) }
                throw e
            }
        }
        NoteStore.pendingSaves[id] = job
        job.invokeOnCompletion { NoteStore.pendingSaves.remove(id, job) }
        job.start()
        return job
    }
