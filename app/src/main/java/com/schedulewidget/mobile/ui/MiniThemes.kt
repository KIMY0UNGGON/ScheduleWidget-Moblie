package com.schedulewidget.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.schedulewidget.mobile.data.ScheduleItem

/** Mini calendar palette, ported from the desktop MiniWindow themes (Light/Dark/Blue/Pink/Modern). */
data class MiniTheme(
    val id: String,
    val label: String,
    val dark: Boolean,
    val paper: Color,
    val frame: Color,
    val header: Color,
    val ink: Color,
    val muted: Color,
    val hover: Color,
    val day: Color,
    val ring: Color,
    val today: Color,
    val todayBorder: Color,
    val holiday: Color,
    val weekday: Color,
    val saturday: Color,
    val canvas: Color,
    val surface: Color,
    val auxInk: Color,
    val auxMuted: Color,
    val hairline: Color,
    val accent: Color,
    val top: Color = header,
    val topInk: Color = ink,
)

object MiniThemes {
    val all = listOf(
        MiniTheme(
            id = "paper", label = "Light", dark = false,
            paper = hex("#FFFFFAF2"), frame = hex("#635277"), header = hex("#EDE3FA"), ink = hex("#514461"),
            muted = hex("#8A7B9C"), hover = hex("#DCCFF3"), day = hex("#F6F0EA"), ring = hex("#F5B5AF"),
            today = hex("#FFD5D0"), todayBorder = hex("#C77478"), holiday = hex("#BD656C"), weekday = hex("#84758F"),
            saturday = hex("#637FA5"), canvas = hex("#F5F5F7"), surface = hex("#FFFFFF"), auxInk = hex("#1D1D1F"),
            auxMuted = hex("#6E6E73"), hairline = hex("#E0E0E0"), accent = hex("#5B61D6"),
        ),
        MiniTheme(
            id = "dark", label = "Dark", dark = true,
            paper = hex("#FF1B2233"), frame = hex("#5C6F96"), header = hex("#27324B"), ink = hex("#E6EAF2"),
            muted = hex("#9AA6BC"), hover = hex("#34436A"), day = hex("#232C41"), ring = hex("#8295FF"),
            today = hex("#3A4A7A"), todayBorder = hex("#8295FF"), holiday = hex("#FF8A94"), weekday = hex("#AEB8CC"),
            saturday = hex("#8FB4FF"), canvas = hex("#1F2636"), surface = hex("#151D2C"), auxInk = hex("#F8FAFC"),
            auxMuted = hex("#C3CDDC"), hairline = hex("#2A3A53"), accent = hex("#8295FF"),
        ),
        MiniTheme(
            id = "blue", label = "Blue", dark = false,
            paper = hex("#FFF8FCFF"), frame = hex("#2F5E96"), header = hex("#DCEBFF"), ink = hex("#102A4A"),
            muted = hex("#466B92"), hover = hex("#C4DCFA"), day = hex("#EAF4FF"), ring = hex("#8FB8E7"),
            today = hex("#CFE3FF"), todayBorder = hex("#2477E6"), holiday = hex("#C4505B"), weekday = hex("#466B92"),
            saturday = hex("#2477E6"), canvas = hex("#EAF4FF"), surface = hex("#FFFFFF"), auxInk = hex("#102A4A"),
            auxMuted = hex("#466B92"), hairline = hex("#A8C8ED"), accent = hex("#2477E6"),
        ),
        MiniTheme(
            id = "pink", label = "Pink", dark = false,
            paper = hex("#FFFFF9FB"), frame = hex("#8E4A67"), header = hex("#FDE4EF"), ink = hex("#4A2035"),
            muted = hex("#A56A82"), hover = hex("#F8CFE0"), day = hex("#FFEDF4"), ring = hex("#F4B6CE"),
            today = hex("#FFD5E4"), todayBorder = hex("#D05A8A"), holiday = hex("#C8455F"), weekday = hex("#A56A82"),
            saturday = hex("#637FA5"), canvas = hex("#FFF4F8"), surface = hex("#FFFFFF"), auxInk = hex("#4A2035"),
            auxMuted = hex("#A56A82"), hairline = hex("#F4CBDC"), accent = hex("#D05A8A"),
        ),
        MiniTheme(
            id = "modern", label = "Modern", dark = false,
            paper = hex("#FFF7F7F7"), frame = hex("#111111"), header = hex("#F2F2F2"), ink = hex("#111111"),
            muted = hex("#6B6B6B"), hover = hex("#E4E4E4"), day = hex("#FFFFFF"), ring = hex("#FFFFFF"),
            today = hex("#EEEEEE"), todayBorder = hex("#111111"), holiday = hex("#D93025"), weekday = hex("#111111"),
            saturday = hex("#1A73E8"), canvas = hex("#F2F2F2"), surface = hex("#FFFFFF"), auxInk = hex("#111111"),
            auxMuted = hex("#6B6B6B"), hairline = hex("#D9D9D9"), accent = hex("#111111"),
            top = hex("#111111"), topInk = hex("#FFFFFF"),
        ),
    )

