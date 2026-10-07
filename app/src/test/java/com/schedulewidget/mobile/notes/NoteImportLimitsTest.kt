package com.schedulewidget.mobile.notes

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import com.schedulewidget.mobile.notes.importer.FileKind
import com.schedulewidget.mobile.notes.render.checkPdfPageCount
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteImportLimitsTest {
    private class ProviderStream(private val bytes: ByteArray) : InputStream() {
        private var offset = 0
        override fun available() = 0 // ContentProvider streams need not report their remaining size.
        override fun read(): Int = if (offset == bytes.size) -1 else bytes[offset++].toInt() and 0xFF
        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            if (offset == bytes.size) return -1
            val count = minOf(length, bytes.size - offset)
            bytes.copyInto(buffer, start, offset, offset + count)
            offset += count
            return count
        }
    }

    @Test fun copiedContentIsCappedByBytesActuallyRead() {
        val output = ByteArrayOutputStream()
        assertFalse(NoteImportFiles.copyLimited(ProviderStream(byteArrayOf(1, 2, 3, 4, 5)), output, 4))
        assertTrue(output.size() <= 4)
        val exact = ByteArrayOutputStream()
        assertTrue(NoteImportFiles.copyLimited(ProviderStream(byteArrayOf(1, 2, 3, 4)), exact, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), exact.toByteArray())
    }

    @Test fun cancellationAfterReadDoesNotWriteTheChunk() {
        val output = ByteArrayOutputStream()
        var checks = 0
        assertThrows(CancellationException::class.java) {
            NoteImportFiles.copyLimited(ProviderStream(byteArrayOf(1)), output, 4) {
                if (++checks == 2) throw CancellationException()
            }
        }
        assertEquals(0, output.size())
    }

    @Test fun largeBackupStreamsUseLongByteCountsWithoutAllocatingTheBackup() {
        fun stream() = object : InputStream() {
            var remaining = (3L shl 30) + 17
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0L) return -1
                val count = minOf(remaining, length.toLong()).toInt()
                remaining -= count
                return count
            }
        }
        assertTrue(NoteImportFiles.copyLimited(stream(), OutputStream.nullOutputStream(), NoteImportFiles.MAX_FLEX_IMPORT_BYTES))
        assertFalse(NoteImportFiles.copyLimited(stream(), OutputStream.nullOutputStream(), NoteImportFiles.MAX_IMPORT_BYTES))
    }

    @Test fun pdfPageCountIsCheckedBeforePageSizesAreAllocated() {
        checkPdfPageCount(FileKind.MAX_IMPORT_PAGES)
        assertThrows(IOException::class.java) { checkPdfPageCount(FileKind.MAX_IMPORT_PAGES + 1) }
    }
}
