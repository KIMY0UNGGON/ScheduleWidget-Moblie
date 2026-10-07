package com.schedulewidget.mobile.stt

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest

class SttSecurityTest {
    @Test fun modelDownloadsRequireTrustedHttpsHostsAndExactRanges() {
        for (host in listOf("github.com", "release-assets.githubusercontent.com", "huggingface.co", "cas-bridge.xethub.hf.co", "us.aws.cdn.hf.co")) {
            assertTrue(Downloader.isAllowedDownloadUrl(URL("https://$host/file")))
        }
        for (url in listOf("http://huggingface.co/file", "https://huggingface.co.evil.invalid/file", "https://user@github.com/file", "https://github.com:8443/file", "https://127.0.0.1/file")) {
            assertFalse(url, Downloader.isAllowedDownloadUrl(URL(url)))
        }
        assertEquals(10L, Downloader.rangeEnd("bytes 4-9/10", 4, 10))
        for (range in listOf("bytes 0-9/10", "bytes 4-10/10", "bytes 4-9/11", "bytes 4-9/*", "bytes 4-9/10 forged", "bytes 4-9999999999999999999999/10")) {
            assertThrows(DownloadException::class.java) { Downloader.rangeEnd(range, 4, 10) }
        }
    }

    @Test fun streamCannotWriteBeyondThePublishedModelLength() = withDirectory { dir ->
        val part = File(dir, "model.part")
        runBlocking { Downloader.copy(byteArrayOf(1, 2, 3).inputStream(), part, false, 0, 3) {} }
        assertEquals(3L, part.length())
        assertThrows(DownloadException::class.java) {
            runBlocking { Downloader.copy(ByteArray(4).inputStream(), part, false, 0, 3) {} }
        }
        assertTrue(part.length() <= 3)
    }

    @Test fun sameSizeModelTamperingAndLegacyReadyMarkersAreRejected() = withDirectory { dir ->
        val data = byteArrayOf(1, 2, 3)
        val sha = digest(data)
        val file = File(dir, "model.onnx").apply { writeBytes(data) }
        assertTrue(Downloader.verifyFile(file, 3, sha))
        runBlocking { Downloader.fetch("https://github.com/model", file, 3, sha) {} }
        file.writeBytes(byteArrayOf(1, 2, 4))
        assertFalse(Downloader.verifyFile(file, 3, sha))
        val pkg = ModelPackage("test", "test", listOf(RemoteFile("https://github.com/model", file.name, 3, sha)), null)
        val marker = File(dir, ".complete")
        marker.writeText("123456789")
        assertFalse(hasVerifiedPackage(dir, pkg))
        marker.writeText(packageVerification(pkg))
        assertTrue(hasVerifiedPackage(dir, pkg))
        assertFalse(hasVerifiedPackage(dir, pkg.copy(files = listOf(pkg.files[0].copy(sha256 = "b".repeat(64))))))
        assertTrue((SttModel.entries.map(ModelCatalog::of) + ModelCatalog.vad + ModelCatalog.diarization).all { p ->
            p.files.all { Regex("[0-9a-f]{64}").matches(it.sha256) } && (p.archive?.sha256?.let { Regex("[0-9a-f]{64}").matches(it) } ?: true)
        })
    }

    @Test fun archiveKeepsValidFilesAndRejectsTraversalMarkerInjectionAndLinks() = withDirectory { dir ->
        val output = File(dir, "output")
        val safe = archive(dir, "safe.tar.bz2", listOf("root/tokenizer/vocab.json" to byteArrayOf(1, 2, 3), "root/ignored" to ByteArray(20)))
        runBlocking { ArchiveExtractor.extractTarBz2(safe, output, listOf("tokenizer/"), 100) {} }
        assertArrayEquals(byteArrayOf(1, 2, 3), File(output, "tokenizer/vocab.json").readBytes())
        assertFalse(File(output, "ignored").exists())
        for (name in listOf("root/tokenizer/../.complete", "root/tokenizer/../../escape", "root/tokenizer\\..\\escape", "/root/tokenizer/vocab.json")) {
            val bad = archive(dir, "bad.tar.bz2", listOf(name to byteArrayOf(1)))
            assertThrows(DownloadException::class.java) {
                runBlocking { ArchiveExtractor.extractTarBz2(bad, output, listOf("tokenizer/"), 100) {} }
            }
        }
        assertFalse(File(output, ".complete").exists())
        assertFalse(File(dir, "escape").exists())
        val link = File(dir, "link.tar.bz2")
        TarArchiveOutputStream(BZip2CompressorOutputStream(link.outputStream())).use { tar ->
            tar.putArchiveEntry(TarArchiveEntry("root/tokenizer/link", TarConstants.LF_SYMLINK).apply { linkName = "../../escape" })
            tar.closeArchiveEntry()
        }
        assertThrows(DownloadException::class.java) {
            runBlocking { ArchiveExtractor.extractTarBz2(link, output, listOf("tokenizer/"), 100) {} }
        }
    }

