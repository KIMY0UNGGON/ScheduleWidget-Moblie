package com.schedulewidget.mobile.calendar

import com.schedulewidget.mobile.data.ScheduleItem
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal data class CalendarRemote(
    val id: String, val title: String, val period: String, val time: String?, val endMinutes: Int, val completed: Boolean,
    // One date of a repeating event; made by an app (the "scheduleWidget" mark); has guests or someone else organizes it.
    val recurring: Boolean = false, val appTag: Boolean = false, val shared: Boolean = false,
    val endPeriod: String? = null,
) { val lastPeriod: String get() = endPeriod ?: period }

internal object GoogleCalendarApi {
    internal const val EVENTS = "https://www.googleapis.com/calendar/v3/calendars/primary/events"
    // Request only fields consumed by this sync; don't fetch descriptions, locations, conference details or attendee emails.
    private const val EVENT_FIELDS =
        "nextPageToken,items(id,status,start(date,dateTime),end(date,dateTime),summary," +
            "extendedProperties(private(scheduleWidgetCompleted,scheduleWidget)),recurringEventId,recurrence," +
            "attendees(self,resource),organizer(self))"
    internal class Unauthorized : IllegalStateException("구글 로그인이 만료되었습니다. 다시 시도해 주세요.")
    internal class RequestFailed(val code: Int, message: String) : IllegalStateException(message) {
        val refusal get() = code in 400..499 && code != 408 && code != 429
    }
    // ---- reading ----

    // What one sync reads: everything from 30 days ago through 8 weeks ahead with repeating events listed date by date,
    // then the one-off events from there to two years ahead (repeating ones are left out there).
    internal fun readEvents(token: String, today: LocalDate): List<CalendarRemote> {
        val split = GoogleCalendarProtocol.recurringEnd(today).plusDays(1)
        val events = listEvents(token, GoogleCalendarProtocol.windowStart(today), split, singleEvents = true).toMutableList()
        val seen = events.map { it.id }.toMutableSet()
        for (e in listEvents(token, split.minusDays(1), GoogleCalendarProtocol.windowEnd(today), singleEvents = false))
            if (!e.recurring && seen.add(e.id)) events += e
        return events
    }

