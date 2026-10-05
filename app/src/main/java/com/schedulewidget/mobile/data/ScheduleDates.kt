package com.schedulewidget.mobile.data

import java.time.LocalDate

/** Index only the visible days, including the middle of ranges without expanding stored schedules. */
internal object ScheduleDates {
    val dayOrder = compareBy<ScheduleItem>({ it.isCompleted }, { !it.important }, { it.time ?: "99:99" }, { it.title })

    fun byDay(items: List<ScheduleItem>, from: LocalDate, to: LocalDate): Map<LocalDate, List<ScheduleItem>> {
        val days = linkedMapOf<LocalDate, MutableList<ScheduleItem>>()
        for (item in items) {
            if (!item.overlaps(from, to)) continue
            var day = maxOf(from, item.date!!)
            val last = minOf(to, item.lastDate!!)
            while (true) {
                days.getOrPut(day) { mutableListOf() }.add(item)
                if (day == last) break
                day = day.plusDays(1)
            }
        }
        return days.mapValues { (_, list) -> list.sortedWith(dayOrder) }
    }

    data class Upcoming(val items: List<ScheduleItem>, val more: Int)

    /** Desktop › preview: 12 unfinished schedules within 28 days, or the nearest later date. */
    fun upcoming(items: List<ScheduleItem>, from: LocalDate, expanded: Boolean = false): Upcoming {
        val future = items.filter { !it.isCompleted && it.date?.let { d -> d >= from } == true }
            .sortedWith(compareBy<ScheduleItem> { it.date }.then(dayOrder))
        if (expanded) return Upcoming(future, 0)
        val window = future.filter { it.date!!.toEpochDay() - from.toEpochDay() < 28 }
        val shown = (window.ifEmpty { future.takeWhile { it.date == future.firstOrNull()?.date } }).take(12)
        return Upcoming(shown, future.size - shown.size)
    }
}
