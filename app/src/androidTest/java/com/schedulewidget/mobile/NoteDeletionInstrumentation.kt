package com.schedulewidget.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import com.schedulewidget.mobile.notes.NoteStore
import com.schedulewidget.mobile.notes.editor.EditorState
import com.schedulewidget.mobile.notes.editor.TextEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/** Reproduces a dirty editor saving after its notebook has been deleted from the library. */
class NoteDeletionInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        var failure: Throwable? = null
        try {
            runBlocking {
                val scratch = File(targetContext.cacheDir, "note-delete-${System.nanoTime()}")
                val files = File(scratch, "files").apply { mkdirs() }
                val context = object : ContextWrapper(targetContext) {
                    override fun getFilesDir() = files
                    override fun getApplicationContext() = this
                }
                val meta = NoteStore.createBlank(context, "Delete regression", "plain")
                val note = checkNotNull(NoteStore.load(context, meta.id))
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
                try {
                    val state = withContext(Dispatchers.Main) { EditorState(context, note, emptyMap(), 1f, scope) }
                    val uid = note.pages.first().uid
                    withContext(Dispatchers.Main) {
                        state.applyText(TextEdit(uid, null, 10f, 10f), "unsaved", 16f)
                    }

                    withContext(Dispatchers.IO) { NoteStore.delete(context, meta.id) }
                    val lateSave = withContext(Dispatchers.Main) { state.saveNow(reportError = false) }
                    check(lateSave != null) { "Expected a dirty editor save after deletion" }
                    check(runCatching { lateSave.await() }.isFailure) { "Deleted notebook accepted a later save" }
                    check(!File(files, "notes/${meta.id}").exists()) { "Late save recreated the deleted notebook folder" }
                } finally {
                    scope.cancel()
                    runCatching { withContext(Dispatchers.IO) { NoteStore.delete(context, meta.id) } }
                    scratch.deleteRecursively()
                }
            }
        } catch (e: Throwable) {
            failure = e
        }
        finish(
            if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", failure?.stackTraceToString() ?: "PASS: late editor save cannot recreate deleted notebook\n") },
        )
    }
}
