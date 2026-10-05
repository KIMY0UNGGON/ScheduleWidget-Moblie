package com.schedulewidget.mobile.notes.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/**
 * The one thread all PdfRenderer work runs on (editor pages, thumbnails, export): pdfium is not thread-safe, and one
 * serialized queue also keeps memory use predictable.
 */
object PdfThread {
    val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "notes-pdf").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}

/** An open PDF. Every call must run on [PdfThread.dispatcher]. */
class PdfDoc(file: File) : Closeable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(fd) } catch (e: Exception) { fd.close(); throw e }
    private var closed = false

    val pageCount: Int get() = renderer.pageCount

    /** Page sizes in PDF points. */
    fun pageSizes(): List<Pair<Float, Float>> = (0 until pageCount).map { i ->
        renderer.openPage(i).use { p -> p.width.toFloat() to p.height.toFloat() }
    }

    /**
     * Renders page [index] onto [bitmap] (filled white first: many PDFs have a transparent background).
     * [transform] maps page points (top-left origin) to bitmap pixels; null = fit the whole page.
     */
    fun render(index: Int, bitmap: Bitmap, transform: Matrix?) {
        if (closed) return
        bitmap.eraseColor(Color.WHITE)
        renderer.openPage(index).use { p -> p.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { renderer.close() }
        runCatching { fd.close() }
    }
}
