package com.schedulewidget.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class DesktopJsonReadTest {
    @Test fun exactLimitKeepsUtf8AndBom() {
        val text = "\uFEFF{\"제목\":\"수업\"}"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(text, readDesktopJson(ByteArrayInputStream(bytes), bytes.size.toLong()))
    }

    @Test fun oversizeStreamIsRejectedBeforeJsonParsing() {
        assertNull(readDesktopJson(ByteArrayInputStream("{\"Schedules\":[]}".toByteArray()), 8))
    }
}
