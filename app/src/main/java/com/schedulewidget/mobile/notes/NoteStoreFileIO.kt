package com.schedulewidget.mobile.notes

import android.content.Context
import com.schedulewidget.mobile.notes.editor.PageSources
import com.schedulewidget.mobile.notes.ink.PageInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Paths and atomic byte/file writes for the notebook store. */
internal object NoteStoreFileIO {
    private const val SOURCE_PREFIX = "src-"

    fun root(context: Context) = checked(File(context.filesDir, "notes"))
    fun dir(context: Context, id: String): File {
        requireSinglePathComponent(id, "노트를 찾을 수 없어요")
        return checked(File(root(context), id))
    }
    fun basePdf(context: Context, id: String) = child(dir(context, id), "base.pdf")
    fun metaFile(context: Context, id: String) = child(dir(context, id), "meta.json")
    fun inkDir(context: Context, id: String) = child(dir(context, id), "ink")
    fun inkFile(context: Context, id: String, uid: String): File {
        requireSinglePathComponent(uid, "필기 페이지가 올바르지 않아요")
        return child(inkDir(context, id), "$uid.json")
    }
    fun thumbFile(context: Context, id: String) = child(dir(context, id), "thumb.png")

    /** The PDF file behind page [page] of notebook [id]. */
    fun sourceFile(context: Context, id: String, page: PageInfo): File {
        val name = PageSources.nameOf(page)
        requireSinglePathComponent(name, "PDF 원본 경로가 올바르지 않아요")
        return child(dir(context, id), name)
    }

    fun hasSafePagePaths(page: PageInfo): Boolean =
        isSinglePathComponent(page.uid) && (page.src == null || isSinglePathComponent(page.src))

    /** Copies [pdf] into notebook [id]'s folder as a page source (unless already there). */
    fun storeSource(context: Context, id: String, pdf: File): String {
        NoteStore.ensureNotDeleted(id)
        val noteDir = dir(context, id).apply { mkdirs() }
        if (!noteDir.isDirectory) throw IOException("노트 저장 공간을 만들 수 없어요")
        val tmp = Files.createTempFile(noteDir.toPath(), SOURCE_PREFIX, ".tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val size = pdf.inputStream().buffered().use { input ->
                FileOutputStream(tmp.toFile()).use { output ->
                    val count = DigestInputStream(input, digest).copyTo(output, 64 * 1024)
                    output.fd.sync()
                    count
                }
            }
            val name = sourceName(digest.digest(), size)
            val target = child(noteDir, name)
            if (target.isFile && target.length() == size) {
                Files.deleteIfExists(tmp)
            } else {
                Files.move(tmp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            return name
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    internal fun sourceName(sha256: ByteArray, size: Long): String {
        require(sha256.size == 32)
        val hash = sha256.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "$SOURCE_PREFIX$hash-${size.toString(36)}.pdf"
    }

    fun writeAtomic(file: File, bytes: ByteArray) {
        val parent = file.parentFile ?: throw IOException("file has no parent")
        val tmp = Files.createTempFile(parent.toPath(), "${file.name}.", ".tmp")
        try {
            FileOutputStream(tmp.toFile()).use { it.write(bytes); it.fd.sync() }
            Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun child(parent: File, name: String): File = checked(File(parent, name))

    private fun checked(file: File): File {
        if (Files.isSymbolicLink(file.toPath())) throw IOException("노트 저장 경로에 심볼릭 링크가 있어요")
        return file
    }

    private fun requireSinglePathComponent(value: String, message: String) {
        if (!isSinglePathComponent(value)) throw IOException(message)
    }

    private fun isSinglePathComponent(value: String): Boolean =
        value.isNotBlank() && value != "." && value != ".." &&
            value.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' }
}
