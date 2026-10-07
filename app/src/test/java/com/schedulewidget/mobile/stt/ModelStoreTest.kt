package com.schedulewidget.mobile.stt

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelStoreTest {
    @Test
    fun archiveSpaceCountsRemainingDownloadAndExtraction() {
        val dir = Files.createTempDirectory("model-space").toFile()
        val archive = RemoteArchive("https://example.invalid/model.tar.bz2", 100, 200, emptyList(), emptyMap(), "a".repeat(64))
        try {
            assertEquals(300L, archiveSpaceNeeded(dir, "model.tar.bz2", archive))

            File(dir, "model.tar.bz2.part").writeBytes(ByteArray(40))
            assertEquals(260L, archiveSpaceNeeded(dir, "model.tar.bz2", archive))

            File(dir, "model.tar.bz2.part").delete()
            File(dir, "model.tar.bz2").writeBytes(ByteArray(100))
            assertEquals(200L, archiveSpaceNeeded(dir, "model.tar.bz2", archive))

            File(dir, "model.tar.bz2").writeBytes(ByteArray(50))
            assertEquals(250L, archiveSpaceNeeded(dir, "model.tar.bz2", archive))
        } finally {
            dir.deleteRecursively()
        }
    }
}
