package com.schedulewidget.mobile.calendar

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.data.ScheduleItem
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class DeviceCalendarInfo(
    val id: Long,
    val name: String,
    val account: String,
    val color: Int,
    val writable: Boolean,
    val accountType: String = "",
    val ownerAccount: String = "",
) {
    val isGoogle: Boolean get() = accountType == "com.google"

    /** The account's own (primary) Google calendar — what the Google Calendar API calls "primary". */
    fun isPrimaryOf(googleAccount: String): Boolean =
        isGoogle && account.equals(googleAccount, true) &&
            (name.equals(googleAccount, true) || ownerAccount.equals(googleAccount, true))
}

/** An event read from a device calendar (Google Calendar account, Samsung/system calendar, ...). */
data class DeviceEvent(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val date: LocalDate,
    /** HH:mm, null for all-day events (and for continuation days of multi-day timed events) */
    val time: String?,
    val color: Int?,
    /** Start of this occurrence (epoch ms), needed to delete just one day of a repeating event. */
    val begin: Long = 0,
    val recurring: Boolean = false,
    /**
     * Whether one day of a repeating event can be removed here. Needs a synced event (Google etc.): for purely local
     * events Android's calendar provider drops the whole series from its day list after such an exception.
     */
    val canDeleteOne: Boolean = false,
    val calendarName: String = "",
    /** False for read-only calendars (holidays, subscribed calendars): the app cannot delete from them. */
    val writable: Boolean = true,
    /** Marked done here: the stored title carries [DeviceCalendar.DONE_PREFIX]; [title] is shown without it. */
    val done: Boolean = false,
    val allDay: Boolean = false,
    /** End of this occurrence (epoch ms); an edit keeps the event's length. */
    val end: Long = 0,
)

/** One occurrence from the Instances table (recurring events already expanded). */
internal data class DeviceInstance(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val allDay: Boolean,
    val begin: Long,
    val end: Long,
    val color: Int?,
    val recurring: Boolean = false,
    val synced: Boolean = false,
    val calendarName: String = "",
    val writable: Boolean = true,
) {
    /** First day and last day (inclusive). All-day events are stored as UTC midnights. */
    fun days(zone: ZoneId): ClosedRange<LocalDate> {
        return if (allDay) {
            val start = Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate()
            val endEx = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()
            start..(if (endEx > start) endEx.minusDays(1) else start)
        } else {
            val start = Instant.ofEpochMilli(begin).atZone(zone).toLocalDate()
            val last = Instant.ofEpochMilli(maxOf(begin, end - 1)).atZone(zone).toLocalDate()
            start..maxOf(start, last)
        }
    }
}

/**
 * CalendarContract access. Google Calendar accounts synced on the device and the system (e.g. Samsung) calendar
 * all appear here, so no OAuth is needed. All functions are safe without permission (they return empty/null).
 * Call from a background thread.
 */
object DeviceCalendar {
    const val DESCRIPTION = "일정 위젯"
    const val DONE_PREFIX = "✓ "
    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    fun hasPermission(context: Context): Boolean = canRead(context)

    private fun canRead(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun canWrite(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(context: Context): List<DeviceCalendarInfo> {
        if (!canRead(context)) return emptyList()
        val projection = arrayOf(
            Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE,
            Calendars.CALENDAR_COLOR, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.OWNER_ACCOUNT,
        )
        val result = mutableListOf<DeviceCalendarInfo>()
        runCatching {
            context.contentResolver.query(
                Calendars.CONTENT_URI, projection, "${Calendars.VISIBLE} = 1", null,
                "${Calendars.ACCOUNT_NAME} ASC, ${Calendars.CALENDAR_DISPLAY_NAME} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    result += DeviceCalendarInfo(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "",
                        account = c.getString(2) ?: "",
                        accountType = c.getString(3) ?: "",
                        color = c.getInt(4),
                        writable = c.getInt(5) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                        ownerAccount = c.getString(6) ?: "",
                    )
                }
            }
        }
        return result
    }

