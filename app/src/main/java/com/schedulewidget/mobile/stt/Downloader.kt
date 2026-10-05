package com.schedulewidget.mobile.stt

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** A download failure with a message that can be shown to the user as-is. */
internal class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Resumable HTTP downloads (Range requests into "<name>.part"); archive extraction is delegated. */
internal object Downloader {
    internal const val BUFFER = 256 * 1024
    private const val MAX_REDIRECTS = 10

    /**
     * Downloads [url] to [dest], resuming from "[dest].part" when the server honours Range.
     * [onProgress] gets the bytes of this file on disk so far. [dest] only appears once complete.
     */
    suspend fun fetch(url: String, dest: File, expectedBytes: Long, onProgress: (Long) -> Unit) {
        if (dest.isFile && dest.length() > 0) { onProgress(dest.length()); return }
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                fetchOnce(url, part, expectedBytes, onProgress)
                break
            } catch (e: DownloadException) {
                throw e
            } catch (e: IOException) {
                // Flaky mobile networks: retry a few times, each retry resumes where the last one stopped.
                if (++attempt >= 4) throw DownloadException("다운로드가 끊겼어요. 네트워크를 확인하고 다시 시도해 주세요", e)
                kotlinx.coroutines.delay(2_000L * attempt)
            }
        }
        if (!part.renameTo(dest)) throw DownloadException("파일을 저장하지 못했어요")
    }

    private suspend fun fetchOnce(url: String, part: File, expectedBytes: Long, onProgress: (Long) -> Unit) {
        var have = if (part.isFile) part.length() else 0L
        val conn = open(url, have)
        try {
            val code = conn.responseCode
            if (code == 416) {
                // Range past the end: the partial file is stale (or already complete but unverified). Start over.
                part.delete()
                conn.disconnect()
                return fetchOnce(url, part, expectedBytes, onProgress)
            }
            if (code != 200 && code != 206) throw DownloadException("다운로드 실패 (서버 응답 $code)")
            val type = conn.contentType.orEmpty().lowercase()
            if (type.startsWith("text/html")) throw DownloadException("서버가 파일 대신 오류 페이지를 보냈어요. 잠시 후 다시 시도해 주세요")
            val total: Long
            if (code == 206) {
                // Content-Range: bytes <start>-<end>/<total>
                val range = conn.getHeaderField("Content-Range").orEmpty()
                val start = Regex("""bytes (\d+)-""").find(range)?.groupValues?.get(1)?.toLongOrNull()
                if (start != have) { part.delete(); have = 0L; throw IOException("unexpected Content-Range $range") }
                total = range.substringAfterLast('/').toLongOrNull() ?: (have + conn.contentLengthLong)
            } else {
                have = 0L
                total = conn.contentLengthLong
            }
            if (total in 1 until expectedBytes / 2) {
                throw DownloadException("받은 파일 크기가 이상해요 (${total} 바이트). 잠시 후 다시 시도해 주세요")
            }
            conn.inputStream.use { input -> copy(input, part, append = code == 206, have, onProgress) }
            val size = part.length()
            if (total > 0 && size != total) throw IOException("incomplete: $size / $total")
            if (size < expectedBytes / 2) { part.delete(); throw DownloadException("받은 파일이 너무 작아요. 잠시 후 다시 시도해 주세요") }
            if (looksLikeHtml(part)) { part.delete(); throw DownloadException("서버가 파일 대신 오류 페이지를 보냈어요. 잠시 후 다시 시도해 주세요") }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun copy(input: InputStream, part: File, append: Boolean, start: Long, onProgress: (Long) -> Unit) {
        val buf = ByteArray(BUFFER)
        var done = start
        var lastReport = 0L
        FileOutputStream(part, append).use { out ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                val now = System.nanoTime()
                if (now - lastReport > 200_000_000L) { lastReport = now; onProgress(done) }
            }
        }
        onProgress(done)
    }

    /** Opens [url] with a Range header, following redirects by hand so the header survives each hop. */
    private fun open(url: String, from: Long): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "ScheduleWidget-mobile")
            c.setRequestProperty("Accept-Encoding", "identity")
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val location = c.getHeaderField("Location") ?: throw DownloadException("다운로드 주소가 잘못됐어요")
                c.disconnect()
                current = URL(current, location)
            } else {
                return c
            }
        }
        throw DownloadException("다운로드 주소가 너무 여러 번 바뀌었어요")
    }

    private fun looksLikeHtml(file: File): Boolean {
        val head = ByteArray(64)
        val n = file.inputStream().use { it.read(head) }
        if (n <= 0) return false
        val text = String(head, 0, n, Charsets.ISO_8859_1).trimStart().lowercase()
        return text.startsWith("<!doctype html") || text.startsWith("<html")
    }


    /** Keep the original entry point; archive extraction is owned by ArchiveExtractor. */
    suspend fun extractTarBz2(archive: File, dir: File, keep: List<String>, onProgress: (Long) -> Unit) =
        ArchiveExtractor.extractTarBz2(archive, dir, keep, onProgress)
}
