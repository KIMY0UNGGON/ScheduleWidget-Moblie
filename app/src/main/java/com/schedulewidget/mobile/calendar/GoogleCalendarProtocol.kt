package com.schedulewidget.mobile.calendar

import com.schedulewidget.mobile.data.ScheduleItem
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

internal object GoogleCalendarProtocol {
    internal const val COMPLETED_KEY = "scheduleWidgetCompleted"
    internal const val APP_KEY = "scheduleWidget"
    internal const val UNTITLED = "(제목 없음)"
    private val timeFormat = Regex("""\d{2}:\d{2}""") // desktop parses Time with "hh\:mm" exactly
    internal val offsetFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    private val recurringInstance = Regex("""^[a-v0-9]+_(\d{8})(T\d{6}Z?)?$""")
    private val guidFormat = Regex("""^[0-9a-f]{32}$""")

    internal fun hash(title: String?, period: String?, time: String?, completed: Boolean, endPeriod: String? = null) =
        (title ?: "").trim() + "\u001f" + (period ?: "") + "\u001f" + (time ?: "") + "\u001f" + (if (completed) "1" else "0") +
            (endPeriod?.takeIf { it.isNotEmpty() }?.let { "\u001f$it" } ?: "")

    internal fun hash(i: ScheduleItem) = hash(i.title, i.period, i.time, i.isCompleted, i.endDate?.toString())
    internal fun hash(e: CalendarRemote) = hash(e.title, e.period, e.time, e.completed, e.endPeriod)

    /** Refused by Google as it is now (desktop IsRefused). */
    internal fun isRefused(i: ScheduleItem) = i.googleRefusedHash != null && i.googleRefusedHash == hash(i)

    // ---- event ids (desktop EventIdFor / NewEventId / IsRecurringInstanceId) ----

    /**
     * A new event's id is chosen here (Google allows a-v and 0-9): the first one from the schedule's own id — the same id
     * the PC picks for that schedule — so a request whose answer got lost cannot make a second copy: sent again, Google
     * answers 409 and the schedule links to it.
     */
    internal fun eventIdFor(item: ScheduleItem): String {
        val guid = item.id.trim().removePrefix("{").removeSuffix("}").replace("-", "").lowercase()
        if (guidFormat.matches(guid)) return "sched$guid" // desktop: "sched" + Guid.ToString("N")
        // Not a GUID (never made by either app, but keep it valid): its bytes in hex, which Google's charset allows.
        return "sched" + item.id.toByteArray().joinToString("") { "%02x".format(it) }.take(1000)
    }

    internal fun newEventId() = "sched" + UUID.randomUUID().toString().replace("-", "")