    /** Events overlapping [from, toExclusive) from every visible calendar except AppData.calendar.excludedIds. Empty if ShowEvents is off. */
    fun events(context: Context, from: LocalDate, toExclusive: LocalDate): List<DeviceEvent> {
        val data = Repository.get(context).data.value
        val settings = data.calendar
        if (!settings.showEvents) return emptyList()
        // Events exported from our own schedules are already shown as the schedule itself.
        val linked = data.schedules.mapNotNullTo(HashSet()) { it.eventId }
        // With the Google Calendar sync on, that account's primary calendar is already the schedule list itself.
        val skipCalendars = syncedPrimaryCalendars(context)
        val sameAsSchedule = data.schedules.mapTo(HashSet()) { it.title.trim().lowercase() to it.period }
        val zone = ZoneId.systemDefault()
        val out = mutableListOf<DeviceEvent>()
        for (inst in instances(context, from, toExclusive, settings.excludedIds)) {
            if (inst.eventId in linked || inst.calendarId in skipCalendars) continue
            // Already a schedule with the same title on that day (e.g. imported as 할 일): show it once, as the schedule.
            if (inst.title.trim().lowercase() to inst.days(zone).start.toString() in sameAsSchedule) continue
            val range = inst.days(zone)
            var day = maxOf(range.start, from)
            val last = minOf(range.endInclusive, toExclusive.minusDays(1))
            while (day <= last) {
                val time = if (!inst.allDay && day == range.start)
                    Instant.ofEpochMilli(inst.begin).atZone(zone).toLocalTime().format(HHMM) else null
                out += DeviceEvent(
                    inst.eventId, inst.calendarId, inst.title.removePrefix(DONE_PREFIX).trim(), day, time, inst.color,
                    done = inst.title.startsWith(DONE_PREFIX), allDay = inst.allDay, end = inst.end,
                    begin = inst.begin, recurring = inst.recurring, canDeleteOne = inst.recurring && inst.synced, calendarName = inst.calendarName, writable = inst.writable,
                )
                day = day.plusDays(1)
            }
        }
        return out.distinctBy { Triple(it.date, it.time, it.title.trim()) }
            .sortedWith(compareBy({ it.date }, { it.time != null }, { it.time }))
    }

    /**
     * With the Google Calendar API sync on, that account's primary calendar holds the schedules themselves; its device
     * copy (the same account synced by Android) must not be shown or imported a second time. Empty when sync is off.
     */
    internal fun syncedPrimaryCalendars(context: Context): Set<Long> {
        val gcal = Repository.get(context).data.value.googleCalendar
        val account = gcal.account?.takeIf { gcal.enabled && it.isNotBlank() } ?: return emptySet()
        return calendars(context).filter { it.isPrimaryOf(account) }.mapTo(HashSet()) { it.id }
    }

