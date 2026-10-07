package com.schedulewidget.mobile.record

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStorageTest {
    @Test
    fun sidecarIdsCannotEscapeRecordingsDirectory() {
        val directory = File("recordings")
        assertEquals(File(directory, "수업_2026-10-07_14-30.json"), recordingMetaFile(directory, "수업_2026-10-07_14-30"))
        assertNull(recordingMetaFile(directory, "../schedules.json"))
        assertNull(recordingMetaFile(directory, "C:\\outside"))
        assertNull(recordingMetaFile(directory, "/tmp/outside"))
    }

    @Test
    fun sidecarMustMatchEnumeratedAudio() {
        val audio = File("recordings/lecture.m4a")
        assertTrue(RecordingMeta("lecture", "title", 1, file = audio.name).matchesAudio(audio))
        assertFalse(RecordingMeta("../other", "title", 1, file = audio.name).matchesAudio(audio))
        assertFalse(RecordingMeta("lecture", "title", 1, file = "../other.m4a").matchesAudio(audio))
        assertFalse(RecordingMeta("lecture", "title", 1, file = audio.name, noteId = "../note").matchesAudio(audio))
    }

    @Test
    fun audioExportAcceptsExternalContentUrisOnly() {
        assertTrue(isSafeRecordingExportLocation("content", "provider.documents", "com.example.files"))
        assertFalse(isSafeRecordingExportLocation("file", null, "com.example.files"))
        assertFalse(isSafeRecordingExportLocation("content", "com.example.files", "com.example.files"))
        assertFalse(isSafeRecordingExportLocation("content", "10@com.example.files", "com.example.files"))
        assertFalse(isSafeRecordingExportLocation("content", null, "com.example.files"))
    }
}
