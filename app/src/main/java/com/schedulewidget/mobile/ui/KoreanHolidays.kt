package com.schedulewidget.mobile.ui

import android.icu.util.ChineseCalendar
import android.icu.util.TimeZone
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Korean public holidays (port of the desktop Services/KoreanHolidays.cs): fixed solar holidays, lunar holidays
 * (설날·부처님오신날·추석), substitute holidays, plus a table of elections / one-off holidays rules cannot predict.
 */
object KoreanHolidays {
    private val cache = ConcurrentHashMap<Int, Map<LocalDate, String>>()

    private val special = mapOf(
        LocalDate.of(2020, 4, 15) to "국회의원 선거",
        LocalDate.of(2020, 8, 17) to "임시공휴일",
        LocalDate.of(2022, 3, 9) to "대통령 선거",
        LocalDate.of(2022, 6, 1) to "지방선거",
        LocalDate.of(2023, 10, 2) to "임시공휴일",
        LocalDate.of(2024, 4, 10) to "국회의원 선거",
        LocalDate.of(2024, 10, 1) to "국군의 날",
        LocalDate.of(2025, 1, 27) to "임시공휴일",
        LocalDate.of(2025, 6, 3) to "대통령 선거",
        LocalDate.of(2026, 6, 3) to "지방선거",
    )

    // 설날, 부처님오신날, 추석 as MMdd, from .NET KoreanLunisolarCalendar (the Korean calendar differs from the
    // Chinese one in a few years, e.g. 2027). Years outside the table fall back to ICU's ChineseCalendar.
    private val lunarTable: Map<Int, IntArray> = mapOf(
        2000 to intArrayOf(205, 511, 912), 2001 to intArrayOf(124, 501, 1001), 2002 to intArrayOf(212, 519, 921),
        2003 to intArrayOf(201, 508, 911), 2004 to intArrayOf(122, 526, 928), 2005 to intArrayOf(209, 515, 918),
        2006 to intArrayOf(129, 505, 1006), 2007 to intArrayOf(218, 524, 925), 2008 to intArrayOf(207, 512, 914),
        2009 to intArrayOf(126, 502, 1003), 2010 to intArrayOf(214, 521, 922), 2011 to intArrayOf(203, 510, 912),
        2012 to intArrayOf(123, 528, 930), 2013 to intArrayOf(210, 517, 919), 2014 to intArrayOf(131, 506, 908),
        2015 to intArrayOf(219, 525, 927), 2016 to intArrayOf(208, 514, 915), 2017 to intArrayOf(128, 503, 1004),
        2018 to intArrayOf(216, 522, 924), 2019 to intArrayOf(205, 512, 913), 2020 to intArrayOf(125, 430, 1001),
        2021 to intArrayOf(212, 519, 921), 2022 to intArrayOf(201, 508, 910), 2023 to intArrayOf(122, 527, 929),
        2024 to intArrayOf(210, 515, 917), 2025 to intArrayOf(129, 505, 1006), 2026 to intArrayOf(217, 524, 925),
        2027 to intArrayOf(207, 513, 915), 2028 to intArrayOf(127, 502, 1003), 2029 to intArrayOf(213, 520, 922),
        2030 to intArrayOf(203, 509, 912), 2031 to intArrayOf(123, 528, 1001), 2032 to intArrayOf(211, 516, 919),
        2033 to intArrayOf(131, 506, 908), 2034 to intArrayOf(219, 525, 927), 2035 to intArrayOf(208, 515, 916),
        2036 to intArrayOf(128, 503, 1004), 2037 to intArrayOf(215, 522, 924), 2038 to intArrayOf(204, 511, 913),
        2039 to intArrayOf(124, 430, 1002), 2040 to intArrayOf(212, 518, 921), 2041 to intArrayOf(201, 507, 910),
        2042 to intArrayOf(122, 526, 928), 2043 to intArrayOf(210, 516, 917), 2044 to intArrayOf(130, 505, 1005),
        2045 to intArrayOf(217, 524, 925), 2046 to intArrayOf(206, 513, 915), 2047 to intArrayOf(126, 502, 1004),
        2048 to intArrayOf(214, 520, 922), 2049 to intArrayOf(202, 509, 911), 2050 to intArrayOf(123, 528, 930),
    )

    /** Holiday name for [date], or null. */
    fun nameOf(date: LocalDate): String? = cache.getOrPut(date.year) { build(date.year) }[date]

