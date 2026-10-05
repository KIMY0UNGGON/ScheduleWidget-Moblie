package com.schedulewidget.mobile.notes.editor

import com.schedulewidget.mobile.notes.ink.InkJson
import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.notes.ink.StoredNote
import com.schedulewidget.mobile.notes.ink.Stroke
import com.schedulewidget.mobile.notes.ink.Template
import com.schedulewidget.mobile.notes.ink.TextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageOpsTest {
    private fun blank(uid: String, w: Float = 595f, h: Float = 842f) = PageInfo(uid, PageInfo.KIND_BLANK, w = w, h = h)
    private fun pdf(uid: String, i: Int, w: Float = 720f, h: Float = 405f, src: String? = null) =
        PageInfo(uid, PageInfo.KIND_PDF, pdf = i, w = w, h = h, src = src)

    private val five = listOf("a", "b", "c", "d", "e").map { blank(it) }
    private fun uids(l: List<PageInfo>) = l.joinToString("") { it.uid }

    // ---- page ranges ----

    @Test fun rangeAllForms() {
        assertEquals((0 until 5).toList(), PageOps.parseRange("", 5))
        assertEquals((0 until 5).toList(), PageOps.parseRange("전체", 5))
        assertEquals((0 until 5).toList(), PageOps.parseRange(" ALL ", 5))
    }

    @Test fun rangeListAndSpans() {
        assertEquals(listOf(0, 1, 2, 4), PageOps.parseRange("1-3,5", 10))
        assertEquals(listOf(0, 1, 2, 4), PageOps.parseRange(" 1 - 3 , 5 ", 10))
        assertEquals(listOf(6, 7, 8, 9), PageOps.parseRange("7-", 10))
        assertEquals(listOf(0, 1, 2), PageOps.parseRange("-3", 10))
        assertEquals(listOf(4, 3, 2), PageOps.parseRange("5-3", 10))
        assertEquals(listOf(1, 2), PageOps.parseRange("2~3", 10))
        // Duplicates collapse, first occurrence wins the order.
        assertEquals(listOf(2, 0, 1), PageOps.parseRange("3,1-3", 10))
    }

    @Test fun rangeRejectsBadInput() {
        assertNull(PageOps.parseRange("0", 5))
        assertNull(PageOps.parseRange("6", 5))
        assertNull(PageOps.parseRange("1-9", 5))
        assertNull(PageOps.parseRange("a", 5))
        assertNull(PageOps.parseRange("1-2-3", 5))
        assertNull(PageOps.parseRange(",,", 5))
    }

    // ---- list operations ----

    @Test fun insertAtPositions() {
        val x = listOf(blank("x"), blank("y"))
        assertEquals("xyabcde", uids(PageOps.insert(five, 0, x)))
        assertEquals("abxycde", uids(PageOps.insert(five, 2, x)))
        assertEquals("abcdexy", uids(PageOps.insert(five, 5, x)))
        assertEquals("abcdexy", uids(PageOps.insert(five, 99, x)))
        assertEquals("abcde", uids(five)) // input untouched (undo snapshot)
    }

    @Test fun deleteKeepsOnePage() {
        assertEquals("ace", uids(PageOps.delete(five, listOf(1, 3))!!))
        assertNull(PageOps.delete(five, listOf(0, 1, 2, 3, 4)))
        assertSame(five, PageOps.delete(five, listOf(7)))
    }

    @Test fun moveSingleAndToEnds() {
        assertEquals("bcade", uids(PageOps.move(five, 0, 2)))
        assertEquals("bdace", uids(PageOps.moveTo(five, listOf(3, 1), toStart = true)))
        assertEquals("acebd", uids(PageOps.moveTo(five, listOf(1, 3), toStart = false)))
    }

    @Test fun duplicateGoesAfterLastSelected() {
        var n = 0
        val (after, copies) = PageOps.duplicate(five, listOf(3, 1), { "n${n++}" })
        assertEquals(listOf("a", "b", "c", "d", "n0", "n1", "e"), after.map { it.uid })
        assertEquals(listOf("b" to "n0", "d" to "n1"), copies.map { it.first.uid to it.second.uid })
    }

    // ---- rotation ----

    @Test fun rotatePdfPageKeepsTurnAndSwapsSize() {
        val p = pdf("p", 0, w = 720f, h = 405f)
        val r1 = PageOps.rotate(p, 1)
        assertEquals(90, r1.rot); assertEquals(405f, r1.w); assertEquals(720f, r1.h)
        val r4 = PageOps.rotate(PageOps.rotate(r1, 1), 2)
        assertEquals(0, r4.rot); assertEquals(720f, r4.w); assertEquals(405f, r4.h)
        assertEquals(270, PageOps.rotate(p, -1).rot)
    }

    @Test fun rotateBlankPageOnlyChangesOrientation() {
        val r = PageOps.rotate(blank("b").copy(template = Template.GRID), 1)
        assertEquals(0, r.rot); assertEquals(842f, r.w); assertEquals(595f, r.h); assertEquals(Template.GRID, r.template)
    }

    @Test fun normRot() {
        assertEquals(0, PageOps.normRot(360)); assertEquals(270, PageOps.normRot(-90)); assertEquals(90, PageOps.normRot(450))
    }

    @Test fun rotatePointCorners() {
        // A 100 × 50 page turned clockwise is 50 × 100: top-left goes to top-right, bottom-left to top-left.
        assertEquals(50f to 0f, PageOps.rotatePoint(0f, 0f, 100f, 50f, 1))
        assertEquals(0f to 0f, PageOps.rotatePoint(0f, 50f, 100f, 50f, 1))
        assertEquals(50f to 100f, PageOps.rotatePoint(100f, 0f, 100f, 50f, 1))
        assertEquals(100f to 50f, PageOps.rotatePoint(0f, 0f, 100f, 50f, 2))
        assertEquals(0f to 100f, PageOps.rotatePoint(0f, 0f, 100f, 50f, 3))
    }

    @Test fun rotateInkFourTimesIsIdentity() {
        val s = Stroke(1, color = 0, width = 2f, pts = floatArrayOf(10f, 20f, 0.5f, 30f, 5f, 1f))
        val ink = PageInk(strokes = listOf(s), texts = listOf(TextBox(2, 40f, 10f, "hi", size = 10f)))
        val w = 100f; val h = 60f
        val once = PageOps.rotateInk(ink, w, h, 1)
        // First point (10, 20) → (h - 20, 10); pressure untouched.
        assertEquals(40f, once.strokes[0].pts[0]); assertEquals(10f, once.strokes[0].pts[1]); assertEquals(0.5f, once.strokes[0].pts[2])
        val back = PageOps.rotateInk(PageOps.rotateInk(PageOps.rotateInk(once, h, w, 1), w, h, 1), h, w, 1)
        assertTrue(back.strokes[0].pts.contentEquals(s.pts))
        assertEquals(40f, back.texts[0].x, 0.01f); assertEquals(10f, back.texts[0].y, 0.01f)
        assertSame(ink, PageOps.rotateInk(ink, w, h, 4))
    }

    @Test fun rotatedTextStaysOnPage() {
        val ink = PageInk(texts = listOf(TextBox(1, 0f, 0f, "a long line of text", size = 14f)))
        val r = PageOps.rotateInk(ink, 595f, 842f, 1).texts[0]
        assertTrue(r.x >= 0f && r.x <= 842f); assertTrue(r.y >= 0f && r.y <= 595f)
    }

    // ---- sizes & storage ----

    @Test fun pageSizes() {
        val ref = pdf("p", 0, w = 720f, h = 405f)
        assertEquals(720f to 405f, PageSize.SAME.size(ref))
        assertEquals(PageInfo.A4_W to PageInfo.A4_H, PageSize.SAME.size(null))
        assertEquals(PageInfo.A4_H to PageInfo.A4_W, PageSize.A4_LANDSCAPE.size(ref))
        assertEquals(PageInfo.A4_W to PageInfo.A4_W, PageSize.SQUARE.size(ref))
    }

    @Test fun newPageFieldsRoundTripAndOldFilesStillLoad() {
        val note = StoredNote(
            id = "n", title = "t", createdAt = 1, updatedAt = 2, source = "pdf",
            pages = listOf(pdf("a", 2, src = "src-1.pdf").copy(rot = 90), blank("b").copy(template = "cornell", paper = "dark")),
        )
        val back = InkJson.decodeNote(InkJson.encodeNote(note))
        assertEquals(note.pages, back.pages)
        val old = InkJson.decodeNote("""{"id":"o","title":"t","createdAt":1,"updatedAt":1,"source":"pdf","pages":[{"uid":"x","kind":"pdf","pdf":0}]}""")
        assertNull(old.pages[0].src); assertEquals(0, old.pages[0].rot); assertNull(old.pages[0].paper)
        // Defaults are not written, so untouched pages keep the old file format.
        assertTrue("rot" !in InkJson.encodeNote(old))
    }
}
