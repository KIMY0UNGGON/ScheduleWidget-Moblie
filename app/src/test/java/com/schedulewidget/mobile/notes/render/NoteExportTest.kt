package com.schedulewidget.mobile.notes.render

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteExportTest {
    @Test fun rasterSizeKeepsNormalQualityAndBoundsHugePages() {
        assertEquals(1681 to 2379, NoteExport.rasterSize(595f, 842f))
        assertEquals(2000 to 2000, NoteExport.rasterSize(14_400f, 14_400f))
    }
}