    internal fun instances(context: Context, from: LocalDate, toExclusive: LocalDate, excludedIds: List<Long>): List<DeviceInstance> {
        if (!canRead(context) || toExclusive <= from) return emptyList()
        val zone = ZoneId.systemDefault()
        // Widen by a day on each side: all-day events are UTC-based, then filter by the computed dates.
        val begin = from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = toExclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID, Instances.CALENDAR_ID, Instances.TITLE, Instances.ALL_DAY,
            Instances.BEGIN, Instances.END, Instances.DISPLAY_COLOR,
            Instances.RRULE, Instances.RDATE, Instances.CALENDAR_DISPLAY_NAME, Instances.CALENDAR_ACCESS_LEVEL,
            Events._SYNC_ID,
        )
        val selection = buildString {
            append("${Instances.VISIBLE} = 1")
            // A day removed from a repeating event stays behind as a canceled exception: don't show it.
            append(" AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Events.STATUS_CANCELED})")
            if (excludedIds.isNotEmpty()) append(" AND ${Instances.CALENDAR_ID} NOT IN (${excludedIds.joinToString(",")})")
        }
        val result = mutableListOf<DeviceInstance>()
        runCatching {
            context.contentResolver.query(uri, projection, selection, null, "${Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val inst = DeviceInstance(
                        eventId = c.getLong(0),
                        calendarId = c.getLong(1),
                        title = c.getString(2)?.takeIf { it.isNotBlank() } ?: "(제목 없음)",
                        allDay = c.getInt(3) != 0,
                        begin = c.getLong(4),
                        end = c.getLong(5),
                        color = if (c.isNull(6)) null else c.getInt(6),
                        recurring = !c.getString(7).isNullOrBlank() || !c.getString(8).isNullOrBlank(),
                        calendarName = c.getString(9).orEmpty(),
                        writable = c.getInt(10) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                        synced = !c.getString(11).isNullOrBlank(),
                    )
                    val days = inst.days(zone)
                    if (days.endInclusive >= from && days.start < toExclusive) result += inst
                }
            }
        }
        return result
    }

    private fun eventExists(context: Context, eventId: Long): Boolean = runCatching {
        context.contentResolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId), arrayOf(Events._ID, Events.DELETED), null, null, null,
        )?.use { c -> c.moveToFirst() && c.getInt(1) == 0 } ?: false
    }.getOrDefault(false)

    /** Inserts or updates [item] in [calendarId]; returns the event id. */
    fun push(context: Context, item: ScheduleItem, calendarId: Long): Long? {
        if (!canWrite(context)) return null
        val date = item.date ?: return null
        val time = item.time?.let { runCatching { LocalTime.parse(it, HHMM) }.getOrNull() }
        val values = ContentValues().apply {
            put(Events.TITLE, (if (item.isCompleted) DONE_PREFIX else "") + item.title)
            put(Events.DESCRIPTION, DESCRIPTION)
            if (time == null) {
                val start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                put(Events.DTSTART, start)
                put(Events.DTEND, (item.lastDate ?: date).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                put(Events.EVENT_TIMEZONE, "UTC")
                put(Events.ALL_DAY, 1)
            } else {
                val zone = ZoneId.systemDefault()
                val start = date.atTime(time).atZone(zone)
                put(Events.DTSTART, start.toInstant().toEpochMilli())
                val end = item.endDate?.atTime(time)?.atZone(zone) ?: start.plusHours(1)
                put(Events.DTEND, end.toInstant().toEpochMilli())
                put(Events.EVENT_TIMEZONE, zone.id)
                put(Events.ALL_DAY, 0)
            }
        }
        return runCatching {
            val existing = item.eventId
            if (existing != null && eventExists(context, existing)) {
                context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, existing), values, null, null)
                existing
            } else {
                values.put(Events.CALENDAR_ID, calendarId)
                context.contentResolver.insert(Events.CONTENT_URI, values)?.let { ContentUris.parseId(it) }
            }
        }.getOrNull()
    }

    /** Deletes the whole event (every repetition). Google-account calendars sync the deletion to Google. */
    fun delete(context: Context, eventId: Long): Boolean {
        if (!canWrite(context)) return false
        return runCatching {
            context.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null) > 0
        }.getOrDefault(false)
    }

    /** Marks [event] done / not done by adding or removing [DONE_PREFIX] (a repeating event: the whole series). */
    fun setDone(context: Context, event: DeviceEvent, done: Boolean): Boolean {
        if (!canWrite(context)) return false
        val values = ContentValues().apply { put(Events.TITLE, (if (done) DONE_PREFIX else "") + event.title) }
        return runCatching {
            context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, event.id), values, null, null) > 0
        }.getOrDefault(false)
    }

    /**
     * Changes [event]'s title and, for a single (non-repeating) event, its day and time: [time] null = all day. The
     * event keeps its length (a timed event that was all day gets one hour). A repeating event only gets the new title.
     */
    fun updateEvent(context: Context, event: DeviceEvent, title: String, date: LocalDate, time: String?): Boolean {
        if (!canWrite(context)) return false
        val values = ContentValues().apply {
            put(Events.TITLE, (if (event.done) DONE_PREFIX else "") + title)
            if (!event.recurring) {
                val parsed = time?.let { runCatching { LocalTime.parse(it, HHMM) }.getOrNull() }
                if (parsed == null) {
                    val days = if (event.allDay && event.end > event.begin) ((event.end - event.begin) / 86_400_000L).coerceAtLeast(1) else 1
                    put(Events.DTSTART, date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                    put(Events.DTEND, date.plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                    put(Events.EVENT_TIMEZONE, "UTC")
                    put(Events.ALL_DAY, 1)
                } else {
                    val zone = ZoneId.systemDefault()
                    val length = if (!event.allDay && event.end > event.begin) event.end - event.begin else 3_600_000L
                    val start = date.atTime(parsed).atZone(zone).toInstant().toEpochMilli()
                    put(Events.DTSTART, start)
                    put(Events.DTEND, start + length)
                    put(Events.EVENT_TIMEZONE, zone.id)
                    put(Events.ALL_DAY, 0)
                }
            }
        }
        return runCatching {
            context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, event.id), values, null, null) > 0
        }.getOrDefault(false)
    }

    /** Cancels only the occurrence of a repeating event that starts at [begin] (the rest of the series stays). */
    fun deleteOccurrence(context: Context, eventId: Long, begin: Long): Boolean {
        if (!canWrite(context)) return false
        val values = ContentValues().apply {
            put(Events.ORIGINAL_INSTANCE_TIME, begin)
            put(Events.STATUS, Events.STATUS_CANCELED)
        }
        return runCatching {
            context.contentResolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, eventId), values) != null
        }.getOrDefault(false)
    }

    /** Opens the system calendar app at [date] (or the event if eventId given). */
    fun open(context: Context, date: LocalDate, eventId: Long? = null) {
        val millis = date.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val primary = if (eventId != null) {
            Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(Events.CONTENT_URI, eventId))
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/$millis"))
        }
        val fallback = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
        for (intent in listOf(primary, fallback)) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }

    /**
     * Hook for schedule CRUD: pushes/deletes when AppData.calendar.autoPush is on. Returns the updated item (with
     * eventId). Completed items keep their event with a "✓ " title prefix.
     */
    fun onScheduleChanged(context: Context, before: ScheduleItem?, after: ScheduleItem?): ScheduleItem? {
        val settings = Repository.get(context).data.value.calendar
        val target = settings.targetCalendarId
        // With the Google Calendar sync on, schedules already go to Google through the API. An event pushed here earlier
        // is still removed with its schedule, or it would stay behind and show up as a separate device event.
        if (Repository.get(context).data.value.googleCalendar.enabled) {
            if (after == null) before?.eventId?.let { delete(context, it) }
            return after
        }
        if (!settings.autoPush || target == null || !canWrite(context)) return after
        if (after == null) {
            before?.eventId?.let { delete(context, it) }
            return null
        }
        val withId = if (after.eventId == null && before?.eventId != null) after.copy(eventId = before.eventId) else after
        val id = push(context, withId, target) ?: return withId
        return withId.copy(eventId = id)
    }

    /** Calls [onChange] (main thread) whenever calendar data changes on the device. Returns an unregister lambda. */
    fun observeChanges(context: Context, onChange: () -> Unit): () -> Unit {
        if (!canRead(context)) return {}
        val app = context.applicationContext
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        return runCatching {
            app.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            val unregister: () -> Unit = { app.contentResolver.unregisterContentObserver(observer) }
            unregister
        }.getOrDefault {}
    }
}