    /** Accepts mobile ids and desktop preset names ("Light" = paper). Unknown ids fall back to paper. */
    fun of(id: String?): MiniTheme =
        all.firstOrNull { it.id.equals(id, true) || it.label.equals(id, true) } ?: all.first()
}

/** Schedule block / card colors shared by the mini board and the TODO list. */
object ScheduleColors {
    /** Vivid colors for schedules without a custom card color (desktop BlockPalette). */
    private val blockPalette = listOf("#E8505B", "#2E86DE", "#F39C12", "#27AE60", "#8E44AD", "#E84393", "#16A5A5", "#D35400").map(::hex)

    /** Choices offered in the card color picker. */
    val choices = listOf(
        "#FFE8505B", "#FFF39C12", "#FFF6C945", "#FF27AE60", "#FF16A5A5", "#FF2E86DE",
        "#FF5B61D6", "#FF8E44AD", "#FFE84393", "#FF8D6E63", "#FF64748B", "#FF1D1D1F",
        "#FFFFE4E1", "#FFFFF4D6", "#FFE3F6E8", "#FFDDEBFF", "#FFEDE3FA", "#FFFFFFFF",
    )

    val DdayToday = hex("#3B82F6")
    val DdayFuture = hex("#64748B")
    val DdayPast = hex("#EF4444")

    fun parse(value: String?): Color? {
        val v = value?.trim()?.removePrefix("#") ?: return null
        if (!v.matches(Regex("[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8}"))) return null
        val argb = v.toLong(16).let { if (v.length == 6) it or 0xFF000000 else it }
        return Color(argb.toInt())
    }

    fun toHex(color: Color): String = "#%08X".format(color.toArgb())

    /** Black or white text, whichever reads better on [background] (desktop FeatureRules.ReadableText). */
    fun readableOn(background: Color): Color = if (background.luminance() > 0.179f) Color.Black else Color.White

    /** Stable per-schedule colors; blocks on the same day get different colors when possible. */
    fun blockColors(items: List<ScheduleItem>): Map<String, Color> {
        val result = LinkedHashMap<String, Color>()
        val used = HashSet<Color>()
        items.forEach { s -> parse(s.color)?.let { result[s.id] = it; used += it } }
        items.filter { parse(it.color) == null }.forEach { s ->
            val start = Math.floorMod(s.id.hashCode(), blockPalette.size)
            val color = (blockPalette.indices).map { blockPalette[(start + it) % blockPalette.size] }.firstOrNull { it !in used }
                ?: blockPalette[start]
            result[s.id] = color
            used += color
        }
        return result
    }

    fun ddayColor(item: ScheduleItem): Color {
        val diff = item.remainingDays()
        return when {
            item.isCompleted || diff == null -> DdayFuture
            diff == 0 -> DdayToday
            diff > 0 -> DdayFuture
            else -> DdayPast
        }
    }
}

private fun hex(value: String): Color = ScheduleColors.parse(value) ?: Color.Magenta