    private fun listEvents(token: String, from: LocalDate, to: LocalDate, singleEvents: Boolean): List<CalendarRemote> {
        val zone = ZoneId.systemDefault()
        val out = mutableListOf<CalendarRemote>()
        var page: String? = null
        val seenPages = HashSet<String>()
        do {
            val url = "$EVENTS?singleEvents=$singleEvents&maxResults=2500&eventTypes=default" +
                "&timeMin=" + enc(from.atStartOfDay(zone).toOffsetDateTime().format(GoogleCalendarProtocol.offsetFormat)) +
                "&timeMax=" + enc(to.atStartOfDay(zone).toOffsetDateTime().format(GoogleCalendarProtocol.offsetFormat)) +
                "&fields=" + enc(EVENT_FIELDS) +
                (page?.let { "&pageToken=" + enc(it) } ?: "")
            val json = get(token, url)
            val items = json.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) runCatching { parse(items!!.getJSONObject(i), zone)?.let { out += it } }
            page = json.optString("nextPageToken").ifBlank { null }
            if (page != null) {
                check(seenPages.add(page)) { "구글이 같은 일정 페이지를 반복해서 보냈습니다. 다시 시도해 주세요." }
                check(out.size < 20000) { "구글 일정이 너무 많아 동기화를 중단했습니다." }
            }
        } while (page != null)
        return out
    }

    internal fun parseOrNull(e: JSONObject?): CalendarRemote? = e?.let { runCatching { parse(it, ZoneId.systemDefault()) }.getOrNull() }

    private fun parse(e: JSONObject, zone: ZoneId): CalendarRemote? {
        val id = e.optString("id")
        if (id.isEmpty() || e.optString("status") == "cancelled") return null
        val start = e.optJSONObject("start") ?: return null
        val end = e.optJSONObject("end")
        val title = e.optString("summary").trim().ifBlank { GoogleCalendarProtocol.UNTITLED }
        val properties = e.optJSONObject("extendedProperties")?.optJSONObject("private")
        val completed = properties?.optString(GoogleCalendarProtocol.COMPLETED_KEY) == "1"
        val appTag = properties?.optString(GoogleCalendarProtocol.APP_KEY) == "1"
        val recurring = e.has("recurringEventId") || e.has("recurrence")
        val shared = isShared(e)
        val startDate = start.optString("date")
        return if (startDate.isNotBlank()) {
            val day = LocalDate.parse(startDate)
            val endDay = end?.optString("date")?.takeIf { it.isNotBlank() }?.let(LocalDate::parse) ?: day.plusDays(1)
            CalendarRemote(id, title, day.toString(), null, ChronoUnit.MINUTES.between(day.atStartOfDay(), endDay.atStartOfDay()).coerceIn(1440, Int.MAX_VALUE.toLong()).toInt(),
                completed, recurring, appTag, shared, ScheduleItem.normalizeEndPeriod(day.toString(), endDay.minusDays(1).toString()))
        } else {
            val startAt = OffsetDateTime.parse(start.getString("dateTime")).atZoneSameInstant(zone)
            val endAt = end?.optString("dateTime")?.takeIf { it.isNotBlank() }?.let { OffsetDateTime.parse(it).atZoneSameInstant(zone) } ?: startAt.plusHours(1)
            val last = if (endAt.toLocalTime() == LocalTime.MIDNIGHT) endAt.toLocalDate().minusDays(1) else endAt.toLocalDate()
            CalendarRemote(id, title, startAt.toLocalDate().toString(), "%02d:%02d".format(startAt.hour, startAt.minute),
                Duration.between(startAt, endAt).toMinutes().coerceIn(1, Int.MAX_VALUE.toLong()).toInt(), completed, recurring, appTag, shared,
                ScheduleItem.normalizeEndPeriod(startAt.toLocalDate().toString(), last.toString()))
        }
    }

    // Guests besides this calendar (meeting rooms aside), or organized by someone else.
    internal fun isShared(e: JSONObject): Boolean {
        val attendees: JSONArray? = e.optJSONArray("attendees")
        for (i in 0 until (attendees?.length() ?: 0)) {
            val a = attendees!!.optJSONObject(i) ?: continue
            if (!a.optBoolean("self") && !a.optBoolean("resource")) return true
        }
        val organizer = e.optJSONObject("organizer")
        return organizer != null && !organizer.optBoolean("self")
    }

    // ---- HTTP ----

    internal fun eventUrl(id: String) = "$EVENTS/${enc(id)}"

    internal fun get(token: String, url: String) = request(token, "GET", url, null, allowMissing = false)!!

    internal fun request(token: String, method: String, url: String, body: JSONObject?, allowMissing: Boolean): JSONObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        // HttpURLConnection has no PATCH: use the override header Google APIs accept.
        if (method == "PATCH") { conn.requestMethod = "POST"; conn.setRequestProperty("X-HTTP-Method-Override", "PATCH") }
        else conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        try {
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            if (allowMissing && (code == 404 || code == 410)) return null
            if (code == 401) throw Unauthorized()
            if (code !in 200..299) {
                val text = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull() ?: ""
                throwForStatus(code, text)
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { conn.disconnect() }
    }

    // desktop ThrowForStatusAsync: Google's rate limit, a missing permission and a switched-off API stop the whole sync;
    // anything else is one request's error (RequestFailed, a refusal when 4xx).
    private fun throwForStatus(code: Int, text: String): Nothing {
        val error = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
        val reasons = buildList {
            error?.optString("status")?.takeIf { it.isNotEmpty() }?.let { add(it) }
            for (list in listOf("errors", "details")) {
                val arr = error?.optJSONArray(list) ?: continue
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("reason")?.takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }.joinToString(" ")
        val message = error?.optString("message")?.ifBlank { null }
        fun has(vararg words: String) = words.any { reasons.contains(it) }
        if (code == 429 || (code == 403 && has("rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded", "dailyLimitExceeded",
                "RATE_LIMIT_EXCEEDED", "RESOURCE_EXHAUSTED")))
            throw IllegalStateException("구글 요청 한도에 걸렸습니다. 잠시 쉬었다가 다시 동기화합니다.")
        if (code == 403) {
            if (has("ACCESS_TOKEN_SCOPE_INSUFFICIENT", "insufficientScopes") || text.contains("insufficient authentication scopes", ignoreCase = true))
                throw IllegalStateException("구글 캘린더 권한이 없습니다. ‘연결 해제’ 후 다시 켜고 일정 및 기본 캘린더 정보 권한을 허용해 주세요.")
            if (text.contains("accessNotConfigured", ignoreCase = true) || text.contains("SERVICE_DISABLED"))
                throw IllegalStateException("구글 캘린더를 사용할 수 없습니다: Cloud 프로젝트에서 Google Calendar API 사용 설정이 필요합니다.")
        }
        throw RequestFailed(code, "구글 캘린더 오류 $code${message?.let { ": $it" } ?: ""}")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
