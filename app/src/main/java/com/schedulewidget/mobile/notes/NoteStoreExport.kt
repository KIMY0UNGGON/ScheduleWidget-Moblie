package com.schedulewidget.mobile.notes

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** Stages a complete PDF in cache before opening the user-selected output. */
internal object NoteStoreExport {
    suspend fun write(context: Context, destination: Uri, render: suspend (OutputStream) -> Unit) {
        val app = context.applicationContext
        val temp = withContext(Dispatchers.IO) { File.createTempFile("notes-export-", ".pdf", app.cacheDir) }
        var failure: Throwable? = null
        try {
            withContext(Dispatchers.IO) {
                FileOutputStream(temp).use { stream ->
                    render(stream)
                    stream.fd.sync()
                }
            }
            withContext(Dispatchers.IO) {
                val output = app.contentResolver.openOutputStream(destination, "wt")
                    ?: throw IOException("파일을 쓸 수 없어요")
                output.use { target -> temp.inputStream().use { it.copyTo(target, 64 * 1024) } }
            }
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (temp.exists() && !temp.delete()) {
                    val cleanupError = IOException("임시 PDF를 정리하지 못했어요")
                    if (failure == null) throw cleanupError else failure?.addSuppressed(cleanupError)
                }
            }
        }
    }
}