    /** The date of one instance of a repeating Google event, by the id Google gives it ("…_20261005[T090000Z]"); else null. */
    internal fun recurringInstanceDate(id: String?): LocalDate? {
        val m = recurringInstance.matchEntire(id ?: "") ?: return null
        return runCatching { LocalDate.parse(m.groupValues[1], DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
    }

    // Google events read each sync: from 30 days ago through two years ahead; the dates of a repeating event only from
    // today through 8 weeks ahead (a daily meeting would otherwise become hundreds of schedules).
    internal fun windowStart(today: LocalDate) = today.minusDays(30)
    internal fun windowEnd(today: LocalDate) = today.plusYears(2)
    internal fun recurringEnd(today: LocalDate) = today.plusDays(56)

    // ---- event bodies (desktop EventBody / InsertBody / PatchBody / SetTimes) ----

    // The title Google gets: "(제목 없음)" is only how an untitled event is shown here, never a title to send back.
    private fun summary(title: String?) = if (title == UNTITLED) "" else title ?: ""

    /** A new event: all-day without a time, else a timed event (1 hour, or its known length), marked as this app's. */
    internal fun insertBody(item: ScheduleItem, id: String): JSONObject {
        val body = JSONObject().put("summary", summary(item.title))
        setTimes(body, item, wasTimed = null)
        // A new event leaves the other kind of start / end out (PATCH sends it as null so switching all-day <-> timed clears it).
        for (side in listOf("start", "end")) {
            val o = body.getJSONObject(side)
            listOf("date", "dateTime").filter { o.isNull(it) }.forEach { o.remove(it) }
        }
        body.put("extendedProperties", JSONObject().put("private",
            JSONObject().put(COMPLETED_KEY, if (item.isCompleted) "1" else "0").put(APP_KEY, "1")))
        body.put("id", id)
        return body
    }

    /**
     * What a PATCH sends for a schedule changed here: only what differs from the last sync (title, date and time, 완료), so
     * what changed on Google meanwhile — or is never shown here (length, guests, notes) — stays as it is there. [owned]:
     * made by an app (the mark goes along, so the PC knows it too).
     */
    internal fun patchBody(item: ScheduleItem, owned: Boolean): JSONObject {
        val was = (item.googleSyncedHash ?: "").split('\u001f')
        val known = was.size == 4 || was.size == 5
        val wasEnd = was.getOrNull(4).orEmpty()
        val body = JSONObject()
        if (!known || was[0] != item.title.trim()) body.put("summary", summary(item.title))
        if (!known || was[1] != item.period || was[2] != (item.time ?: "") || wasEnd != item.endDate?.toString().orEmpty())
            setTimes(body, item, if (known) was[2].isNotEmpty() else null, if (known) wasEnd.isNotEmpty() else null)
        val properties = JSONObject()
        if (!known || was[3] != (if (item.isCompleted) "1" else "0")) properties.put(COMPLETED_KEY, if (item.isCompleted) "1" else "0")
        if (owned && (body.length() > 0 || properties.length() > 0)) properties.put(APP_KEY, "1")
        if (properties.length() > 0) body.put("extendedProperties", JSONObject().put("private", properties))
        return body
    }

    // start / end of a schedule. wasTimed (what it was at the last sync; null = not known): an all-day event keeps its
    // number of days and a timed one its length; one that just got or lost its time becomes 1 hour / 1 day.
    private fun setTimes(body: JSONObject, item: ScheduleItem, wasTimed: Boolean?, wasRange: Boolean? = null) {
        val date = LocalDate.parse(item.period) // throws for a bad date: the event is skipped as refused
        val last = item.endDate
        val length = item.googleEndMinutes?.takeIf { it > 0 }
        val time = item.time?.takeIf { timeFormat.matches(it) }?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        if (time == null) {
            val end = last?.plusDays(1) ?: date.plusDays(if (wasTimed == true || wasRange == true) 1 else maxOf(1, (length ?: 1440) / 1440).toLong())
            body.put("start", JSONObject().put("date", date.toString()).put("dateTime", JSONObject.NULL))
            body.put("end", JSONObject().put("date", end.toString()).put("dateTime", JSONObject.NULL))
        } else {
            val minutes = if (wasRange == true) 60 else when (wasTimed) {
                true -> length ?: 60
                false -> 60
                null -> length?.takeIf { it < 1440 * 60 && it % 1440 != 0 } ?: 60
            }
            val start = date.atTime(time).atZone(ZoneId.systemDefault()).toOffsetDateTime()
            val end = if (last != null) {
                val clock = if (wasTimed == true && wasRange == true && length != null) date.atTime(time).plusMinutes(length.toLong()).toLocalTime() else time
                (if (clock == LocalTime.MIDNIGHT && wasTimed == true && wasRange == true) last.plusDays(1) else last)
                    .atTime(clock).atZone(ZoneId.systemDefault()).toOffsetDateTime()
            } else start.plusMinutes(minutes.toLong())
            body.put("start", JSONObject().put("dateTime", start.format(offsetFormat)).put("date", JSONObject.NULL))
            body.put("end", JSONObject().put("dateTime", end.format(offsetFormat)).put("date", JSONObject.NULL))
        }
    }
}
