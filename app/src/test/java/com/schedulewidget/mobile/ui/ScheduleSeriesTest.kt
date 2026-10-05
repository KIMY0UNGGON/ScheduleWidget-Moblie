package com.schedulewidget.mobile.ui

import com.schedulewidget.mobile.data.ScheduleItem
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ScheduleSeriesTest {
    private val start = LocalDate.of(2026, 10, 3)

    @Test fun continuousPeriodStaysOneScheduleAcrossMonthBoundary() {
        val first = LocalDate.of(2028, 2, 28)
        assertEquals(listOf(first),
            ScheduleSeries.dates(first, Repeat.CONTINUOUS, LocalDate.of(2028, 3, 1)))
        assertEquals(listOf(start), ScheduleSeries.dates(start, Repeat.CONTINUOUS, start))
    }

    @Test fun continuousExpansionKeepsOneRangeAndItsCalendarLinks() {
        val first = ScheduleItem(title = "연수", period = start.toString(), time = "09:30", color = "#FF141414",
            memo = "준비물", important = true, eventId = 12L, googleEventId = "linked")
        val items = ScheduleSeries.expand(first, Repeat.CONTINUOUS, start.plusDays(3))
        assertEquals(1, items.size)
        assertEquals(start.toString(), items.single().period)
        assertEquals(start.plusDays(3), items.single().endDate)
        assertEquals(first.id, items.single().id)
        assertNull(items.single().seriesId)
        assertEquals(first.eventId, items.single().eventId)
        assertEquals("linked", items.single().googleEventId)
        assertTrue(items.single().title == first.title && items.single().time == first.time && items.single().color == first.color &&
            items.single().memo == first.memo && items.single().important)
    }

    @Test fun invalidContinuousRangeCannotBeSavedOrSilentlyTruncated() {
        val form = ScheduleFormState.of(null, start).apply { title = "연수"; repeat = Repeat.CONTINUOUS }
        for (end in listOf(start.minusDays(1), start.plusDays(ScheduleItem.MAX_RANGE_DAYS + 1L))) {
            form.until = end
            assertFalse(form.canSave)
            assertThrows(IllegalArgumentException::class.java) { ScheduleSeries.dates(start, Repeat.CONTINUOUS, end) }
        }
        form.until = start.plusDays(ScheduleItem.MAX_RANGE_DAYS.toLong())
        assertTrue(form.canSave)
        assertEquals(1, form.build(quick = false).size)
        assertEquals(start.plusDays(ScheduleItem.MAX_RANGE_DAYS.toLong()), form.build(quick = false).single().endDate)
    }

    @Test fun chosenContinuousDatesSurviveQuickEntryAndSeriesEdits() {
        val form = ScheduleFormState.of(null, start).apply {
            title = "내일 09:30 연수"; repeat = Repeat.CONTINUOUS; until = start.plusDays(2); dateTouched = true
        }
        val items = form.build(quick = true)
        assertEquals(1, items.size)
        assertEquals(start, items.single().date)
        assertEquals(start.plusDays(2), items.single().endDate)
        assertEquals("09:30", items.single().time)
        assertEquals("내일 연수", items.single().title)
        assertNull(items.single().seriesId)
    }

    @Test fun previousRepeatModesKeepTheirDates() {
        val january = LocalDate.of(2027, 1, 31)
        assertEquals(listOf(january, LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31)),
            ScheduleSeries.dates(january, Repeat.MONTHLY, LocalDate.of(2027, 3, 31)))
        assertEquals(listOf(start, start.plusWeeks(1), start.plusWeeks(2)),
            ScheduleSeries.dates(start, Repeat.WEEKLY, start.plusWeeks(2)))
        assertEquals(listOf(start), ScheduleSeries.dates(start, Repeat.NONE, start.plusDays(5)))
        assertEquals(4, ScheduleSeries.dates(start, Repeat.DAILY, start.plusDays(3)).size)
    }
}
