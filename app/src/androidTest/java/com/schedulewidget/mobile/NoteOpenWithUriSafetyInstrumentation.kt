package com.schedulewidget.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import com.schedulewidget.mobile.notes.NoteImport
import com.schedulewidget.mobile.notes.library.NoteOpenWith
import java.io.File

/** URI gate checks that do not query providers or read the referenced files. */
class NoteOpenWithUriSafetyInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        var failure: Throwable? = null
        var sharedFile: File? = null
        try {
            val context = targetContext
            check(!NoteOpenWith.isImportableUri(context, Uri.fromFile(File("/tmp/shared.pdf")))) {
                "A file URI was accepted for import"
            }
            check(!NoteOpenWith.isImportableUri(context, Uri.parse("https://example.invalid/shared.pdf"))) {
                "An HTTP URI was accepted for import"
            }
            check(!NoteOpenWith.isImportableUri(context, Uri.parse("content://${context.packageName}.files/shared.pdf"))) {
                "The app's own FileProvider URI was accepted for import"
            }
            check(NoteOpenWith.isImportableUri(context, Uri.parse("content://com.example.provider/document/1"))) {
                "A shared content URI was rejected"
            }

            fun assertRejected(intent: Intent, label: String) {
                var actionFailure: Throwable? = null
                runOnMainSync {
                    val before = NoteImport.state.value
                    try {
                        check(NoteOpenWith.handle(context, intent)) { "$label was not handled safely" }
                        check(NoteImport.state.value == before) { "$label started a file import" }
                    } catch (e: Throwable) {
                        actionFailure = e
                    } finally {
                        if (NoteImport.state.value != before) NoteImport.cancel()
                    }
                }
                actionFailure?.let { throw it }
            }
            val fileUri = Uri.fromFile(File("/tmp/shared.pdf"))
            assertRejected(Intent(Intent.ACTION_VIEW).setData(fileUri), "ACTION_VIEW file URI")
            assertRejected(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, fileUri), "ACTION_SEND file URI")
            assertRejected(
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(fileUri)),
                "ACTION_SEND_MULTIPLE file URI",
            )
            val shared = File(context.cacheDir, "notes-share/uri-gate-${System.nanoTime()}.pdf").apply {
                parentFile?.mkdirs()
                writeText("not read by URI validation")
            }
            sharedFile = shared
            val ownUri = FileProvider.getUriForFile(context, context.packageName + ".files", shared)
            assertRejected(Intent(Intent.ACTION_VIEW).setData(ownUri), "app FileProvider URI")
        } catch (e: Throwable) {
            failure = e
        } finally {
            sharedFile?.delete()
        }
        finish(
            if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", failure?.stackTraceToString() ?: "PASS: share imports reject unsafe URI sources\n") },
        )
    }
}
