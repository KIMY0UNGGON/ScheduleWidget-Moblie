package com.schedulewidget.mobile.notes.editor

import com.schedulewidget.mobile.data.PenPreset
import com.schedulewidget.mobile.notes.ink.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PenCaseTest {
    private var n = 0
    private val ids: () -> String = { "t${n++}" }

    @Test fun defaultsMatchTheFlexcilStyleSet() {
        val d = NotesPens.defaults()
        assertEquals(listOf(Tool.PEN, Tool.BALLPOINT, Tool.BALLPOINT, Tool.PENCIL, Tool.HIGHLIGHTER, Tool.HIGHLIGHTER), d.map { it.tool })
        assertEquals(d.size, d.map { it.id }.toSet().size)
        d.forEach { p ->
            assertTrue(p.width in NotesPens.widthRange(p.tool))
            assertEquals(0xFF, p.color ushr 24)
        }
        // Defaults are already normal.
        assertEquals(d, NotesPens.normalize(d, ids))
    }

    @Test fun emptyStoredListMigratesToDefaults() {
        assertEquals(NotesPens.defaults(), NotesPens.normalize(emptyList(), ids))
        assertEquals(emptyList<PenPreset>(), NotesPens.forStorage(NotesPens.defaults()))
        val custom = NotesPens.defaults().drop(1)
        assertEquals(custom, NotesPens.forStorage(custom))
    }

    @Test fun normalizeRepairsBadPresets() {
        val stored = listOf(
            PenPreset("", 99, 0x00123456, -1f),
            PenPreset("a", Tool.HIGHLIGHTER, 0xFFFFFF00.toInt(), 500f),
            PenPreset("a", Tool.BALLPOINT, 0xFF000000.toInt(), Float.NaN),
        )
        val out = NotesPens.normalize(stored, ids)
        assertEquals(3, out.size)
        assertEquals(3, out.map { it.id }.toSet().size)
        assertTrue(out.all { it.id.isNotBlank() })
        assertEquals(Tool.PEN, out[0].tool)
        assertEquals(0xFF123456.toInt(), out[0].color)
        assertEquals(NotesPens.defaultWidth(Tool.PEN), out[0].width, 0f)
        assertEquals(NotesPens.widthRange(Tool.HIGHLIGHTER).endInclusive, out[1].width, 0f)
        assertEquals(NotesPens.defaultWidth(Tool.BALLPOINT), out[2].width, 0f)
        assertEquals("a", out[1].id)
    }

    @Test fun normalizeCapsSlots() {
        val many = (0 until 20).map { PenPreset("p$it", Tool.PEN, -16777216, 1f) }
        assertEquals(NotesPens.MAX_SLOTS, NotesPens.normalize(many, ids).size)
    }

    @Test fun resolveSelectedFallsBackToFirst() {
        val d = NotesPens.defaults()
        assertEquals("ballpoint-red", NotesPens.resolveSelected(d, "ballpoint-red"))
        assertEquals(d[0].id, NotesPens.resolveSelected(d, "gone"))
        assertEquals(d[0].id, NotesPens.resolveSelected(d, ""))
    }

    @Test fun addStopsAtMax() {
        var pens = NotesPens.defaults()
        while (NotesPens.canAdd(pens)) {
            val p = NotesPens.newPen(pens, Tool.BALLPOINT, ids)!!
            pens = NotesPens.insertAfter(pens, pens.first().id, p)
        }
        assertEquals(NotesPens.MAX_SLOTS, pens.size)
        assertNull(NotesPens.newPen(pens, Tool.PEN, ids))
        assertNull(NotesPens.duplicate(pens, pens[0].id, ids))
        val extra = PenPreset("x", Tool.PEN, -16777216, 1f)
        assertSame(pens, NotesPens.insertAfter(pens, null, extra))
        assertEquals(pens.size, pens.map { it.id }.toSet().size)
    }

    @Test fun newPenPicksAnUnusedColourAndInsertsAfterAnchor() {
        val d = NotesPens.defaults()
        val p = NotesPens.newPen(d, Tool.BALLPOINT, ids)!!
        assertTrue(d.filter { it.tool != Tool.HIGHLIGHTER }.none { it.color == p.color })
        val hl = NotesPens.newPen(d, Tool.HIGHLIGHTER, ids)!!
        assertTrue(hl.color in NotesPens.HL_PALETTE)
        assertTrue(d.none { it.color == hl.color })
        val list = NotesPens.insertAfter(d, "ballpoint-blue", p)
        assertEquals(p.id, list[2].id)
        assertEquals(p.id, NotesPens.insertAfter(d, "missing", p).last().id)
    }

    @Test fun duplicateCopiesNextToSource() {
        val d = NotesPens.defaults()
        val (list, copy) = NotesPens.duplicate(d, "pencil-gray", ids)!!
        assertEquals(d.size + 1, list.size)
        assertEquals(copy, list[4])
        assertNotEquals("pencil-gray", copy.id)
        assertEquals(d[3].copy(id = copy.id), copy)
        assertNull(NotesPens.duplicate(d, "missing", ids))
    }

    @Test fun removeKeepsAtLeastOne() {
        var pens = NotesPens.defaults()
        for (p in NotesPens.defaults()) pens = NotesPens.remove(pens, p.id)
        assertEquals(1, pens.size)
        assertEquals("hl-green", pens[0].id)
        assertTrue(!NotesPens.canRemove(pens))
        assertSame(pens, NotesPens.remove(pens, "hl-green"))
    }

    @Test fun selectionAfterRemovePrefersRightNeighbour() {
        val d = NotesPens.defaults()
        assertEquals("ballpoint-red", NotesPens.selectionAfterRemove(d, "ballpoint-blue"))
        assertEquals("hl-yellow", NotesPens.selectionAfterRemove(d, "hl-green"))
        assertNull(NotesPens.selectionAfterRemove(d, "missing"))
        assertNull(NotesPens.selectionAfterRemove(d.take(1), d[0].id))
    }

    @Test fun moveIsClampedToTheEnds() {
        val d = NotesPens.defaults()
        assertEquals("ballpoint-blue", NotesPens.move(d, "ballpoint-blue", -1)[0].id)
        assertSame(d, NotesPens.move(d, d[0].id, -1))
        assertSame(d, NotesPens.move(d, d.last().id, 1))
        assertEquals(d[0].id, NotesPens.move(d, d[0].id, 100).last().id)
        assertSame(d, NotesPens.move(d, "missing", 1))
        assertEquals(d.toSet(), NotesPens.move(d, "pencil-gray", 2).toSet())
    }

    @Test fun kindChangeAcrossFamiliesResetsLook() {
        val blue = NotesPens.defaults()[1]
        val fountain = NotesPens.withKind(blue, Tool.PEN)
        assertEquals(blue.color, fountain.color)
        assertEquals(blue.width, fountain.width, 0f)
        val hl = NotesPens.withKind(blue, Tool.HIGHLIGHTER)
        assertEquals(NotesPens.HL_YELLOW, hl.color)
        assertEquals(NotesPens.defaultWidth(Tool.HIGHLIGHTER), hl.width, 0f)
        assertEquals(blue.id, hl.id)
        val back = NotesPens.withKind(hl, Tool.BRUSH)
        assertEquals(NotesPens.BLACK, back.color)
        assertTrue(back.width in NotesPens.widthRange(Tool.BRUSH))
        // Width is clamped into the new kind's range.
        val wide = PenPreset("w", Tool.PEN, -16777216, 8f)
        assertEquals(NotesPens.widthRange(Tool.BRUSH).endInclusive, NotesPens.withKind(wide, Tool.BRUSH).width, 0f)
    }

    @Test fun resetRestoresDefaultOrKindDefaults() {
        val red = NotesPens.defaults()[2]
        assertEquals(red, NotesPens.reset(red.copy(color = 0xFF00FF00.toInt(), width = 5f)))
        val custom = PenPreset("c", Tool.PENCIL, 0xFF00FF00.toInt(), 5f)
        assertEquals(PenPreset("c", Tool.PENCIL, NotesPens.defaultColor(Tool.PENCIL), NotesPens.defaultWidth(Tool.PENCIL)), NotesPens.reset(custom))
    }

    @Test fun replaceSanitizes() {
        val d = NotesPens.defaults()
        val out = NotesPens.replace(d, d[0].copy(width = 100f))
        assertEquals(NotesPens.widthRange(Tool.PEN).endInclusive, out[0].width, 0f)
        assertEquals(d.drop(1), out.drop(1))
    }

    @Test fun hexRoundTrip() {
        assertEquals("#1E5BD8", NotesPens.toHex(NotesPens.BLUE))
        assertEquals(NotesPens.BLUE, NotesPens.parseHex("#1e5bd8"))
        assertEquals(NotesPens.BLUE, NotesPens.parseHex(" 1E5BD8 "))
        assertEquals(0xFFFF0000.toInt(), NotesPens.parseHex("#f00"))
        assertNull(NotesPens.parseHex("#12345"))
        assertNull(NotesPens.parseHex("#GG0000"))
        assertNotNull(NotesPens.parseHex("000000"))
    }

    @Test fun palettesAreOpaqueAndDistinct() {
        assertEquals(16, NotesPens.PALETTE.size)
        assertEquals(16, NotesPens.PALETTE.toSet().size)
        assertTrue(NotesPens.BLACK in NotesPens.PALETTE)
        assertTrue((NotesPens.PALETTE + NotesPens.HL_PALETTE).all { it ushr 24 == 0xFF })
        assertEquals(NotesPens.HL_PALETTE.size, NotesPens.HL_PALETTE.toSet().size)
    }
}
