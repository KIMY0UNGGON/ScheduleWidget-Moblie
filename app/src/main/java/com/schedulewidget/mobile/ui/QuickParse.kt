package com.schedulewidget.mobile.ui

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Korean quick entry: dates and times written into a schedule title ("내일 오후 3시 회의", "10월 3일 병원",
 * "다음주 월요일 9시 반 스터디"). Pure (no Android), so it can be unit tested.
 */
internal object QuickParse {
    /** [title] with the recognised parts removed; [date] / [time] (HH:mm) are null when none was found. */
    data class Result(val title: String, val date: LocalDate?, val time: String?) {
        val found: Boolean get() = date != null || time != null
    }

    private const val WEEKDAYS = "월화수목금토일"
    // Particles that may follow a date or time word ("내일까지", "3시에").
    private const val TAIL = """(?:까지|부터|에는|에|은|는)?"""
    // Not glued to other Hangul (so "오늘의" or "내일모레" alone are not misread as a word in the middle of a title).
    private const val START = """(?<![가-힣0-9])"""
    private const val END = """(?![가-힣])"""

    private val relative = Regex("""$START(오늘|내일|모레|글피)$TAIL$END""")
    private val daysLater = Regex("""$START(\d{1,3})\s*일\s*(?:후|뒤)$TAIL$END""")
    private val weekDay = Regex("""$START(다다음\s*주|다음\s*주|담주|이번\s*주|금주)\s*([$WEEKDAYS])(?:요일)?$TAIL$END""")
    private val bareWeekDay = Regex("""$START([$WEEKDAYS])요일$TAIL$END""")
    private val slashDate = Regex("""(?<![\d/])(\d{1,2})/(\d{1,2})(?![\d/])$TAIL$END""")
    private val monthDay = Regex("""$START(\d{1,2})\s*월\s*(\d{1,2})\s*일$TAIL$END""")
    private val dayOnly = Regex("""$START(\d{1,2})\s*일$TAIL$END""")
    private val colonTime = Regex("""$START(?:(오전|오후|아침|저녁|밤|낮|새벽)\s*)?(\d{1,2}):(\d{2})(?!\d)$TAIL$END""")
    private val hourTime = Regex("""$START(?:(오전|오후|아침|저녁|밤|낮|새벽)\s*)?(\d{1,2})\s*시(?!간)(?:\s*(\d{1,2})\s*분|\s*(반))?$TAIL$END""")

    /** [dates] / [times] false: leave that part alone (the user already set the date / time field by hand). */
    fun parse(text: String, today: LocalDate, dates: Boolean = true, times: Boolean = true): Result {
        var rest = text
        var date: LocalDate? = null
        var time: String? = null

        // Removes the first match of [regex] when [read] accepts it; returns whether it did.
        fun take(regex: Regex, read: (MatchResult) -> Boolean): Boolean {
            val m = regex.find(rest) ?: return false
            if (!read(m)) return false
            rest = rest.substring(0, m.range.first) + " " + rest.substring(m.range.last + 1)
            return true
        }

        // Time first, so "3시" is never read as a day and "15:30" never as a date.
        if (times) take(colonTime) { m -> clock(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt())?.also { time = it } != null } ||
            take(hourTime) { m ->
                val minute = when {
                    m.groupValues[4].isNotEmpty() -> 30
                    m.groupValues[3].isNotEmpty() -> m.groupValues[3].toInt()
                    else -> 0
                }
                clock(m.groupValues[1], m.groupValues[2].toInt(), minute)?.also { time = it } != null
            }

        if (dates) take(relative) { m ->
            date = today.plusDays(listOf("오늘", "내일", "모레", "글피").indexOf(m.groupValues[1]).toLong()); true
        } ||
            take(daysLater) { m -> date = today.plusDays(m.groupValues[1].toLong()); true } ||
            take(weekDay) { m ->
                val week = m.groupValues[1].replace(" ", "")
                val weeks = when (week) { "다다음주" -> 2L; "다음주", "담주" -> 1L; else -> 0L }
                val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
                date = monday.plusWeeks(weeks).plusDays(WEEKDAYS.indexOf(m.groupValues[2]).toLong()); true
            } ||
            take(bareWeekDay) { m ->
                // The next such day after today (on a Monday, "월요일" means next Monday).
                val target = DayOfWeek.of(WEEKDAYS.indexOf(m.groupValues[1]) + 1)
                var d = today.plusDays(1)
                while (d.dayOfWeek != target) d = d.plusDays(1)
                date = d; true
            } ||
            take(monthDay) { m -> monthDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), today)?.also { date = it } != null } ||
            take(slashDate) { m -> monthDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), today)?.also { date = it } != null } ||
            take(dayOnly) { m ->
                // This month's day, or next month's when it has passed (or this month has no such day).
                // An invalid full date (e.g. 4월 31일) must not become a standalone "31일".
                if (monthDay.findAll(rest).any { m.range.first in it.range }) return@take false
                val day = m.groupValues[1].toInt()
                if (day !in 1..31) return@take false
                val thisMonth = today.withDayOfMonth(1)
                date = listOf(thisMonth, thisMonth.plusMonths(1), thisMonth.plusMonths(2))
                    .filter { day <= it.lengthOfMonth() }.map { it.withDayOfMonth(day) }.firstOrNull { !it.isBefore(today) }
                date != null
            }

        val title = rest.replace(Regex("""\s+"""), " ").trim()
        // Nothing left (the whole title was a date): keep the words as the title.
        return Result(title.ifEmpty { text.trim() }, date, time)
    }

    /** "→ 10월 1일 (목) 15:00" hint for what [parse] found, or null. */
    fun hint(result: Result): String? {
        if (!result.found) return null
        val parts = listOfNotNull(result.date?.format(HintDate), result.time)
        return "→ " + parts.joinToString(" ")
    }

    private val HintDate: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)

    private fun monthDate(month: Int, day: Int, today: LocalDate): LocalDate? {
        if (month !in 1..12 || day !in 1..31) return null
        for (year in listOf(today.year, today.year + 1)) {
            val d = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: continue
            if (!d.isBefore(today)) return d
        }
        return null
    }

    /**
     * HH:mm for [hour]:[minute] with a Korean time-of-day word. Without one, 1–7 o'clock is read as the afternoon
     * ("3시 회의" is 15:00), anything else as written (9시 → 09:00, 15시 → 15:00).
     */
    private fun clock(period: String, hour: Int, minute: Int): String? {
        if (hour !in 0..24 || minute !in 0..59) return null
        val h = when (period) {
            "오후", "저녁", "밤" -> if (hour in 1..11) hour + 12 else hour
            "낮" -> if (hour in 1..6) hour + 12 else hour
            "오전", "아침", "새벽" -> if (hour == 12) 0 else hour
            else -> if (hour in 1..7) hour + 12 else hour
        } % 24
        return String.format(Locale.ROOT, "%02d:%02d", h, minute)
    }
}
