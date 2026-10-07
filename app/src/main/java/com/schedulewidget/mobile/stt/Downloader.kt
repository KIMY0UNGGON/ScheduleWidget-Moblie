package com.schedulewidget.mobile.stt

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A download failure with a message that can be shown to the user as-is. */
internal class DownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Resumable HTTP downloads (Range requests into "<name>.part"); archive extraction is delegated. */
internal object Downloader {
    internal const val BUFFER = 256 * 1024
    private const val MAX_REDIRECTS = 10
    private val DOWNLOAD_HOSTS = setOf(
        "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com", "github-releases.githubusercontent.com",
        "huggingface.co", "cdn-lfs.huggingface.co", "cdn-lfs.hf.co", "cdn-lfs-us-1.hf.co", "cdn-lfs-eu-1.hf.co",
        "cas-bridge.xethub.hf.co", "cas-server.xethub.hf.co", "cas-server.xethub-eu.hf.co",
        "transfer.xethub.hf.co", "transfer.xethub-eu.hf.co", "us.aws.cdn.hf.co", "us.gcp.cdn.hf.co",
    )
    private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+)""")

    /**
     * Downloads [url] to [dest], resuming from "[dest].part" when the server honours Range.
     * [onProgress] gets the bytes of this file on disk so far. [dest] only appears once complete.
     */
    suspend fun fetch(url: String, dest: File, expectedBytes: Long, sha256: String, onProgress: (Long) -> Unit) {
        if (expectedBytes <= 0 || !Regex("[0-9a-f]{64}").matches(sha256)) throw DownloadException("모델 정보가 올바르지 않아요")
        if (dest.isFile && verifyFile(dest, expectedBytes, sha256)) { onProgress(dest.length()); return }
        dest.delete()
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                fetchOnce(url, part, expectedBytes, onProgress)
                break
            } catch (e: DownloadException) {
                part.delete()
                throw e
            } catch (e: IOException) {
                // Flaky mobile networks: retry a few times, each retry resumes where the last one stopped.
                if (++attempt >= 4) throw DownloadException("다운로드가 끊겼어요. 네트워크를 확인하고 다시 시도해 주세요", e)
                kotlinx.coroutines.delay(2_000L * attempt)
            }
        }
        if (!verifyFile(part, expectedBytes, sha256)) {
            part.delete()
            throw DownloadException("모델 파일의 SHA-256이 일치하지 않아요. 다시 받아 주세요")
        }
        if (!part.renameTo(dest)) throw DownloadException("파일을 저장하지 못했어요")
    }

    private suspend fun fetchOnce(url: String, part: File, expectedBytes: Long, onProgress: (Long) -> Unit, restartRange: Boolean = true) {
        var have = if (part.isFile) part.length() else 0L
        if (have >= expectedBytes) { part.delete(); have = 0L }
        val conn = open(url, have)
        try {
            val code = conn.responseCode
            if (code == 416) {
                if (!restartRange || have == 0L) throw DownloadException("서버가 파일 범위를 거부했어요. 다시 시도해 주세요")
                // Range past the end: the partial file is stale (or already complete but unverified). Start over.
                part.delete()
                conn.disconnect()
                return fetchOnce(url, part, expectedBytes, onProgress, restartRange = false)
            }
            if (code != 200 && code != 206) throw DownloadException("다운로드 실패 (서버 응답 $code)")
            val type = conn.contentType.orEmpty().lowercase()
            if (type.startsWith("text/html")) throw DownloadException("서버가 파일 대신 오류 페이지를 보냈어요. 잠시 후 다시 시도해 주세요")
            val end: Long
            if (code == 206) {
                // Content-Range: bytes <start>-<end>/<total>
                val range = conn.getHeaderField("Content-Range").orEmpty()
                end = rangeEnd(range, have, expectedBytes)
                if (conn.contentLengthLong >= 0 && conn.contentLengthLong != end - have) throw DownloadException("모델 파일의 응답 크기가 올바르지 않아요")
            } else {
                have = 0L
                end = expectedBytes
                if (conn.contentLengthLong >= 0 && conn.contentLengthLong != expectedBytes) throw DownloadException("모델 파일 크기가 배포 정보와 달라요")
            }
            conn.inputStream.use { input -> copy(input, part, append = code == 206, have, end, onProgress) }
            val size = part.length()
            if (size != expectedBytes) throw IOException("incomplete model download")
            if (looksLikeHtml(part)) { part.delete(); throw DownloadException("서버가 파일 대신 오류 페이지를 보냈어요. 잠시 후 다시 시도해 주세요") }
        } finally {
            conn.disconnect()
        }
    }

    internal suspend fun copy(input: InputStream, part: File, append: Boolean, start: Long, maxBytes: Long, onProgress: (Long) -> Unit) {
        val buf = ByteArray(BUFFER)
        var done = start
        var lastReport = 0L
        FileOutputStream(part, append).use { out ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                if (n > maxBytes - done) throw DownloadException("받은 모델 파일이 배포 정보보다 커요")
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
            if (!isAllowedDownloadUrl(current)) throw DownloadException("허용되지 않은 모델 다운로드 주소예요")
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "ScheduleWidget-mobile")
            c.setRequestProperty("Accept-Encoding", "identity")
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            val code = try { c.responseCode } catch (e: Exception) { c.disconnect(); throw e }
            if (code in setOf(301, 302, 303, 307, 308)) {
                val location = c.getHeaderField("Location")
                c.disconnect()
                if (location == null) throw DownloadException("다운로드 주소가 잘못됐어요")
                current = URL(current, location)
            } else {
                return c
            }
        }
        throw DownloadException("다운로드 주소가 너무 여러 번 바뀌었어요")
    }

    internal fun isAllowedDownloadUrl(url: URL): Boolean =
        url.protocol == "https" && url.userInfo == null && url.port in listOf(-1, 443) && url.host.lowercase() in DOWNLOAD_HOSTS

    internal fun rangeEnd(range: String, start: Long, expectedBytes: Long): Long {
        val parts = CONTENT_RANGE.matchEntire(range)?.groupValues?.drop(1)?.map { it.toLongOrNull() }
        val end = parts?.get(1)
        if (parts?.get(0) != start || parts?.get(2) != expectedBytes || end == null || end !in start until expectedBytes) {
            throw DownloadException("모델 파일의 응답 범위가 올바르지 않아요")
        }
        return end + 1
    }

    internal fun verifyFile(file: File, expectedBytes: Long, sha256: String): Boolean {
        if (!file.isFile || file.length() != expectedBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == sha256
    }

    private fun looksLikeHtml(file: File): Boolean {
        val head = ByteArray(64)
        val n = file.inputStream().use { it.read(head) }
        if (n <= 0) return false
        val text = String(head, 0, n, Charsets.ISO_8859_1).trimStart().lowercase()
        return text.startsWith("<!doctype html") || text.startsWith("<html")
    }


    /** Keep the original entry point; archive extraction is owned by ArchiveExtractor. */
    suspend fun extractTarBz2(archive: File, dir: File, keep: List<String>, maxBytes: Long, onProgress: (Long) -> Unit) =
        ArchiveExtractor.extractTarBz2(archive, dir, keep, maxBytes, onProgress)
}
