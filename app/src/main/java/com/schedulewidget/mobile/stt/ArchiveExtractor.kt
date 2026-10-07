package com.schedulewidget.mobile.stt

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

internal object ArchiveExtractor {
    private const val MAX_ENTRIES = 10_000
    private const val MAX_HEADER_BYTES = 1024L * 1024
    /**
     * Unpacks a .tar.bz2 [archive] into [dir]: drops the top-level folder and writes only the [keep] paths
     * (exact names, or prefixes ending in "/"). [onProgress] gets the compressed bytes consumed.
     */
    suspend fun extractTarBz2(archive: File, dir: File, keep: List<String>, maxBytes: Long, onProgress: (Long) -> Unit) {
        if (maxBytes <= 0 || maxBytes > 4L * 1024 * 1024 * 1024) throw DownloadException("모델 압축 크기가 올바르지 않아요")
        if (!dir.isDirectory && !dir.mkdirs()) throw DownloadException("모델 폴더를 만들지 못했어요")
        val root = dir.canonicalFile
        val counting = CountingInputStream(BufferedInputStream(archive.inputStream(), Downloader.BUFFER))
        try {
            val expanded = CountingInputStream(BZip2CompressorInputStream(counting))
            val expandedLimit = maxBytes * 4 + 16L * 1024 * 1024
            TarArchiveInputStream(expanded).use { tar ->
                val buf = ByteArray(Downloader.BUFFER)
                val writtenPaths = HashSet<String>()
                var written = 0L
                var entries = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    // Bound GNU/PAX metadata before the TAR reader can accumulate it in memory.
                    expanded.limit = minOf(expandedLimit, expanded.count + MAX_HEADER_BYTES)
                    val entry = tar.nextEntry ?: break
                    if (++entries > MAX_ENTRIES || entry.size < 0 || entry.size > expandedLimit - expanded.count) throw DownloadException("모델 압축 파일이 너무 커요")
                    if (entry.isSparse || !tar.canReadEntryData(entry)) throw DownloadException("모델 압축 파일 형식이 올바르지 않아요")
                    expanded.limit = expandedLimit
                    val name = entry.name.trimEnd('/')
                    val parts = name.split('/')
                    if (name.startsWith('/') || '\\' in name || ':' in name || parts.any { it.isEmpty() || it == "." || it == ".." }) {
                        throw DownloadException("모델 압축 파일의 경로가 올바르지 않아요")
                    }
                    val rel = parts.drop(1).joinToString("/")
                    val selected = rel.isNotEmpty() && !entry.isDirectory && keep.any { k -> if (k.endsWith("/")) rel.startsWith(k) else rel == k }
                    var tmp: File? = null
                    val out = File(root, rel).canonicalFile
                    if (selected) {
                        if (!entry.isFile || entry.isSymbolicLink || entry.isLink) throw DownloadException("모델 압축 파일에 일반 파일이 아닌 항목이 있어요")
                        if (entry.size > maxBytes - written) throw DownloadException("압축을 푼 모델 파일이 너무 커요")
                        if (!out.path.startsWith(root.path + File.separator) || !writtenPaths.add(out.path)) throw DownloadException("모델 압축 파일의 경로가 올바르지 않아요")
                        val parent = out.parentFile ?: throw DownloadException("모델 파일 경로가 올바르지 않아요")
                        if (!parent.isDirectory && !parent.mkdirs()) throw DownloadException("모델 폴더를 만들지 못했어요")
                        tmp = File.createTempFile("stt-", ".tmp", parent)
                    }
                    try {
                        val os = tmp?.let(::FileOutputStream)
                        try {
                            var lastReport = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = tar.read(buf)
                                if (n < 0) break
                                if (os != null) {
                                    if (n > maxBytes - written) throw DownloadException("압축을 푼 모델 파일이 너무 커요")
                                    os.write(buf, 0, n)
                                    written += n
                                }
                                val now = System.nanoTime()
                                if (now - lastReport > 300_000_000L) {
                                    lastReport = now
                                    onProgress(counting.count)
                                }
                            }
                        } finally { os?.close() }
                        if (tmp != null && !tmp.renameTo(out)) throw DownloadException("압축을 풀지 못했어요")
                    } finally {
                        tmp?.delete()
                    }
                }
            }
        } catch (e: IOException) {
            if (e is DownloadException) throw e
            throw DownloadException("압축을 풀지 못했어요. 다시 받아 주세요", e)
        }
        onProgress(archive.length())
    }

    private class CountingInputStream(private val inner: InputStream) : InputStream() {
        @Volatile var count = 0L
        var limit = Long.MAX_VALUE
        private fun counted(n: Int): Int {
            if (n > 0) count += n
            if (count > limit) throw DownloadException("모델 압축 파일이 너무 커요")
            return n
        }
        override fun read(): Int = inner.read().also { if (it >= 0) counted(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = counted(inner.read(b, off, len))
        override fun close() = inner.close()
    }
}