    private fun build(year: Int): Map<LocalDate, String> {
        val names = LinkedHashMap<LocalDate, MutableList<String>>()
        fun add(day: LocalDate?, name: String) {
            if (day == null || day.year != year) return
            val list = names.getOrPut(day) { mutableListOf() }
            if (name !in list) list += name
        }
        fun solar(month: Int, day: Int) = LocalDate.of(year, month, day)

        add(solar(1, 1), "신정")
        add(solar(3, 1), "삼일절")
        add(solar(5, 5), "어린이날")
        add(solar(6, 6), "현충일")
        if (year >= 2026) add(solar(7, 17), "제헌절")
        add(solar(8, 15), "광복절")
        add(solar(10, 3), "개천절")
        add(solar(10, 9), "한글날")
        add(solar(12, 25), "성탄절")

        val seollal = lunar(year, 0)
        val buddha = lunar(year, 1)
        val chuseok = lunar(year, 2)
        val seollalDays = seollal?.let { listOf(it.minusDays(1), it, it.plusDays(1)) }.orEmpty()
        val chuseokDays = chuseok?.let { listOf(it.minusDays(1), it, it.plusDays(1)) }.orEmpty()
        seollalDays.forEach { add(it, if (it == seollal) "설날" else "설날 연휴") }
        add(buddha, "부처님오신날")
        chuseokDays.forEach { add(it, if (it == chuseok) "추석" else "추석 연휴") }
        special.filterKeys { it.year == year }.forEach { (d, n) -> add(d, n) }

        val holidays = names.keys.toHashSet()
        val substitutes = mutableListOf<Pair<LocalDate, String>>()
        fun weekend(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
        fun substitute(after: LocalDate, reason: String) {
            var day = after.plusDays(1)
            while (weekend(day) || day in holidays) day = day.plusDays(1)
            holidays += day
            substitutes += day to "대체공휴일($reason)"
        }
        fun overlaps(day: LocalDate, own: String) =
            names[day]?.any { it != own && !it.startsWith(own.substring(0, 2)) } == true

        val checks = mutableListOf<Pair<LocalDate, () -> Unit>>()
        if (year >= 2014) {
            if (seollalDays.any { it.dayOfWeek == DayOfWeek.SUNDAY || overlaps(it, "설날") })
                checks += seollalDays.last() to { substitute(seollalDays.last(), "설날") }
            if (chuseokDays.any { it.dayOfWeek == DayOfWeek.SUNDAY || overlaps(it, "추석") })
                checks += chuseokDays.last() to { substitute(chuseokDays.last(), "추석") }
            val children = solar(5, 5)
            if (weekend(children) || overlaps(children, "어린이날")) checks += children to { substitute(children, "어린이날") }
        }
        fun single(day: LocalDate?, name: String, since: LocalDate) {
            if (day == null || day < since || day !in names) return
            if (weekend(day) || overlaps(day, name)) checks += day to { substitute(day, name) }
        }
        if (year >= 2021) {
            val national = LocalDate.of(2021, 8, 4)
            single(solar(3, 1), "삼일절", national)
            single(solar(8, 15), "광복절", national)
            single(solar(10, 3), "개천절", national)
            single(solar(10, 9), "한글날", national)
            if (year >= 2026) single(solar(7, 17), "제헌절", national)
            val extended = LocalDate.of(2023, 5, 4)
            single(buddha, "부처님오신날", extended)
            single(solar(12, 25), "성탄절", extended)
        }
        // Holidays falling on the same day produce a single substitute.
        checks.groupBy { it.first }.toSortedMap().values.forEach { it.first().second() }

        val result = names.mapValues { it.value.joinToString("·") }.toMutableMap()
        substitutes.filter { it.first.year == year }.forEach { (d, n) ->
            result[d] = result[d]?.let { "$it·$n" } ?: n
        }
        return result
    }

    /** index 0 = 설날 (1/1), 1 = 부처님오신날 (4/8), 2 = 추석 (8/15). */
    private fun lunar(year: Int, index: Int): LocalDate? {
        lunarTable[year]?.let { val mmdd = it[index]; return LocalDate.of(year, mmdd / 100, mmdd % 100) }
        val (month, day) = when (index) { 0 -> 1 to 1; 1 -> 4 to 8; else -> 8 to 15 }
        return runCatching {
            val cal = ChineseCalendar(TimeZone.getTimeZone("Asia/Seoul"))
            cal.clear()
            cal.set(ChineseCalendar.EXTENDED_YEAR, year + 2637)
            cal.set(ChineseCalendar.MONTH, month - 1)
            cal.set(ChineseCalendar.IS_LEAP_MONTH, 0)
            cal.set(ChineseCalendar.DAY_OF_MONTH, day)
            Instant.ofEpochMilli(cal.timeInMillis).atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
        }.getOrNull()
    }
}