    @Test(timeout = 15_000) fun archiveRejectsOversizedSelectedAndIgnoredDataAndMetadata() = withDirectory { dir ->
        val selected = archive(dir, "selected.tar.bz2", listOf("root/model.onnx" to ByteArray(101)))
        assertThrows(DownloadException::class.java) {
            runBlocking { ArchiveExtractor.extractTarBz2(selected, File(dir, "selected"), listOf("model.onnx"), 100) {} }
        }
        assertFalse(File(dir, "selected/model.onnx").exists())
        val ignored = archive(dir, "ignored.tar.bz2", listOf("root/ignored" to ByteArray(17 * 1024 * 1024)))
        assertThrows(DownloadException::class.java) {
            runBlocking { ArchiveExtractor.extractTarBz2(ignored, File(dir, "ignored"), listOf("model.onnx"), 100) {} }
        }
        val metadata = File(dir, "metadata.tar.bz2")
        TarArchiveOutputStream(BZip2CompressorOutputStream(metadata.outputStream())).use { tar ->
            val longName = ByteArray(1024 * 1024 + 128) { 'x'.code.toByte() }
            tar.putArchiveEntry(TarArchiveEntry("././@LongLink", TarConstants.LF_GNUTYPE_LONGNAME).apply { size = longName.size.toLong() })
            tar.write(longName)
            tar.closeArchiveEntry()
            tar.putArchiveEntry(TarArchiveEntry("root/model.onnx").apply { size = 1 })
            tar.write(byteArrayOf(1))
            tar.closeArchiveEntry()
        }
        assertThrows(DownloadException::class.java) {
            runBlocking { ArchiveExtractor.extractTarBz2(metadata, File(dir, "metadata"), listOf("model.onnx"), 100) {} }
        }
    }

    @Test fun wavRejectsAllocationBombsInvalidFormatsAndNonFiniteSamples() {
        for (size in listOf(0, 1, 15, Int.MAX_VALUE, -1)) {
            assertNull(WavReader.readHeader(wav(size = size).inputStream()))
        }
        for ((channels, rate) in listOf(0 to 16000, 32767 to 16000, 1 to 1, 1 to Int.MAX_VALUE)) {
            assertNull(WavReader.readHeader(wav(channels = channels, rate = rate).inputStream()))
        }
        assertNotNull(WavReader.readHeader(wav().inputStream()))
        val header = wav()
        val fmt = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0xFFFE.toShort()).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .putShort(22).putShort(16).putInt(0)
            .put(byteArrayOf(1, 0, 0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71)).array()
        val extensible = header.copyOfRange(0, 20) + fmt + header.copyOfRange(36, header.size)
        ByteBuffer.wrap(extensible).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 40)
        assertNotNull(WavReader.readHeader(extensible.inputStream()))
        extensible[59] = 0
        assertNull(WavReader.readHeader(extensible.inputStream()))
        val nonFinite = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).array()
        assertThrows(AudioDecodeException::class.java) { WavReader.toMono(nonFinite, 4, WavReader.Header(1, 16000, 4, true, 4)) }
    }

    private fun archive(dir: File, name: String, files: List<Pair<String, ByteArray>>): File = File(dir, name).also { file ->
        TarArchiveOutputStream(BZip2CompressorOutputStream(file.outputStream())).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
            files.forEach { (path, content) ->
                tar.putArchiveEntry(TarArchiveEntry(path, true).apply { size = content.size.toLong() })
                tar.write(content)
                tar.closeArchiveEntry()
            }
        }
    }

    private fun wav(size: Int = 16, channels: Int = 1, rate: Int = 16000): ByteArray = ByteArrayOutputStream().apply {
        val body = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 2)
            .putShort((channels * 2).toShort()).putShort(16).array()
        write("RIFF".toByteArray()); write(ByteArray(4)); write("WAVEfmt ".toByteArray())
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(size).array()); write(body)
        write("data".toByteArray()); write(byteArrayOf(2, 0, 0, 0)); write(ByteArray(2))
    }.toByteArray()

    private fun digest(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun withDirectory(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("stt-security-").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }
}
