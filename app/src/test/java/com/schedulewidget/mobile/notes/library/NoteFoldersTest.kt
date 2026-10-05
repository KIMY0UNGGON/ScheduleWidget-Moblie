package com.schedulewidget.mobile.notes.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteFoldersTest {
    @Test fun ancestorsIncludeImpliedParentsOnce() {
        val all = NoteFolders.ancestors(listOf("백업/과목/물리", "백업/과목", "AB", "A/x"))
        assertEquals(all.size, all.toSet().size)
        assertEquals(setOf("백업", "백업/과목", "백업/과목/물리", "AB", "A", "A/x"), all.toSet())
    }

    @Test fun childrenAreImmediateEvenFromLeafOnlyPaths() {
        val leaves = listOf("A/B/C", "A/D", "AB/X", "Z")
        assertEquals(setOf("A", "AB", "Z"), NoteFolders.children(leaves, null).toSet())
        assertEquals(setOf("A/B", "A/D"), NoteFolders.children(leaves, "A").toSet())
        assertEquals(setOf("A/B/C"), NoteFolders.children(leaves, "A/B").toSet())
        assertTrue(NoteFolders.children(leaves, "A/B/C").isEmpty())
    }

    @Test fun withinRespectsSegmentBoundaries() {
        assertTrue(NoteFolders.isWithin("A", "A"))
        assertTrue(NoteFolders.isWithin("A/B/C", "A"))
        assertFalse(NoteFolders.isWithin("AB", "A"))
        assertFalse(NoteFolders.isWithin("AB/C", "A"))
        assertFalse(NoteFolders.isWithin("A", "A/B"))
        assertFalse(NoteFolders.isWithin(null, "A"))
    }

    @Test fun renameMovesDescendantsAndLeavesPrefixSiblings() {
        val paths = listOf("A", "A/B", "A/B/C", "AB", "AB/C", null)
        assertEquals(listOf("Z", "Z/B", "Z/B/C", "AB", "AB/C", null), paths.map { NoteFolders.remap(it, "A", "Z") })
        assertEquals("P/Z/B", NoteFolders.remap("P/A/B", "P/A", "P/Z"))
    }

    @Test fun removePromotesContentsToParentKeepingRelativePaths() {
        // Nested folder: contents of P/A move up into P.
        assertEquals("P", NoteFolders.remap("P/A", "P/A", "P"))
        assertEquals("P/B/C", NoteFolders.remap("P/A/B/C", "P/A", "P"))
        assertEquals("P/AB", NoteFolders.remap("P/AB", "P/A", "P"))
        // Top-level folder: its notes go home, subfolders become top-level.
        assertNull(NoteFolders.remap("A", "A", null))
        assertEquals("B/C", NoteFolders.remap("A/B/C", "A", null))
        assertEquals("AB", NoteFolders.remap("AB", "A", null))
    }

    @Test fun uniquePathTreatsImpliedParentsAsOccupied() {
        assertEquals("Backup", NoteFolders.uniquePath("Backup", listOf("Backups", "Other/Backup")))
        assertEquals("Backup (2)", NoteFolders.uniquePath("Backup", listOf("Backup/과목/물리")))
        assertEquals("Backup (3)", NoteFolders.uniquePath("Backup", listOf("Backup", "Backup (2)/x")))
        assertEquals("P/Backup (2)", NoteFolders.uniquePath("P/Backup", listOf("P/Backup/sub")))
    }
}
