package com.schedulewidget.mobile.notes.editor

import android.graphics.Matrix
import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.render.PdfDoc
import java.io.Closeable
import java.io.File
import java.nio.file.Files

/** Where a page's PDF comes from and how it is rotated onto the page. */
object PageSources {
    const val BASE = "base.pdf"

    /** File name (in the notebook folder) of the PDF behind [page]. */
    fun nameOf(page: PageInfo): String = page.src ?: BASE

    /** A notebook-local PDF, or null for an unsafe name or symbolic link. */
    fun fileOf(dir: File, page: PageInfo): File? {
        val name = nameOf(page)
        if (name.isBlank() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == ':' || it == '\u0000' }) return null
        if (Files.isSymbolicLink(dir.toPath())) return null
        return File(dir, name).takeUnless { Files.isSymbolicLink(it.toPath()) }
    }

    /**
     * Matrix mapping points of the source PDF page to bitmap pixels of [page] as shown ([PageInfo.w] × [PageInfo.h]
     * are already rotated), at [sx] × [sy] pixels per point.
     */
    fun pdfMatrix(page: PageInfo, sx: Float, sy: Float): Matrix {
        val m = Matrix()
        when (PageOps.normRot(page.rot)) {
            90 -> { m.setRotate(90f); m.postTranslate(page.w, 0f) }
            180 -> { m.setRotate(180f); m.postTranslate(page.w, page.h) }
            270 -> { m.setRotate(270f); m.postTranslate(0f, page.h) }
        }
        m.postScale(sx, sy)
        return m
    }
}

/**
 * Open PDFs of one notebook folder, by file name, opened on first use. Every call must run on
 * [com.schedulewidget.mobile.notes.render.PdfThread]. Files that fail to open are remembered (not retried).
 */
class PdfPool(private val dir: File?) : Closeable {
    private val docs = HashMap<String, PdfDoc?>()
    private var closed = false

    fun doc(page: PageInfo): PdfDoc? {
        if (closed) return null
        val d = dir ?: return null
        val name = PageSources.nameOf(page)
        return docs.getOrPut(name) {
            val f = PageSources.fileOf(d, page)
            if (f?.isFile == true) runCatching { PdfDoc(f) }.getOrNull() else null
        }
    }

    override fun close() {
        closed = true
        docs.values.forEach { runCatching { it?.close() } }
        docs.clear()
    }
}
