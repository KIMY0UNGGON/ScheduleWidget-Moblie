package com.schedulewidget.mobile.notes

import android.content.Context
import com.schedulewidget.mobile.notes.editor.PageSources
import com.schedulewidget.mobile.notes.ink.PageInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32

/** Paths and atomic byte/file writes for the notebook store. */
internal object NoteStoreFileIO {
    private const val SOURCE_PREFIX = "src-"

    fun root(context: Context) = File(context.filesDir, "notes")
    fun dir(context: Context, id: String): File {
        requireSinglePathComponent(id, "노트를 찾을 수 없어요")
        return File(root(context), id)
    }
    fun basePdf(context: Context, id: String) = File(dir(context, id), "base.pdf")
    fun metaFile(context: Context, id: String) = File(dir(context, id), "meta.json")
    fun inkDir(context: Context, id: String) = File(dir(context, id), "ink")
    fun inkFile(context: Context, id: String, uid: String): File {
        requireSinglePathComponent(uid, "필기 페이지가 올바르지 않아요")
        return File(inkDir(context, id), "$uid.json")
    }
    fun thumbFile(context: Context, id: String) = File(dir(context, id), "thumb.png")

    /** The PDF file behind page [page] of notebook [id]. */
    fun sourceFile(context: Context, id: String, page: PageInfo): File {
        val name = PageSources.nameOf(page)
        requireSinglePathComponent(name, "PDF 원본 경로가 올바르지 않아요")
        return File(dir(context, id), name)
    }

    fun hasSafePagePaths(page: PageInfo): Boolean =
        isSinglePathComponent(page.uid) && (page.src == null || isSinglePathComponent(page.src))

    /** Copies [pdf] into notebook [id]'s folder as a page source (unless already there). */
    fun storeSource(context: Context, id: String, pdf: File): String {
        NoteStore.ensureNotDeleted(id)
        val crc = CRC32()
        pdf.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                crc.update(buffer, 0, count)
            }
        }
        val name = SOURCE_PREFIX + java.lang.Long.toHexString(crc.value) + "-" + pdf.length().toString(36) + ".pdf"
        val target = File(dir(context, id).apply { mkdirs() }, name)
        if (!(target.exists() && target.length() == pdf.length())) {
            val tmp = File(target.parentFile, "$name.tmp")
            pdf.inputStream().use { input -> FileOutputStream(tmp).use { input.copyTo(it); it.fd.sync() } }
            if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) throw IOException("could not write $name") }
        }
        return name
    }

    fun writeAtomic(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun requireSinglePathComponent(value: String, message: String) {
        if (!isSinglePathComponent(value)) throw IOException(message)
    }

    private fun isSinglePathComponent(value: String): Boolean =
        value.isNotBlank() && value != "." && value != ".." &&
            value.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' }
}
