package com.schedulewidget.mobile.notes.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.notes.ink.StoredNote
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Flattened PDF export and library thumbnails (run on [PdfThread]). */
object NoteExport {
    /** PDF pages are embedded as images of about this many pixels, at most 300 dpi. */
    private const val EXPORT_PIXELS = 4_000_000f
    private const val MAX_SCALE = 300f / 72f

    /**
     * Writes [note] as a PDF to [out]: original PDF pages as images with the handwriting drawn over them as vectors;
     * blank pages as vector templates + handwriting. [inkOf] gives each page's ink; [progress] gets (done, total).
     */
    suspend fun writePdf(
        note: StoredNote, basePdf: File?, out: OutputStream,
        inkOf: (PageInfo) -> PageInk, progress: (Int, Int) -> Unit = { _, _ -> },
        /** The pages to write (a subset of [note]'s for "선택한 페이지 내보내기"). */
        pages: List<PageInfo> = note.pages,
    ) = withContext(PdfThread.dispatcher) {
        val doc = PdfDocument()
        // Pages may come from several PDFs in the notebook folder (PageInfo.src) and be rotated (PageInfo.rot).
        val sources = com.schedulewidget.mobile.notes.editor.PdfPool(basePdf?.parentFile)
        val filter = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        try {
            pages.filter { it.kind == PageInfo.KIND_PDF }.forEach { page ->
                val src = sources.doc(page) ?: throw IOException("원본 PDF를 찾을 수 없어요")
                if (page.pdf !in 0 until src.pageCount) throw IOException("원본 PDF 페이지를 찾을 수 없어요")
            }
            pages.forEachIndexed { i, page ->
                val src = if (page.isPdf) sources.doc(page) else null
                coroutineContext.ensureActive()
                val info = PdfDocument.PageInfo.Builder(page.w.roundToInt().coerceAtLeast(1), page.h.roundToInt().coerceAtLeast(1), i + 1).create()
                val p = doc.startPage(info)
                val c = p.canvas
                c.drawColor(Color.WHITE)
                if (page.isPdf && src != null && page.pdf < src.pageCount) {
                    val (bw, bh) = rasterSize(page.w, page.h)
                    val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    src.render(page.pdf, bmp, com.schedulewidget.mobile.notes.editor.PageSources.pdfMatrix(page, bw / page.w, bh / page.h))
                    c.drawBitmap(bmp, null, RectF(0f, 0f, page.w, page.h), filter)
                    bmp.recycle()
                } else {
                    PageDraw.drawTemplate(c, page, 1f)
                }
                c.save()
                c.clipRect(0f, 0f, page.w, page.h)
                PageDraw.drawInk(c, inkOf(page), cache = false)
                c.restore()
                doc.finishPage(p)
                progress(i + 1, pages.size)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
            sources.close()
        }
    }

    internal fun rasterSize(widthPt: Float, heightPt: Float): Pair<Int, Int> {
        val scale = sqrt(EXPORT_PIXELS / (widthPt * heightPt)).coerceAtMost(MAX_SCALE)
        return (widthPt * scale).roundToInt().coerceAtLeast(1) to (heightPt * scale).roundToInt().coerceAtLeast(1)
    }

    /** A [widthPx]-wide image of the first page with its handwriting, or null when the notebook has no pages. */
    suspend fun thumbnail(note: StoredNote, basePdf: File?, ink: PageInk, widthPx: Int): Bitmap? = withContext(PdfThread.dispatcher) {
        val page = note.pages.firstOrNull() ?: return@withContext null
        val scale = widthPx / page.w
        val h = (page.h * scale).roundToInt().coerceIn(1, widthPx * 4)
        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        if (page.isPdf && basePdf != null) {
            // [basePdf]'s folder holds the page's own source file (PageInfo.src); rotation as in the editor.
            val f = File(basePdf.parentFile, com.schedulewidget.mobile.notes.editor.PageSources.nameOf(page))
            if (f.exists()) runCatching { PdfDoc(f).use { it.render(page.pdf, bmp, com.schedulewidget.mobile.notes.editor.PageSources.pdfMatrix(page, scale, scale)) } }
        }
        val c = Canvas(bmp)
        c.scale(scale, scale)
        PageDraw.drawTemplate(c, page, scale)
        PageDraw.drawInk(c, ink, cache = false)
        bmp
    }
}
