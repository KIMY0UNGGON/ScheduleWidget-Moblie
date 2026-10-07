package com.schedulewidget.mobile.notes.editor

import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.notes.ink.StoredNote
import com.schedulewidget.mobile.notes.ink.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteRecordingsTest {
    @Test
    fun noteIncludesExplicitAndAllPageInkLinksButNotUnrelatedIds() {
        val note = StoredNote(
            id = "note-1", title = "note", createdAt = 1, updatedAt = 1, source = "blank",
            pages = listOf(PageInfo("p1"), PageInfo("p2")),
        )
        val ink = mapOf(
            "p1" to PageInk(strokes = listOf(stroke(1, "rec-1"), stroke(2, null))),
            "p2" to PageInk(strokes = listOf(stroke(3, "rec-2"), stroke(4, "  "))),
            "other" to PageInk(strokes = listOf(stroke(5, "rec-unrelated-page"))),
        )
        val linked = linkedRecordingIds(note, ink)

        assertEquals(setOf("rec-1", "rec-2"), linked)
        assertTrue(recordingBelongsToNote("rec-1", null, note.id, linked))
        assertTrue(recordingBelongsToNote("rec-3", note.id, note.id, linked))
        assertFalse(recordingBelongsToNote("rec-unrelated", null, note.id, linked))
    }

    private fun stroke(id: Long, recordingId: String?) = Stroke(
        id = id, color = 0, width = 1f, pts = floatArrayOf(0f, 0f, 1f), rec = recordingId,
    )
}
