package com.schedulewidget.mobile.notes.ink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkJsonTest {
    @Test fun pageInkRoundTrip() {
        val ink = PageInk(
            strokes = listOf(
                Stroke(1, Tool.PEN, 0xFF1E5BD8.toInt(), 1.5f, floatArrayOf(10.25f, 20.5f, 0.42f, 11f, 21f, 0.9f)),
                Stroke(2, Tool.HIGHLIGHTER, 0xFFFFE34D.toInt(), 14f, floatArrayOf(0f, 0f, 1f, 100f, 0f, 1f), rec = "수업_2026-10-01_14-30", recMs = 65_000),
            ),
            texts = listOf(TextBox(3, 40f, 50f, "첫 줄\n둘째 줄", 0xFFE53935.toInt(), 20f)),
        )
        val back = InkJson.decodeInk(InkJson.encodeInk(ink))
        assertEquals(2, back.strokes.size)
        for ((a, b) in ink.strokes.zip(back.strokes)) {
            assertEquals(a.id, b.id); assertEquals(a.tool, b.tool); assertEquals(a.color, b.color)
            assertEquals(a.width, b.width, 0f)
            assertArrayEquals(a.pts, b.pts, 0f)
            assertEquals(a.rec, b.rec); assertEquals(a.recMs, b.recMs)
        }
        assertEquals(ink.texts, back.texts)
    }

    @Test fun defaultsAreLeftOutAndUnknownKeysIgnored() {
        val s = Stroke(1, Tool.PEN, 0, 1f, floatArrayOf(1f, 2f, 1f))
        val text = InkJson.encodeInk(PageInk(strokes = listOf(s)))
        assertFalse(text.contains("rec"))
        assertFalse(text.contains("texts"))
        val parsed = InkJson.decodeInk("""{"strokes":[{"id":7,"color":-1,"width":2.0,"pts":[1,2,0.5],"future":true}],"v2":1}""")
        assertEquals(7L, parsed.strokes[0].id)
        assertEquals(Tool.PEN, parsed.strokes[0].tool)
        assertNull(parsed.strokes[0].rec)
        assertTrue(parsed.texts.isEmpty())
    }

    @Test fun emptyInk() {
        assertTrue(InkJson.decodeInk("{}").isEmpty)
        assertTrue(PageInk.EMPTY.isEmpty)
    }

    @Test fun noteRoundTripKeepsPageUidsAndKinds() {
        val note = StoredNote(
            id = "n1", title = "선형대수 3강", createdAt = 1, updatedAt = 2, source = "pptx", folder = "수학",
            pages = listOf(
                PageInfo("a1", PageInfo.KIND_PDF, pdf = 0, w = 720f, h = 405f),
                PageInfo("b2", PageInfo.KIND_BLANK, template = Template.GRID),
                PageInfo("c3", PageInfo.KIND_PDF, pdf = 1, w = 720f, h = 405f),
            ),
        )
        val back = InkJson.decodeNote(InkJson.encodeNote(note))
        assertEquals(note, back)
        assertTrue(back.pages[0].isPdf)
        assertFalse(back.pages[1].isPdf)
        assertEquals(PageInfo.A4_W, back.pages[1].w, 0f)
        val meta = back.toMeta()
        assertEquals(3, meta.pageCount)
        assertEquals("수학", meta.folder)
    }
}
