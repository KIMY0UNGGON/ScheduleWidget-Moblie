package com.schedulewidget.mobile.stt

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptStoreAtomicWriteTest {
    @Test
    fun failedReplaceKeepsPreviousTranscriptAndRemovesTempFile() {
        val directory = Files.createTempDirectory("transcript-store-").toFile()
        try {
            val target = File(directory, "lecture.json").apply { writeText("old transcript") }
            writeTranscriptAtomically(target, "new transcript".toByteArray(Charsets.UTF_8))
            assertEquals("new transcript", target.readText())

            val failure = runCatching {
                writeTranscriptAtomically(target, "partial replacement".toByteArray(Charsets.UTF_8)) { _, _ ->
                    throw IOException("simulated atomic move failure")
                }
            }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals("new transcript", target.readText())
            assertEquals(listOf("lecture.json"), directory.list()?.toList())
        } finally {
            directory.deleteRecursively()
        }
    }
}
