package com.schedulewidget.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GoogleCalendarLink(
    @SerialName("Enabled") val enabled: Boolean = false,
    // The account the schedules' GoogleEventIds belong to; another account starts fresh.
    @SerialName("Account") val account: String? = null,
    @SerialName("SyncedEventIds") val syncedEventIds: List<String> = emptyList(),
    @SerialName("LastSync") val lastSync: Long = 0,
    @SerialName("LastError") val lastError: String? = null,
    // Same meaning as the PC's GoogleCalendar.OwnedEventIds / HiddenEvents: events this app created (deleting the
    // schedule deletes them on Google), and events only removed from the app (never re-imported; id -> reason/time).
    @SerialName("OwnedEventIds") val ownedEventIds: List<String> = emptyList(),
    @SerialName("HiddenEvents") val hiddenEvents: Map<String, String> = emptyMap(),
)

@Serializable
data class CalendarSync(
    // Show events from device calendars on the mini calendar / widget.
    @SerialName("ShowEvents") val showEvents: Boolean = false,
    // CalendarContract.Calendars._ID values to read. Empty = all visible calendars.
    // Calendars the user unticked. Stored as exclusions so calendars that sync in later (a new Google account or
    // calendar) show up automatically. (The old "CalendarIds" inclusion list is no longer read.)
    @SerialName("ExcludedCalendarIds") val excludedIds: List<Long> = emptyList(),
    // Calendar to write schedules into when "export to calendar" is used (or auto-push is on). null = ask/none.
    @SerialName("TargetCalendarId") val targetCalendarId: Long? = null,
    // Automatically push schedule add/edit/delete into the target calendar.
    @SerialName("AutoPush") val autoPush: Boolean = false,
)
