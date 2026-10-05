package com.schedulewidget.mobile.calendar

import com.schedulewidget.mobile.data.AppData
import com.schedulewidget.mobile.data.ScheduleItem
import java.time.LocalDate

internal data class GoogleCalendarPlan(
    val local: List<ScheduleItem>,
    val syncedIds: Set<String>,
    val owned: MutableSet<String>,
    val hidden: LinkedHashMap<String, String>,
    val localByEvent: LinkedHashMap<String, ScheduleItem>,
    val remoteById: LinkedHashMap<String, CalendarRemote>,
    val deleteRemote: MutableList<String>,
    val unlink: LinkedHashMap<String, String>,
    val patch: MutableList<ScheduleItem>,
    val insert: MutableList<ScheduleItem>,
    val fresh: MutableSet<String>,
    val updateLocal: MutableList<Pair<ScheduleItem, CalendarRemote>>,
    val updateLength: MutableList<Pair<ScheduleItem, CalendarRemote>>,
    val addLocal: MutableList<CalendarRemote>,
    val prune: MutableList<ScheduleItem>,
    val remoteOf: HashMap<String, CalendarRemote>,
)

internal object GoogleCalendarPlanner {
    fun plan(snapshot: AppData, account: String?, today: LocalDate, remote: List<CalendarRemote>): GoogleCalendarPlan {
        val recurringEnd = GoogleCalendarProtocol.recurringEnd(today)
        val settings = snapshot.googleCalendar
        // Another account than last time: start fresh (the event ids belong to the other account).
        val sameAccount = account == null || settings.account.isNullOrBlank() || settings.account == account
        val unlinked = if (sameAccount) snapshot.schedules
        else snapshot.schedules.map { it.copy(googleEventId = null, googleSyncedHash = null, googleSyncedPeriod = null, googleRefusedHash = null) }
        val syncedIds = if (sameAccount) settings.syncedEventIds.toSet() else emptySet()
        val owned = (if (sameAccount) settings.ownedEventIds else emptyList()).toMutableSet()
        val hidden = LinkedHashMap(if (sameAccount) settings.hiddenEvents else emptyMap())

        // desktop UpdateOwnership: one with guests or a repeating one is never owned; one tagged by an app (this one or
        // the PC) is.
        for (e in remote) {
            if (e.shared || e.recurring) owned -= e.id
            else if (e.appTag) owned += e.id
        }

        // A schedule without a Google id that matches an event exactly (e.g. one the phone saved to the same calendar
        // before, or the PC's copy) is linked to it instead of creating a second event and a second schedule. An event
        // is linked to at most one schedule, and never to one another schedule already points at, nor to one taken out
        // of the app (hidden).
        val taken = unlinked.mapNotNull { it.googleEventId?.ifEmpty { null } }.toMutableSet()
        taken += hidden.keys
        taken += syncedIds // linked before and deleted here: handled below, never picked up again
        val byHash = remote.groupBy { GoogleCalendarProtocol.hash(it.title, it.period, it.time, false, it.endPeriod) }
        val local = unlinked.map { item ->
            if (!item.googleEventId.isNullOrEmpty() || item.date == null) return@map item
            val match = byHash[GoogleCalendarProtocol.hash(item.title, item.period, item.time, false, item.endDate?.toString())]?.firstOrNull { it.id !in taken } ?: return@map item
            taken += match.id
            item.copy(googleEventId = match.id, googleSyncedHash = GoogleCalendarProtocol.hash(match), googleSyncedPeriod = match.period, googleEndMinutes = match.endMinutes)
        }

        // ---- plan (desktop GoogleCalendarSync.Plan) ----
        val localByEvent = LinkedHashMap<String, ScheduleItem>()
        local.forEach { i -> i.googleEventId?.takeIf { it.isNotEmpty() }?.let { if (it !in localByEvent) localByEvent[it] = i } }
        val remoteById = LinkedHashMap<String, CalendarRemote>().apply { remote.forEach { put(it.id, it) } }
        val oldest = today.minusDays(30)

        val deleteRemote = mutableListOf<String>()
        val unlink = LinkedHashMap<String, String>() // id -> date ("" when unknown)
        for (id in syncedIds) {
            if (id.isEmpty() || id in localByEvent) continue
            val there = remoteById[id]
            // Deleted here: an app-made event without guests goes on Google too; any other one (a shared meeting, one made
            // in Google Calendar, one date of a repeating event) only leaves the app and is not brought back.
            if (id in owned && there?.shared != true && GoogleCalendarProtocol.recurringInstanceDate(id) == null) deleteRemote += id
            else unlink[id] = there?.lastPeriod ?: GoogleCalendarProtocol.recurringInstanceDate(id)?.toString() ?: ""
        }

        val patch = mutableListOf<ScheduleItem>()
        val insert = mutableListOf<ScheduleItem>()
        val fresh = mutableSetOf<String>() // schedule ids that get a new event id (a copy sharing another one's event)
        val updateLocal = mutableListOf<Pair<ScheduleItem, CalendarRemote>>()
        val updateLength = mutableListOf<Pair<ScheduleItem, CalendarRemote>>()
        val addLocal = mutableListOf<CalendarRemote>()
        val prune = mutableListOf<ScheduleItem>()
        val remoteOf = HashMap<String, CalendarRemote>() // schedule id -> its event, when read
        for (e in remote) {
            if (e.id in syncedIds && e.id !in localByEvent) continue // deleted here (above)
            val item = localByEvent[e.id]
            if (item != null) {
                remoteOf[item.id] = e
                when {
                    GoogleCalendarProtocol.hash(item) != item.googleSyncedHash -> if (item.date != null && !GoogleCalendarProtocol.isRefused(item)) patch += item
                    GoogleCalendarProtocol.hash(e) != item.googleSyncedHash -> updateLocal += item to e
                    item.googleEndMinutes != e.endMinutes -> updateLength += item to e
                }
            } else if (e.id in hidden) continue // taken out of the app: stays out
            else if (!e.recurring || e.period.toDateOrNull()?.let { !it.isBefore(today) && !it.isAfter(recurringEnd) } == true) addLocal += e
        }
        for (item in local) {
            val eid = item.googleEventId
            val date = item.date
            if (eid.isNullOrEmpty()) {
                // Old completed schedules (over 30 days) are not uploaded.
                if (date != null && !(item.isCompleted && date.isBefore(oldest)) && !GoogleCalendarProtocol.isRefused(item)) insert += item
                continue
            }
            if (localByEvent[eid] !== item) {
                if (date != null && !GoogleCalendarProtocol.isRefused(item)) { insert += item; fresh += item.id } // a duplicated id: the copy gets its own event
                continue
            }
            if (eid in remoteById) continue
            // Not in the list (outside the window, or deleted on Google): only our own edits are sent; a 404 re-creates it.
            when {
                // Sent before but the answer never came: sent again under the same id (Google's 409 then links it).
                item.googleSyncedHash == null -> if (date != null && !GoogleCalendarProtocol.isRefused(item)) insert += item
                GoogleCalendarProtocol.hash(item) != item.googleSyncedHash -> if (date != null && !GoogleCalendarProtocol.isRefused(item)) patch += item
                // A repeating date read beyond 8 weeks (older versions read two years): leaves the list, comes back later.
                GoogleCalendarProtocol.recurringInstanceDate(eid) != null && date != null && date.isAfter(recurringEnd) && item.color.isNullOrBlank() -> prune += item
            }
        }
        return GoogleCalendarPlan(
            local, syncedIds, owned, hidden, localByEvent, remoteById, deleteRemote, unlink, patch, insert, fresh,
            updateLocal, updateLength, addLocal, prune, remoteOf,
        )
    }

    private fun String.toDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()
}