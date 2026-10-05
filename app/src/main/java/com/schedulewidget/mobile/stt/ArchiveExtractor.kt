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
    /**
     * Unpacks a .tar.bz2 [archive] into [dir]: drops the top-level folder and writes only the [keep] paths
     * (exact names, or prefixes ending in "/"). [onProgress] gets the compressed bytes consumed.
     */
    suspend fun extractTarBz2(archive: File, dir: File, keep: List<String>, onProgress: (Long) -> Unit) {
        dir.mkdirs()
        val root = dir.canonicalFile
        val counting = CountingInputStream(BufferedInputStream(archive.inputStream(), Downloader.BUFFER))
        try {
            TarArchiveInputStream(BZip2CompressorInputStream(counting)).use { tar ->
                val buf = ByteArray(Downloader.BUFFER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = tar.nextEntry ?: break
                    val rel = entry.name.substringAfter('/', "")
                    if (rel.isEmpty() || entry.isDirectory) continue
                    if (keep.none { k -> if (k.endsWith("/")) rel.startsWith(k) else rel == k }) continue
                    val out = File(root, rel).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator)) continue // never write outside dir
                    out.parentFile?.mkdirs()
                    val tmp = File(out.path + ".tmp")
                    FileOutputStream(tmp).use { os ->
                        var lastReport = 0L
                        while (true) {
                            val n = tar.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            val now = System.nanoTime()
                            if (now - lastReport > 300_000_000L) {
                                lastReport = now
                                currentCoroutineContext().ensureActive()
                                onProgress(counting.count)
                            }
                        }
                    }
                    if (!tmp.renameTo(out)) throw DownloadException("압축을 풀지 못했어요")
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
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }
        override fun close() = inner.close()
    }
}
