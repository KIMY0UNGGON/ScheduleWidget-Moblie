package com.schedulewidget.mobile.notes.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import com.schedulewidget.mobile.notes.NoteStore
import com.schedulewidget.mobile.notes.NoteImport
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.privacy.GoogleDocumentConsent
import com.schedulewidget.mobile.notes.importer.DocxRenderer
import com.schedulewidget.mobile.notes.importer.DriveConvert
import com.schedulewidget.mobile.notes.importer.FileKind
import com.schedulewidget.mobile.notes.importer.PptxRenderer
import com.schedulewidget.mobile.notes.importer.ZipPartSource
import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.pet.DrivePets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turning picked files into pages for an open notebook ("다른 파일에서 삽입"): PDFs as they are, photos as one page
 * each, PPTX / DOCX through the converters selected in settings. A Drive grant and document-upload consent are both
 * required before cloud conversion. Errors are IOExceptions with a message for the user.
 */
internal object PageImport {
    /** A PDF ready to insert from: its source name in the notebook folder and page sizes. */
    class Ready(val name: String, val sizes: List<Pair<Float, Float>>, val title: String)

    private fun workDir(context: Context) = File(context.cacheDir, "note_insert/" + System.nanoTime()).apply { mkdirs() }

    /** Copies [uri] into notebook [noteId] as a page source, converting it to PDF first when needed. */
    suspend fun prepare(context: Context, noteId: String, uri: Uri, stage: (String) -> Unit): Ready {
        val app = context.applicationContext
        val work = withContext(Dispatchers.IO) { workDir(app) }
        try {
            stage("파일 읽는 중")
            val name = displayName(app, uri)
            val input = File(work, "input")
            val head = withContext(Dispatchers.IO) {
                (app.contentResolver.openInputStream(uri) ?: throw IOException("파일을 열 수 없어요")).use { src ->
                    input.outputStream().use { src.copyTo(it, 64 * 1024) }
                }
                if (input.length() == 0L) throw IOException("빈 파일이에요")
                input.inputStream().use { s -> ByteArray(1024).let { b -> val n = s.read(b); if (n <= 0) ByteArray(0) else b.copyOf(n) } }
            }
            val mime = runCatching { app.contentResolver.getType(uri) }.getOrNull()
            val kind = FileKind.detect(name, mime, head) { entry ->
                runCatching { ZipPartSource(input).use { it.has(entry) } }.getOrDefault(false)
            }
            val title = name?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() } ?: "가져온 페이지"
            val pdf = when (kind) {
                FileKind.PDF -> input
                FileKind.IMAGE -> File(work, "image.pdf").also { out ->
                    stage("페이지 만드는 중")
                    withContext(Dispatchers.IO) { imagesToPdf(listOf(input), out) }
                }
                FileKind.PPTX, FileKind.DOCX, FileKind.PPT, FileKind.DOC -> office(app, input, kind, title, work, stage)
                FileKind.FLEXCIL -> throw IOException("Flexcil 파일은 노트 목록에서 가져와 주세요")
                else -> throw IOException("지원하지 않는 파일이에요. PDF, PPT, Word, 사진을 넣을 수 있어요")
            }
            stage("페이지 준비 중")
            val (src, sizes) = NoteStore.addSourcePdf(app, noteId, pdf)
            return Ready(src, sizes, title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw IOException("파일이 너무 커서 넣지 못했어요")
        } catch (e: SecurityException) {
            throw IOException("파일을 읽을 권한이 없어요. 다시 선택해 주세요")
        } catch (e: Exception) {
            throw IOException("넣지 못했어요: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { work.deleteRecursively() }
        }
    }

    /** Photos [uris] as one page each (in that order), stored as a page source of notebook [noteId]. */
    suspend fun prepareImages(context: Context, noteId: String, uris: List<Uri>, stage: (String) -> Unit): Ready {
        val app = context.applicationContext
        val work = withContext(Dispatchers.IO) { workDir(app) }
        try {
            val files = uris.mapIndexed { i, uri ->
                stage("사진 읽는 중 (${i + 1}/${uris.size})")
                withContext(Dispatchers.IO) {
                    File(work, "img$i").also { f ->
                        (app.contentResolver.openInputStream(uri) ?: throw IOException("사진을 열 수 없어요")).use { s ->
                            f.outputStream().use { s.copyTo(it, 64 * 1024) }
                        }
                    }
                }
            }
            stage("페이지 만드는 중")
            val pdf = File(work, "images.pdf")
            withContext(Dispatchers.IO) { imagesToPdf(files, pdf) }
            val (src, sizes) = NoteStore.addSourcePdf(app, noteId, pdf)
            return Ready(src, sizes, "사진")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw IOException("사진이 너무 커서 넣지 못했어요")
        } catch (e: SecurityException) {
            throw IOException("사진을 읽을 권한이 없어요. 다시 선택해 주세요")
        } catch (e: Exception) {
            throw IOException("사진을 넣지 못했어요: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { work.deleteRecursively() }
        }
    }

    /** Pages [indices] of [ready] as new notebook pages (no handwriting). */
    fun pages(ready: Ready, indices: List<Int>): List<Pair<PageInfo, PageInk>> = indices.mapNotNull { i ->
        val (w, h) = ready.sizes.getOrNull(i) ?: return@mapNotNull null
        PageInfo(NoteStore.newPageUid(), PageInfo.KIND_PDF, pdf = i, w = w, h = h, src = ready.name) to PageInk.EMPTY
    }

    private suspend fun office(app: Context, input: File, kind: FileKind, title: String, work: File, stage: (String) -> Unit): File {
        val pdf = File(work, "converted.pdf")
        val mode = NoteImport.Convert.of(Repository.get(app).data.value.notes.convert)
        if (mode == NoteImport.Convert.OFFLINE && kind.isLegacy)
            throw IOException("폰에서는 예전 형식(.ppt/.doc)을 변환할 수 없어요. PPTX/DOCX로 저장하거나 구글 Drive 변환을 선택해 주세요")
        val token = if (mode == NoteImport.Convert.OFFLINE) null else try {
            (DrivePets.authorize(app) as? DrivePets.Auth.Token)?.value
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val allowed = token != null && GoogleDocumentConsent.awaitPermission(app, title)
        if (token != null && !allowed && mode == NoteImport.Convert.DRIVE)
            throw IOException("문서 업로드를 허용하지 않았어요. 폰에서 변환하려면 설정에서 변환 방식을 바꿔 주세요")
        if (token != null && allowed) {
            try {
                val cleaned = withContext(Dispatchers.IO) {
                    DriveConvert.toPdf(token, input, kind.mime, kind.isSlides, title, pdf, stage)
                }
                if (!cleaned) GoogleDocumentConsent.showCleanupWarning(app)
                return pdf
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (kind.isLegacy || mode == NoteImport.Convert.DRIVE) throw IOException("구글 드라이브로 변환하지 못했어요", e)
            }
        }
        if (mode == NoteImport.Convert.DRIVE && token == null)
            throw IOException("노트 목록에서 구글 Drive로 파일을 가져와 로그인한 뒤 다시 넣어 주세요")
        if (kind.isLegacy) throw IOException("예전 형식(.ppt/.doc)은 노트 목록에서 구글 드라이브로 가져와 주세요. PPTX/DOCX는 바로 넣을 수 있어요")
        stage("페이지 만드는 중")
        val ctx = currentCoroutineContext()
        val n = withContext(Dispatchers.IO) {
            ZipPartSource(input).use { src ->
                if (kind.isSlides) PptxRenderer.render(src, pdf) { i, total -> ctx.ensureActive(); stage("페이지 만드는 중 ($i/$total)") }
                else DocxRenderer(src) { k -> ctx.ensureActive(); stage("페이지 만드는 중 (${k}쪽)") }.render(pdf)
            }
        }
        if (n == 0) throw IOException("변환할 내용이 없어요")
        return pdf
    }

    private const val MAX_IMAGE_PX = 2400

    /** One page per photo, sized like the photo (long edge = A4 long edge), EXIF rotation applied. */
    private fun imagesToPdf(inputs: List<File>, out: File) {
        val doc = PdfDocument()
        try {
            var n = 0
            for (input in inputs) {
                val bmp = decode(input) ?: continue
                try {
                    val long = PageInfo.A4_H
                    val (pw, ph) = if (bmp.width >= bmp.height) long to long * bmp.height / bmp.width
                    else long * bmp.width / bmp.height to long
                    val w = pw.roundToInt().coerceAtLeast(1); val h = ph.roundToInt().coerceAtLeast(1)
                    val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, ++n).create())
                    page.canvas.drawBitmap(bmp, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                    doc.finishPage(page)
                } finally {
                    bmp.recycle()
                }
            }
            if (n == 0) throw IOException("사진을 읽을 수 없어요")
            out.outputStream().buffered().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    private fun decode(input: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_PX) sample *= 2
        val bmp = BitmapFactory.decodeFile(input.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val degrees = runCatching {
            when (ExifInterface(input.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (degrees == 0f) return bmp
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    private fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }
}
