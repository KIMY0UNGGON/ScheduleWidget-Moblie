package com.schedulewidget.mobile.notes.importer

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.OffsetDateTime

/**
 * PPT/Word -> PDF through Google Drive: upload with conversion to Google Slides/Docs, export as PDF, then best-effort
 * delete the Drive copy. A failed cleanup can leave that converted file in the user's Drive until a later authorized
 * conversion retries it. Uses the app's existing drive.file access (pet.DrivePets.authorize), which covers files this app creates.
 */
internal object DriveConvert {
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id"
    private const val FILES = "https://www.googleapis.com/drive/v3/files"
    private const val TEMP_KEY = "scheduleWidgetTemporaryConversion"
    private const val TEMP_VALUE = "1"
    // Conversion requests can take up to six minutes; don't delete a file another device may still be converting.
    private const val TEMP_STALE_AFTER_MS = 10 * 60 * 1000L
    private val mutex = Mutex()

    /** Drive refused the export (Google's limit for exported files is about 10 MB). */
    class ExportTooLarge : IOException("구글 드라이브의 변환 크기 제한(약 10MB)을 넘었어요")

    /** Drive answered but refused (no API access, quota, ...); [auth] = token expired / revoked. */
    class DriveError(message: String, val auth: Boolean = false) : IOException(message)

    /** Google editor type the file is converted into. */
    fun googleMimeFor(slides: Boolean) =
        if (slides) "application/vnd.google-apps.presentation" else "application/vnd.google-apps.document"

    /** Returns false when a marked temporary Drive copy may remain after retrying cleanup. */
    suspend fun toPdf(token: String, input: File, inputMime: String, slides: Boolean, title: String, out: File, stage: (String) -> Unit): Boolean = mutex.withLock {
        stage("구글 드라이브에 올리는 중")
        val oldCopiesClean = try {
            cleanupPending(token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        currentCoroutineContext().ensureActive()
        val id = upload(token, input, inputMime, googleMimeFor(slides), title)
        var currentCopyClean = false
        try {
            currentCoroutineContext().ensureActive()
            stage("구글 드라이브에서 변환 중")
            export(token, id, out)
        } finally {
            currentCopyClean = deleteWithRetry(token, id)
        }
        oldCopiesClean && currentCopyClean
    }

    private fun upload(token: String, input: File, inputMime: String, googleMime: String, title: String): String {
        val meta = JSONObject().put("name", title.ifBlank { "노트 변환" }).put("mimeType", googleMime)
            .put("appProperties", JSONObject().put(TEMP_KEY, TEMP_VALUE))
        val boundary = "sw" + java.util.UUID.randomUUID().toString().replace("-", "")
        val head = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$boundary\r\nContent-Type: $inputMime\r\n\r\n").toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val conn = open(token, "POST", UPLOAD)
        conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        conn.doOutput = true
        conn.readTimeout = 180_000 // conversion happens during the upload request
        conn.setFixedLengthStreamingMode(head.size.toLong() + input.length() + tail.size)
        try {
            conn.outputStream.use { os ->
                os.write(head)
                input.inputStream().use { it.copyTo(os, 64 * 1024) }
                os.write(tail)
            }
            val code = conn.responseCode
            if (code !in 200..299) fail(conn)
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text).getString("id")
        } finally {
            conn.disconnect()
        }
    }

    private fun export(token: String, id: String, out: File) {
        val conn = open(token, "GET", "$FILES/${enc(id)}/export?mimeType=application%2Fpdf")
        conn.readTimeout = 180_000
        try {
            val code = conn.responseCode
            if (code !in 200..299) fail(conn)
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it, 64 * 1024) } }
        } finally {
            conn.disconnect()
        }
    }

    private fun delete(token: String, id: String) {
        val conn = open(token, "DELETE", "$FILES/${enc(id)}")
        try {
            val code = conn.responseCode
            if (code !in 200..299 && code != 404) fail(conn)
        } finally { conn.disconnect() }
    }

    /** A timed-out upload may have created a file before returning its id; find only this app's marked temp files. */
    private suspend fun cleanupPending(token: String): Boolean {
        val q = "appProperties has { key='${TEMP_KEY}' and value='${TEMP_VALUE}' } and trashed=false"
        val url = "$FILES?pageSize=1000&fields=nextPageToken,files(id,createdTime)&q=" + enc(q)
        val conn = open(token, "GET", url)
        val json = try {
            val code = conn.responseCode
            if (code !in 200..299) fail(conn)
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
        val files = json.optJSONArray("files") ?: return true
        var allDeleted = true
        for (i in 0 until files.length()) {
            currentCoroutineContext().ensureActive()
            val file = files.optJSONObject(i) ?: continue
            val id = file.optString("id").takeIf { it.isNotBlank() } ?: continue
            val created = runCatching { OffsetDateTime.parse(file.getString("createdTime")).toInstant().toEpochMilli() }.getOrNull()
            if (created == null || System.currentTimeMillis() - created < TEMP_STALE_AFTER_MS) {
                allDeleted = false
                continue
            }
            if (!deleteWithRetry(token, id)) allDeleted = false
        }
        // Do not page while deleting: Drive may reshuffle results. A later conversion starts at the first remaining page.
        return allDeleted && json.optString("nextPageToken").isBlank()
    }

    private fun deleteWithRetry(token: String, id: String): Boolean {
        repeat(2) {
            try {
                delete(token, id)
                return true
            } catch (_: Exception) {
                // The marker remains on Drive, so the next authorized conversion can try again.
            }
        }
        return false
    }

    private fun open(token: String, method: String, url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        setRequestProperty("Authorization", "Bearer $token")
        connectTimeout = 15_000
        readTimeout = 60_000
    }

    private fun fail(conn: HttpURLConnection): Nothing {
        val code = conn.responseCode
        val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val err = runCatching { JSONObject(body).getJSONObject("error") }.getOrNull()
        val reason = err?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
        val message = err?.optString("message").orEmpty()
        if (reason == "exportSizeLimitExceeded" || message.contains("too large", ignoreCase = true)) throw ExportTooLarge()
        throw when (code) {
            401 -> DriveError("구글 로그인이 만료되었어요. 다시 시도해 주세요.", auth = true)
            403 -> DriveError("구글 드라이브를 사용할 수 없어요: ${message.ifBlank { "권한 없음" }}")
            413 -> DriveError("파일이 너무 커서 구글 드라이브에 올릴 수 없어요.")
            else -> DriveError("구글 드라이브 오류 $code${if (message.isNotBlank()) ": $message" else ""}")
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
