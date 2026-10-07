package com.schedulewidget.mobile.calendar

import com.schedulewidget.mobile.data.AppData
import com.schedulewidget.mobile.data.GoogleCalendarLink
import com.schedulewidget.mobile.data.ScheduleItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GoogleCalendarAccountBoundaryTest {
    @Test
    fun unknownSavedAccountCannotDeleteOrPatchOldLinksAgainstCurrentAccount() {
        val item = ScheduleItem(
            id = "schedule-1", title = "Local schedule", period = "2026-10-07",
            googleEventId = "old-account-event", googleSyncedHash = "old-hash",
            googleSyncedPeriod = "2026-10-06", googleEndMinutes = 60, googleRefusedHash = "refused-hash",
        )
        val saved = AppData(
            schedules = listOf(item),
            googleCalendar = GoogleCalendarLink(
                enabled = true, syncedEventIds = listOf("old-account-event"),
                ownedEventIds = listOf("old-account-event"), hiddenEvents = mapOf("hidden-old-event" to "2026-10-06"),
            ),
        )

        val current = forGoogleCalendarAccount(saved, "new-primary-calendar")
        assertEquals(item.copy(
            googleEventId = null, googleSyncedHash = null, googleSyncedPeriod = null,
            googleEndMinutes = null, googleRefusedHash = null,
        ), current.schedules.single())
        assertEquals("new-primary-calendar", current.googleCalendar.account)
        assertTrue(current.googleCalendar.syncedEventIds.isEmpty())
        assertTrue(current.googleCalendar.ownedEventIds.isEmpty())
        assertTrue(current.googleCalendar.hiddenEvents.isEmpty())
        assertEquals(item, saved.schedules.single())
        assertNull(saved.googleCalendar.account)

        val plan = GoogleCalendarPlanner.plan(current, "new-primary-calendar", LocalDate.of(2026, 10, 7), emptyList())
        assertTrue(plan.deleteRemote.isEmpty())
        assertEquals("schedule-1", plan.insert.single().id)
    }

    @Test
    fun calendarBearerRequestsRejectUntrustedHostsBeforeConnecting() {
        val failure = runCatching {
            GoogleCalendarApi.request("fake-token", "GET", "https://attacker.example/events", null, allowMissing = false)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}
