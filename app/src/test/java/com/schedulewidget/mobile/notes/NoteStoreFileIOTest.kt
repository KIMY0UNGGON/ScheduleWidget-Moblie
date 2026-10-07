package com.schedulewidget.mobile.notes

import java.security.MessageDigest
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NoteStoreFileIOTest {
    @Test
    fun sameLengthCrcCollisionDoesNotAliasPdfSources() {
        val first = "ad17b5f2a9".hexBytes()
        val second = "d7352d2219".hexBytes()
        assertEquals(first.size, second.size)
        assertEquals(first.crc32(), second.crc32())
        assertNotEquals(sourceName(first), sourceName(second))
    }

    private fun sourceName(bytes: ByteArray): String = NoteStoreFileIO.sourceName(
        MessageDigest.getInstance("SHA-256").digest(bytes), bytes.size.toLong(),
    )

    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.crc32(): Long = CRC32().apply { update(this@crc32) }.value
}
