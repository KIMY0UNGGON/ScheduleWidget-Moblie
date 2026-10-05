package com.schedulewidget.mobile.ui

import com.schedulewidget.mobile.data.ScheduleItem
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Repeating schedules (mobile only). A repeat is stored as separate ScheduleItems sharing one seriesId, so the PC app and
 * Google Calendar see ordinary schedules; the series only matters for editing / deleting them together here.
 */
internal enum class Repeat(val label: String) {
    NONE("하루"), CONTINUOUS("연속 날짜"), DAILY("매일"), WEEKLY("매주"), MONTHLY("매월"), YEARLY("매년");

    /** Default 종료일 for a repeat starting on [start]: 3 months for daily/weekly, a year for monthly/yearly. */
    fun defaultUntil(start: LocalDate): LocalDate = when (this) {
        CONTINUOUS -> start
        DAILY, WEEKLY -> start.plusMonths(3)
        else -> start.plusYears(1)
    }

    /** The [index]th date from [start]. Monthly on the 29th–31st falls on the month's last day where it is missing. */
    fun dateAt(start: LocalDate, index: Long): LocalDate = when (this) {
        NONE -> start
        CONTINUOUS, DAILY -> start.plusDays(index)
        WEEKLY -> start.plusWeeks(index)
        // Always counted from the start (plusMonths clamps), so Jan 31 → Feb 28 → Mar 31, not Mar 28.
        MONTHLY -> start.plusMonths(index)
        YEARLY -> start.plusYears(index)
    }
}

/** Which schedules of a series an edit or a delete touches. */
internal enum class SeriesScope { THIS, FOLLOWING, ALL }

internal object ScheduleSeries {
    const val MAX_ITEMS = 366

    fun validRange(start: LocalDate, until: LocalDate): Boolean =
        until >= start && ChronoUnit.DAYS.between(start, until) <= ScheduleItem.MAX_RANGE_DAYS

    /** Dates from [start] to [until] (inclusive) for [repeat]; at most [MAX_ITEMS]. */
    fun dates(start: LocalDate, repeat: Repeat, until: LocalDate): List<LocalDate> {
        if (repeat == Repeat.NONE) return listOf(start)
        if (repeat == Repeat.CONTINUOUS) {
            require(validRange(start, until)) { "종료 날짜를 확인해 주세요." }
            return listOf(start)
        }
        val out = ArrayList<LocalDate>()
        var i = 0L
        while (out.size < MAX_ITEMS) {
            val d = repeat.dateAt(start, i++)
            if (d.isAfter(until)) break
            out += d
        }
        return out.ifEmpty { listOf(start) }
    }

    /**
     * [first] (already dated on the start day) plus one copy per further date, all with a new shared seriesId.
     * The copies get fresh ids and no calendar links.
     */
    fun expand(first: ScheduleItem, repeat: Repeat, until: LocalDate): List<ScheduleItem> {
        val start = first.date ?: return listOf(first)
        if (repeat == Repeat.CONTINUOUS) {
            require(validRange(start, until)) { "종료 날짜를 확인해 주세요." }
            return listOf(first.copy(endPeriod = ScheduleItem.normalizeEndPeriod(first.period, until.toString())))
        }
        val dates = dates(start, repeat, until)
        if (dates.size < 2) return listOf(first)
        val series = UUID.randomUUID().toString()
        return dates.mapIndexed { i, d ->
            if (i == 0) first.copy(seriesId = series)
            else ScheduleItem(
                title = first.title, period = d.toString(), time = first.time, color = first.color,
                memo = first.memo, important = first.important, seriesId = series,
            )
        }
    }

    /** Members of [item]'s series that [scope] covers (just [item] when it is not in a series). */
    fun members(all: List<ScheduleItem>, item: ScheduleItem, scope: SeriesScope): List<ScheduleItem> {
        val series = item.seriesId ?: return listOf(item)
        return when (scope) {
            SeriesScope.THIS -> listOf(item)
            SeriesScope.ALL -> all.filter { it.seriesId == series }
            SeriesScope.FOLLOWING -> all.filter {
                it.seriesId == series && (it.id == item.id || (it.date ?: LocalDate.MAX) >= (item.date ?: LocalDate.MIN))
            }
        }
    }

    /**
     * Carries what changed from [before] to [after] (title, time, memo, color, importance — never the date) onto the
     * other members [scope] covers. Returns (before, after) pairs for every schedule to save, [after] first.
     */
    fun propagate(
        all: List<ScheduleItem>, before: ScheduleItem, after: ScheduleItem, scope: SeriesScope,
    ): List<Pair<ScheduleItem, ScheduleItem>> {
        val others = if (scope == SeriesScope.THIS) emptyList() else members(all, before, scope).filter { it.id != before.id }
        return listOf(before to after) + others.map { o ->
            o to o.copy(
                title = if (after.title != before.title) after.title else o.title,
                time = if (after.time != before.time) after.time else o.time,
                memo = if (after.memo != before.memo) after.memo else o.memo,
                color = if (after.color != before.color) after.color else o.color,
                important = if (after.important != before.important) after.important else o.important,
            )
        }.filter { (o, n) -> o != n }
    }
}
