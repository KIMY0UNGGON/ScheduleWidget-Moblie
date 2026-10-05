package com.schedulewidget.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import com.schedulewidget.mobile.notes.NoteImport
import com.schedulewidget.mobile.notes.NoteStore
import com.schedulewidget.mobile.notes.library.NoteFolders
import kotlinx.coroutines.runBlocking
import java.io.File

/** Runs the real import path against a small original backup sample; its notes live only in a scratch context. */
class FlexcilRestoreInstrumentation : Instrumentation() {
    private var fixturePath = "/data/local/tmp/native-sample.flex"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        arguments?.getString("fixture")?.let { fixturePath = it }
        start()
    }

    override fun onStart() {
        val root = File(targetContext.cacheDir, "native-restore-${System.nanoTime()}")
        val files = File(root, "files").apply { mkdirs() }
        val cache = File(root, "cache").apply { mkdirs() }
        val prefsPrefix = "native_restore_test_${System.nanoTime()}_"
        val prefsUsed = HashSet<String>()
        val scratch = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
            override fun getCacheDir() = cache
            // Imported backup folders are registered in preferences; keep them out of the device's real list.
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                prefsUsed += prefsPrefix + name
                return targetContext.getSharedPreferences(prefsPrefix + name, mode)
            }
        }
        val results = ArrayList<String>()
        var failures = 0
        try {
            runBlocking {
                val restored = NoteImport.import(scratch, Uri.fromFile(File(fixturePath)))
                check(restored.isSuccess) { "Import failed: ${restored.exceptionOrNull()}" }
                val notes = NoteStore.list(scratch)
                check(notes.size == 3) { "Expected 3 original notebooks, got ${notes.size}" }
                val backupFolder = File(fixturePath).name.substringBeforeLast('.')
                check(notes.all { NoteFolders.isWithin(it.folder, backupFolder) }) { "Backup was not restored into its own folder" }
                var strokes = 0; var blanks = 0; var mixed = 0
                for (meta in notes) {
                    val note = checkNotNull(NoteStore.load(scratch, meta.id))
                    check(note.source == "flexcil")
                    check(File(files, "notes/${meta.id}/original.flx").isFile) { "Original document was not preserved" }
                    if (note.pages.map { it.src ?: "base.pdf" }.distinct().size > 1) mixed++
                    for (page in note.pages) {
                        if (!page.isPdf) blanks++
                        if (page.isPdf) check(NoteStore.sourceFile(scratch, meta.id, page).isFile)
                        strokes += NoteStore.loadInk(scratch, meta.id, page.uid).strokes.size
                    }
                }
                check(strokes > 0) { "Editable handwriting was not restored" }
                check(blanks > 0) { "Blank pages were dropped" }
                check(mixed > 0) { "Mixed PDF sources were split apart" }
                results += "PASS: 3 native notebooks restored; editable strokes=$strokes, blank pages=$blanks, mixed-source notebooks=$mixed"
                check(File(cache, "note_import").walkTopDown().none { it.isFile && it.name == "input" }) { "Seekable backup was copied into cache" }
                results += "PASS: original .flx files retained; direct SAF descriptor import avoids full backup cache copy"
            }
        } catch (e: Throwable) {
            failures++
            results += "FAIL: ${e.stackTraceToString()}"
        } finally {
            runBlocking { NoteStore.writeMutex.lock(); try { root.deleteRecursively() } finally { NoteStore.writeMutex.unlock() } }
            prefsUsed.forEach { targetContext.deleteSharedPreferences(it) }
        }
        val text = results.joinToString("\n") + "\nFailures: $failures\n"
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\n$text")
        })
    }
}
