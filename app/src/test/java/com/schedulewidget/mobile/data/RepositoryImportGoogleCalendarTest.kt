package com.schedulewidget.mobile.data

import com.schedulewidget.mobile.calendar.CalendarRemote
import com.schedulewidget.mobile.calendar.GoogleCalendarPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RepositoryImportGoogleCalendarTest {
    @Test
    fun importedSchedulesCannotDeleteEventsLinkedByTheReplacedFile() {
        val imported = AppData(
            schedules = listOf(ScheduleItem(
                id = "imported",
                title = "Imported schedule",
                period = "2026-10-06",
                googleEventId = "desktop-event",
                googleSyncedHash = "old-hash",
            )),
            googleCalendar = GoogleCalendarLink(
                enabled = true,
                account = "old-account",
                syncedEventIds = listOf("old-event"),
                ownedEventIds = listOf("old-event"),
                hiddenEvents = mapOf("hidden-event" to "2026-10-05"),
                lastSync = 123,
                lastError = "old error",
            ),
        )

        val unlinked = clearGoogleCalendarLinks(imported)
        val oldRemote = CalendarRemote("old-event", "Old schedule", "2026-10-05", null, 1440, false, appTag = true)
        val plan = GoogleCalendarPlanner.plan(unlinked, "old-account", LocalDate.of(2026, 10, 5), listOf(oldRemote))

        assertFalse(unlinked.googleCalendar.enabled)
        assertNull(unlinked.googleCalendar.account)
        assertTrue(unlinked.googleCalendar.syncedEventIds.isEmpty())
        assertTrue(unlinked.googleCalendar.ownedEventIds.isEmpty())
        assertTrue(unlinked.googleCalendar.hiddenEvents.isEmpty())
        assertEquals(0L, unlinked.googleCalendar.lastSync)
        assertNull(unlinked.googleCalendar.lastError)
        assertNull(unlinked.schedules.single().googleEventId)
        assertNull(unlinked.schedules.single().googleSyncedHash)
        assertNull(unlinked.schedules.single().googleSyncedPeriod)
        assertNull(unlinked.schedules.single().googleEndMinutes)
        assertNull(unlinked.schedules.single().googleRefusedHash)
        assertTrue(plan.deleteRemote.isEmpty())
    }

    @Test
    fun importEpochRejectsACommitCapturedBeforeImport() {
        val fence = ImportEpoch()
        val (beforeImport, state) = fence.capture { "old sync state" }
        assertEquals("old sync state", state)
        fence.advance { }

        var committed = false
        assertFalse(fence.runIfCurrent(beforeImport) { committed = true })
        assertFalse(committed)
    }
}
