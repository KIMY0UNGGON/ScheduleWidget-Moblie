package com.schedulewidget.mobile.reminders

import com.schedulewidget.mobile.data.ReminderSettings
import com.schedulewidget.mobile.data.ScheduleItem
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * The PC's reminder rules (Models/FeatureSettings.cs FeatureRules.IsReminderDue / ReminderKey / ReminderMessage),
 * ported one to one so both apps agree on when a reminder is due and on the receipt keys they store.
 */
object ReminderRules {
    /** Receipt channel of the phone in ScheduleItem.reminderReceipts (the PC uses Desktop / Telegram / Kakao). */
    const val CHANNEL = "Mobile"

    private val timePattern = Regex("""([0-9]{1,2}):([0-9]{2})""")

    /** FeatureRules.TryScheduleTime: "H:mm" / "HH:mm" normalized to "HH:mm", or null when it is not a valid time. */
    fun scheduleTime(value: String?): String? {
        val m = timePattern.matchEntire((value ?: "").trim()) ?: return null
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        if (hour > 23 || minute > 59) return null
        return "%02d:%02d".format(Locale.ROOT, hour, minute)
    }

    /**
     * When the reminder becomes due: DaysBefore days before the date, at the schedule's own time if it has one,
     * otherwise at Hour:Minute. Null when reminders are off, the schedule is completed or its date is unreadable.
     */
    fun startOf(item: ScheduleItem, settings: ReminderSettings): LocalDateTime? {
        if (!settings.enabled || item.isCompleted) return null
        val date = item.date ?: return null
        val days = settings.daysBefore.coerceIn(0, 30)
        var hour = settings.hour.coerceIn(0, 23)
        var minute = settings.minute.coerceIn(0, 59)
        scheduleTime(item.time)?.let { t ->
            hour = t.substring(0, 2).toInt()
            minute = t.substring(3, 5).toInt()
        }
        return date.minusDays(days.toLong()).atTime(hour, minute)
    }

    /** Due from its start until the end of the schedule's date, so a reminder missed while off still fires that day. */
    fun isDue(item: ScheduleItem, settings: ReminderSettings, now: LocalDateTime): Boolean {
        val start = startOf(item, settings) ?: return false
        val date: LocalDate = item.date ?: return false
        return !now.isBefore(start) && !now.toLocalDate().isAfter(date)
    }

    /** Same text as the PC key (raw DaysBefore/Hour/Minute), so an edited date/time or setting fires again. */
    fun key(item: ScheduleItem, settings: ReminderSettings): String =
        item.period + "|" + settings.daysBefore + "|" +
            (scheduleTime(item.time)?.let { "at$it" } ?: "${settings.hour}:${settings.minute}")

    /** True when the phone already delivered this exact reminder. */
    fun delivered(item: ScheduleItem, settings: ReminderSettings): Boolean =
        item.reminderReceipts[CHANNEL] == key(item, settings)

    /** Notification title: the PC message's first line without its "[할 일 알림]" prefix. */
    fun title(item: ScheduleItem): String = item.title.take(150).ifBlank { "할 일" }

    /** The PC message's second line, e.g. "마감: 2026-10-02 14:00 (D-1)". */
    fun message(item: ScheduleItem, today: LocalDate = LocalDate.now()): String =
        "마감: " + item.periodText + (scheduleTime(item.time)?.let { " $it" } ?: "") + " (" + item.dDay(today) + ")"

    /** One-line summary of the settings for the settings card. */
    fun describe(settings: ReminderSettings): String {
        val day = if (settings.daysBefore <= 0) "마감 당일" else "마감 ${settings.daysBefore}일 전"
        return "$day ${"%02d:%02d".format(Locale.ROOT, settings.hour, settings.minute)}에 알려줘요"
    }
}
