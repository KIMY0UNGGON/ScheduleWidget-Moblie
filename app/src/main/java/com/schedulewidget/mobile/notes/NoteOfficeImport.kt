package com.schedulewidget.mobile.notes

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.schedulewidget.mobile.notes.importer.DocxRenderer
import com.schedulewidget.mobile.notes.importer.DriveConvert
import com.schedulewidget.mobile.notes.importer.FileKind
import com.schedulewidget.mobile.notes.importer.PptxRenderer
import com.schedulewidget.mobile.notes.importer.ZipPartSource
import com.schedulewidget.mobile.privacy.GoogleDocumentConsent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

/** Converts Office input to a validated PDF, leaving notebook creation to [NoteImport]. */
internal object NoteOfficeImport {
    class Result(val pdf: File, val message: String)

    suspend fun convert(
        app: Context, input: File, kind: FileKind, title: String, convert: NoteImport.Convert, work: File,
        stage: (String) -> Unit, driveToken: suspend (Context, Boolean) -> String?,
    ): Result {
        val pdf = File(work, "converted.pdf")
        if (convert == NoteImport.Convert.OFFLINE && kind.isLegacy) {
            throw NoteImport.ImportError("예전 형식(.ppt/.doc)은 오프라인 변환을 지원하지 않아요. PPTX/DOCX로 저장하거나 구글 드라이브 변환을 선택해 주세요")
        }
        var why: String? = null
        if (convert != NoteImport.Convert.OFFLINE) {
            val interactive = convert == NoteImport.Convert.DRIVE || kind.isLegacy
            var token = driveToken(app, interactive)
            if (token == null) {
                if (kind.isLegacy) throw NoteImport.ImportError("예전 형식(.ppt/.doc)은 구글 드라이브로만 변환할 수 있어요. 구글 드라이브 권한을 허용하거나 PPTX/DOCX로 저장해서 가져와 주세요")
                if (convert == NoteImport.Convert.DRIVE) throw NoteImport.ImportError("노트 목록에서 구글 Drive로 파일을 가져와 로그인한 뒤 다시 시도해 주세요")
                why = "구글 드라이브를 쓸 수 없어서"
            } else {
                stage("문서 전송 권한 확인 중")
                val uploadAllowed = GoogleDocumentConsent.awaitPermission(app, title)
                if (!uploadAllowed) {
                    if (kind.isLegacy || convert == NoteImport.Convert.DRIVE) {
                        throw NoteImport.ImportError("구글 드라이브 변환을 허용하지 않았어요")
                    }
                    why = "구글 문서 전송을 허용하지 않아"
                }
            }
            var retried = false
            while (token != null && GoogleDocumentConsent.isAllowed(app)) {
                try {
                    val cleaned = DriveConvert.toPdf(token, input, kind.mime, kind.isSlides, title, pdf, stage)
                    NoteImportFiles.checkPdf(pdf)
                    if (!cleaned) GoogleDocumentConsent.showCleanupWarning(app)
                    return Result(pdf, "구글 드라이브로 변환했어요")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DriveConvert.DriveError) {
                    if (e.auth && !retried) {
                        retried = true
                        runCatching { GoogleAuthUtil.clearToken(app, token!!) }
                        token = driveToken(app, interactive)
                        if (token == null && (kind.isLegacy || convert == NoteImport.Convert.DRIVE)) {
                            throw NoteImport.ImportError("구글 드라이브 권한을 확인하지 못했어요. 다시 시도해 주세요")
                        }
                        continue
                    }
                    if (kind.isLegacy || convert == NoteImport.Convert.DRIVE) throw NoteImport.ImportError(e.message.orEmpty())
                    why = "구글 드라이브 변환이 안 돼서"
                    token = null
                } catch (e: DriveConvert.ExportTooLarge) {
                    if (kind.isLegacy || convert == NoteImport.Convert.DRIVE) throw NoteImport.ImportError("파일이 커서 구글 드라이브에서 PDF로 바꿀 수 없어요 (약 10MB 제한)")
                    why = "파일이 커서 구글 드라이브 대신"
                    token = null
                } catch (e: NoteImport.ImportError) {
                    throw e
                } catch (e: IOException) {
                    if (kind.isLegacy || convert == NoteImport.Convert.DRIVE) throw NoteImport.ImportError("구글 드라이브에 연결하지 못했어요. 인터넷 연결을 확인해 주세요")
                    why = "구글 드라이브에 연결하지 못해서"
                    token = null
                }
            }
            if (token != null && !GoogleDocumentConsent.isAllowed(app)) {
                if (kind.isLegacy || convert == NoteImport.Convert.DRIVE) throw NoteImport.ImportError("구글 드라이브 변환을 허용하지 않았어요")
                why = "구글 문서 전송을 허용하지 않아"
            }
        }
        val pages = renderOffline(input, kind, pdf, stage)
        if (pages == 0) throw NoteImport.ImportError("변환할 내용이 없어요")
        NoteImportFiles.checkPdf(pdf)
        return Result(pdf, listOfNotNull(why, "폰에서 변환했어요 (모양이 조금 다를 수 있어요)").joinToString(" "))
    }

    private suspend fun renderOffline(input: File, kind: FileKind, out: File, stage: (String) -> Unit): Int {
        stage("페이지 만드는 중")
        val ctx = currentCoroutineContext()
        return try {
            ZipPartSource(input).use { src ->
                if (kind.isSlides) {
                    PptxRenderer.render(src, out) { i, n -> ctx.ensureActive(); stage("페이지 만드는 중 ($i/$n)") }
                } else {
                    DocxRenderer(src) { n -> ctx.ensureActive(); stage("페이지 만드는 중 (${n}쪽)") }.render(out)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw NoteImport.ImportError("파일이 너무 커서 폰에서 변환하지 못했어요")
        } catch (e: Exception) {
            throw NoteImport.ImportError("폰에서 변환하지 못했어요 (${e.message ?: e.javaClass.simpleName})")
        }
    }
}
