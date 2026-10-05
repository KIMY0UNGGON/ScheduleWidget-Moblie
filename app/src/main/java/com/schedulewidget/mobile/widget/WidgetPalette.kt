package com.schedulewidget.mobile.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.glance.unit.ColorProvider
import com.schedulewidget.mobile.MainActivity
import com.schedulewidget.mobile.ui.Route
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay

/** Small self-contained palette so the widgets don't depend on the in-app theme code. */
data class WidgetPalette(
    val background: Color,
    val surface: Color,
    val text: Color,
    val subText: Color,
    val accent: Color,
    val onAccent: Color,
    val saturday: Color,
    val sunday: Color,
    val todayBg: Color,
    val blocks: List<Color>,
) {
    companion object {
        private val pastel = listOf(
            Color(0xFF5B61D6), Color(0xFF2E9E8F), Color(0xFFE0864A), Color(0xFFD1557A),
            Color(0xFF7A5BD6), Color(0xFF3F8FD8), Color(0xFF8A9A3B), Color(0xFFB0643A),
        )

        private val paper = WidgetPalette(
            background = Color(0xFFFBF6EC), surface = Color(0xFFFFFDF8), text = Color(0xFF3A3530),
            subText = Color(0xFF8A8178), accent = Color(0xFF5B61D6), onAccent = Color.White,
            saturday = Color(0xFF3F6FD8), sunday = Color(0xFFD9534F), todayBg = Color(0xFFE9E8FA), blocks = pastel,
        )

        fun of(themeId: String?): WidgetPalette = when (themeId?.lowercase()) {
            "light" -> paper.copy(
                background = Color(0xFFFFFFFF), surface = Color(0xFFF4F5F7), text = Color(0xFF202124),
                subText = Color(0xFF80868B), todayBg = Color(0xFFE8EAFD),
            )
            "dark" -> WidgetPalette(
                background = Color(0xFF1F2126), surface = Color(0xFF2A2D34), text = Color(0xFFECECEC),
                subText = Color(0xFF9AA0A6), accent = Color(0xFF8C92FF), onAccent = Color(0xFF15161A),
                saturday = Color(0xFF7EA6FF), sunday = Color(0xFFFF7B7B), todayBg = Color(0xFF33375A),
                blocks = pastel.map { it.copy(alpha = 0.85f) },
            )
            "blue" -> paper.copy(
                background = Color(0xFFEAF3FF), surface = Color(0xFFF7FBFF), text = Color(0xFF1D2B45),
                subText = Color(0xFF6A7C99), accent = Color(0xFF2F6FDB), todayBg = Color(0xFFD5E6FF),
            )
            "pink" -> paper.copy(
                background = Color(0xFFFFF0F5), surface = Color(0xFFFFF8FB), text = Color(0xFF4A2835),
                subText = Color(0xFF9A7584), accent = Color(0xFFE0588C), todayBg = Color(0xFFFFDDE9),
            )
            "modern" -> WidgetPalette(
                background = Color.White, surface = Color.White, text = Color.Black, subText = Color(0xFF555555),
                accent = Color.Black, onAccent = Color.White, saturday = Color(0xFF1E5BD8), sunday = Color(0xFFD62C2C),
                todayBg = Color(0xFFEDEDED), blocks = listOf(Color(0xFF222222), Color(0xFF555555), Color(0xFF888888)),
            )
            else -> paper
        }
    }

    fun dayColor(date: LocalDate): Color = when {
        date.dayOfWeek == DayOfWeek.SUNDAY || WidgetHolidays.name(date) != null -> sunday
        date.dayOfWeek == DayOfWeek.SATURDAY -> saturday
        else -> text
    }
}

fun Color.provider(): ColorProvider = ColorProvider(this)

fun contentColorFor(bg: Color): Color = if (bg.luminance() > 0.55f) Color(0xFF1B1B1B) else Color.White

/** "#AARRGGBB" / "#RRGGBB" -> Color, null on failure. */
fun parseHexColor(hex: String?): Color? =
    hex?.takeIf { it.isNotBlank() }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }

/**
 * Holiday lookup for the calendar widget. Only fixed solar holidays built in; the app can plug in the full
 * Korean holiday table (lunar/substitute/election days) via [provider].
 */
object WidgetHolidays {
    @Volatile var provider: ((LocalDate) -> String?)? = null

    private val solar = mapOf(
        MonthDay.of(1, 1) to "신정", MonthDay.of(3, 1) to "삼일절", MonthDay.of(5, 5) to "어린이날",
        MonthDay.of(6, 6) to "현충일", MonthDay.of(8, 15) to "광복절", MonthDay.of(10, 3) to "개천절",
        MonthDay.of(10, 9) to "한글날", MonthDay.of(12, 25) to "성탄절",
    )

    fun name(date: LocalDate): String? = provider?.invoke(date) ?: solar[MonthDay.from(date)]
}

internal fun routeIntent(context: Context, route: Route): Intent =
    Intent(context, MainActivity::class.java)
        .setData(Uri.parse("schedulewidget://route/${route.name}"))
        .putExtra(MainActivity.EXTRA_ROUTE, route.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

internal val koreanWeekdays = arrayOf("월", "화", "수", "목", "금", "토", "일")
internal fun LocalDate.weekdayKo(): String = koreanWeekdays[dayOfWeek.value - 1]
