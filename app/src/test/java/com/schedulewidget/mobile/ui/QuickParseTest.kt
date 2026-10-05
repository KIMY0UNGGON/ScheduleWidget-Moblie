package com.schedulewidget.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class QuickParseTest {
    // A Wednesday.
    private val today = LocalDate.of(2026, 9, 30)

    @Test
    fun relativeDayAndAfternoonTime() {
        val r = QuickParse.parse("내일 오후 3시 회의", today)
        assertEquals(LocalDate.of(2026, 10, 1), r.date)
        assertEquals("15:00", r.time)
        assertEquals("회의", r.title)
    }

    @Test
    fun monthAndDay() {
        val r = QuickParse.parse("10월 3일 병원", today)
        assertEquals(LocalDate.of(2026, 10, 3), r.date)
        assertNull(r.time)
        assertEquals("병원", r.title)
    }

    @Test
    fun nextWeekWeekdayWithHalfHour() {
        val r = QuickParse.parse("다음주 월요일 9시 반 스터디", today)
        assertEquals(LocalDate.of(2026, 10, 5), r.date)
        assertEquals("09:30", r.time)
        assertEquals("스터디", r.title)
    }

    @Test
    fun bareSmallHourIsAfternoon() {
        val r = QuickParse.parse("3시 운동", today)
        assertEquals("15:00", r.time)
        assertEquals("운동", r.title)
    }

    @Test
    fun wordInsideTitleIsNotParsed() {
        val r = QuickParse.parse("오늘의 할 일", today)
        assertFalse(r.found)
        assertEquals("오늘의 할 일", r.title)
    }

    @Test
    fun manualFieldsAreLeftAlone() {
        val r = QuickParse.parse("내일 3시 회의", today, dates = false, times = false)
        assertFalse(r.found)
    }

    @Test
    fun invalidMonthDateIsNotReinterpretedAsDayOnly() {
        for (text in listOf("4월 31일 병원", "13월 10일 회의")) {
            val r = QuickParse.parse(text, today)
            assertNull(r.date)
            assertEquals(text, r.title)
        }
    }
}
