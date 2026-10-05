package com.schedulewidget.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The PC's ReminderSettings (Models/FeatureSettings.cs): same keys and defaults. The phone uses Enabled/DaysBefore/Hour/Minute. */
@Serializable
data class ReminderSettings(
    @SerialName("Enabled") val enabled: Boolean = false,
    @SerialName("Desktop") val desktop: Boolean = true,
    @SerialName("Telegram") val telegram: Boolean = false,
    @SerialName("Kakao") val kakao: Boolean = false,
    @SerialName("DaysBefore") val daysBefore: Int = 0,
    @SerialName("Hour") val hour: Int = 9,
    @SerialName("Minute") val minute: Int = 0,
)

@Serializable
data class ScheduleItem(
    @SerialName("Id") val id: String = UUID.randomUUID().toString(),
    @SerialName("Title") val title: String = "",
    // yyyy-MM-dd
    @SerialName("Period") val period: String = "",
    // HH:mm or null
    @SerialName("Time") val time: String? = null,
    // #AARRGGBB or null
    @SerialName("Color") val color: String? = null,
    @SerialName("IsCompleted") val isCompleted: Boolean = false,
    // mobile only: CalendarContract.Events._ID this schedule was pushed to (for update/delete sync)
    @SerialName("MobileEventId") val eventId: Long? = null,
    // Google Calendar sync — same keys as the PC app, so a PC schedules.json keeps its links.
    @SerialName("GoogleEventId") val googleEventId: String? = null,
    @SerialName("GoogleSyncedHash") val googleSyncedHash: String? = null,
    @SerialName("GoogleSyncedPeriod") val googleSyncedPeriod: String? = null,
    @SerialName("GoogleEndMinutes") val googleEndMinutes: Int? = null,
    // PC keys: reminders already sent per channel (channel -> reminder key), and a Google-refused content hash.
    @SerialName("ReminderReceipts") val reminderReceipts: Map<String, String> = emptyMap(),
    @SerialName("GoogleRefusedHash") val googleRefusedHash: String? = null,
    // mobile only (the PC keeps them untouched): a note, the 중요 flag, and the series a repeated schedule belongs to.
    // A repeat is stored as separate schedules sharing one series id, so the PC and Google see ordinary schedules.
    @SerialName("MobileMemo") val memo: String? = null,
    @SerialName("MobileImportant") val important: Boolean = false,
    @SerialName("MobileSeriesId") val seriesId: String? = null,
    // Inclusive last day, shared with the desktop. A range remains one schedule and one Google event.
    @SerialName("EndPeriod") val endPeriod: String? = null,
) {
    @Transient val date: LocalDate? = parseDate(period)
    @Transient val endDate: LocalDate? = normalizeEndPeriod(period, endPeriod)?.let(::parseDate)
    val lastDate: LocalDate? get() = endDate ?: date
    val isMultiDay: Boolean get() = endDate != null
    val periodText: String get() = endDate?.let { "$period ~ $it" } ?: period
    val rangeLabel: String get() = endDate?.let { "${date!!.monthValue}.${date.dayOfMonth}~${it.monthValue}.${it.dayOfMonth}" }.orEmpty()

    fun covers(day: LocalDate): Boolean = date?.let { day >= it && day <= lastDate!! } == true
    fun overlaps(from: LocalDate, to: LocalDate): Boolean = date?.let { it <= to && lastDate!! >= from } == true
    fun movedTo(day: LocalDate): ScheduleItem = copy(
        period = day.toString(),
        endPeriod = endDate?.let { day.plusDays(ChronoUnit.DAYS.between(date, it)).toString() },
    )

    fun remainingDays(today: LocalDate = LocalDate.now()): Int? = date?.let {
        when {
            today < it -> ChronoUnit.DAYS.between(today, it).toInt()
            today <= lastDate!! -> 0
            else -> ChronoUnit.DAYS.between(today, lastDate).toInt()
        }
    }

    fun dDay(today: LocalDate = LocalDate.now()): String {
        if (isCompleted) return "완료"
        val diff = remainingDays(today) ?: return "날짜 확인"
        return when {
            diff == 0 -> "D-day"
            diff > 0 -> "D-$diff"
            else -> "D+${-diff}"
        }
    }

    companion object {
        const val MAX_RANGE_DAYS = 366

        fun parseDate(value: String?): LocalDate? = value?.let {
            runCatching { LocalDate.parse(it.trim(), DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()?.takeIf { d -> d.year in 1..9999 }
        }

        fun normalizeEndPeriod(period: String, endPeriod: String?): String? {
            val start = parseDate(period) ?: return null
            val end = parseDate(endPeriod)?.takeIf { it > start } ?: return null
            return minOf(end, start.plusDays(minOf(MAX_RANGE_DAYS.toLong(), ChronoUnit.DAYS.between(start, LocalDate.of(9999, 12, 31))))).toString()
        }
    }
}
