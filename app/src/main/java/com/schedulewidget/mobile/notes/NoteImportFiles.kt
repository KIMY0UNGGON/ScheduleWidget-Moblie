package com.schedulewidget.mobile.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.schedulewidget.mobile.notes.importer.FileKind
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt

internal object NoteImportFiles {
    // Matches FlexcilArchive's existing 1 GiB per-entry ceiling; seekable backups avoid a second cache copy.
    const val MAX_IMPORT_BYTES = 1L shl 30
    const val MAX_FLEX_IMPORT_BYTES = 4L shl 30

    /** Copies by actual bytes read; false means the next chunk would exceed [limit]. */
    internal fun copyLimited(input: InputStream, output: OutputStream, limit: Long, checkActive: () -> Unit = {}): Boolean {
        require(limit >= 0)
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkActive()
            val count = try { input.read(buffer) } catch (e: IOException) { checkActive(); throw e }
            checkActive()
            if (count < 0) return true
            if (count == 0) {
                val byte = try { input.read() } catch (e: IOException) { checkActive(); throw e }
                checkActive()
                if (byte < 0) return true
                if (total == limit) return false
                output.write(byte)
                total++
                continue
            }
            if (count.toLong() > limit - total) return false
            output.write(buffer, 0, count)
            total += count
        }
    }

    /** Throws a user-facing error unless [pdf] opens with PdfRenderer and has pages. */
    fun checkPdf(pdf: File) {
        if (pdf.length() > MAX_IMPORT_BYTES) throw NoteImport.ImportError("파일이 1GB보다 커서 가져오지 못했어요")
        val pages = try {
            val fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try { PdfRenderer(fd) } catch (e: Throwable) { fd.close(); throw e }
            try { renderer.pageCount } finally { renderer.close() }
        } catch (e: SecurityException) {
            throw NoteImport.ImportError("암호가 걸린 PDF는 열 수 없어요")
        } catch (e: IOException) {
            throw NoteImport.ImportError("PDF 파일이 손상되어 열 수 없어요")
        }
        if (pages <= 0) throw NoteImport.ImportError("페이지가 없는 PDF예요")
        if (pages > FileKind.MAX_IMPORT_PAGES) throw NoteImport.ImportError("PDF 페이지가 너무 많아요 (최대 ${FileKind.MAX_IMPORT_PAGES}쪽)")
    }

    /** One page whose size follows the photo (long edge = A4 long edge), EXIF rotation applied. */
    fun imageToPdf(input: File, out: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw NoteImport.ImportError("사진을 읽을 수 없어요")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_PX) sample *= 2
        var bmp = BitmapFactory.decodeFile(input.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw NoteImport.ImportError("사진을 읽을 수 없어요")
        val degrees = runCatching {
            when (ExifInterface(input.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (degrees != 0f) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        val long = 842f
        val (pw, ph) = if (bmp.width >= bmp.height) long to long * bmp.height / bmp.width else long * bmp.width / bmp.height to long
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pw.roundToInt().coerceAtLeast(1), ph.roundToInt().coerceAtLeast(1), 1).create())
            page.canvas.drawBitmap(bmp, null, RectF(0f, 0f, pw.roundToInt().toFloat(), ph.roundToInt().toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            doc.finishPage(page)
            out.outputStream().buffered().use { doc.writeTo(it) }
        } finally {
            doc.close()
            bmp.recycle()
        }
    }

    private const val MAX_IMAGE_PX = 2400

    fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }
}
