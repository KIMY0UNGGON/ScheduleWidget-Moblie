package com.schedulewidget.mobile.notes.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileKindTest {
    private fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = size
                    crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    @Test fun embeddedPdfHeaderCannotTurnABackupIntoPdf() {
        val bytes = storedZip(listOf("Math/one.pdf" to "%PDF-1.4\n%%EOF\n".toByteArray(),
            "English/two.pdf" to "%PDF-1.4\n%%EOF\n".toByteArray()))
        val head = bytes.take(1024).toByteArray()
        assertTrue(String(head, Charsets.ISO_8859_1).contains("%PDF-"))
        assertEquals(FileKind.FLEXCIL, FileKind.detect("Semester.flex", "application/pdf", head) { false })
        assertEquals(FileKind.FLEXCIL, FileKind.detect("Book.flx", null, head) { false })
        assertEquals(FileKind.FLEXCIL, FileKind.detect("download", "application/octet-stream", head) { it == "pages.index" })
        assertEquals(FileKind.UNKNOWN, FileKind.detect("download.zip", "application/pdf", head) { false })
    }

    @Test fun officeZipWithAPdfResourceKeepsItsContainerType() {
        val head = storedZip(listOf("preview.pdf" to "%PDF-1.4\n%%EOF\n".toByteArray())).take(1024).toByteArray()
        assertEquals(FileKind.PPTX, FileKind.detect("slides.pdf", "application/pdf", head) { it == "ppt/presentation.xml" })
        assertEquals(FileKind.DOCX, FileKind.detect("word.flex", "application/zip", head) { it == "word/document.xml" })
    }

    @Test fun actualPdfWithLeadingBytesStillOpensAsPdf() {
        assertEquals(FileKind.PDF, FileKind.detect("scan.pdf", null, "\uFEFF\n%PDF-1.7\n".toByteArray()) { false })
    }
}
