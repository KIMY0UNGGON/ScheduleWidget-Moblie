package com.schedulewidget.mobile.pet

import com.schedulewidget.mobile.ui.QuickAddActivity
import com.schedulewidget.mobile.ui.LocalCalendarTextScale
import com.schedulewidget.mobile.ui.LocalCalendarToday
import com.schedulewidget.mobile.ui.calSp
import com.schedulewidget.mobile.ui.calendarTextFactor
import com.schedulewidget.mobile.ui.rememberCalendarToday
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.ExperimentalComposeUiApi
import android.view.MotionEvent
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schedulewidget.mobile.calendar.DeviceCalendar
import com.schedulewidget.mobile.calendar.DeviceEvent
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.data.ScheduleDates
import com.schedulewidget.mobile.ui.KoreanHolidays
import com.schedulewidget.mobile.ui.MiniThemes
import com.schedulewidget.mobile.ui.Route
import com.schedulewidget.mobile.ui.ScheduleColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Panel width that shows all seven 104dp day columns: 7×104 + 6×6 gaps + 2×8 row padding + 2×10 outer padding. */
const val OVERLAY_CALENDAR_FULL_WIDTH_DP = 7 * 104 + 6 * 6 + 2 * 8 + 2 * 10

/** Compact 7-day agenda shown next to the floating pet on double-tap. */
@Composable
fun OverlayCalendar(
    onClose: () -> Unit,
    onOpenApp: (Route) -> Unit,
    onAdd: (LocalDate) -> Unit,
    /** Height of the day columns in dp (the panel's resizable part). */
    dayHeight: Int = 260,
    /** Drag on a corner or side grip, in raw screen px; the service resizes (and for left/top also moves) the window. */
    onResize: ((edges: ResizeEdges, dx: Float, dy: Float) -> Unit)? = null,
    onResizeEnd: () -> Unit = {},
) {
    val context = LocalContext.current
    val data by remember { Repository.get(context) }.data.collectAsState()
    val theme = MiniThemes.of(data.miniTheme)
    val today = rememberCalendarToday()
    val days = remember(today) { (0L until 7L).map { today.plusDays(it) } }
    val events by produceState(emptyList<DeviceEvent>(), today, data.calendar, data.schedules, data.googleCalendar) {
        value = withContext(Dispatchers.IO) {
            runCatching { DeviceCalendar.events(context, today, today.plusDays(7)) }.getOrDefault(emptyList())
        }
    }
    val scheduleByDay = remember(data.schedules, today) { ScheduleDates.byDay(data.schedules, today, today.plusDays(6)) }
    val eventsByDay = remember(events) { events.groupBy { it.date } }
    // Same paper-calendar card as the in-app board (ui/CalendarBoard.kt): 18dp corners, 2dp frame.
    val shape = RoundedCornerShape(18.dp)

    Box {
    Column(
        Modifier.padding(10.dp).shadow(8.dp, shape).clip(shape).background(theme.paper)
            .border(2.dp, theme.frame, shape),
    ) {
        Row(
            // Extra top padding keeps the header text clear of the binding rings that hang over the top edge.
            Modifier.fillMaxWidth().background(theme.top).padding(start = 14.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${today.monthValue}.${today.dayOfMonth} — ${days.last().monthValue}.${days.last().dayOfMonth}",
                color = theme.topInk, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            )
            // Straight to the character settings (size, animation, floating on/off, Drive sync).
            TextButton(onClick = { onOpenApp(Route.Pets) }) { Text("⚙ 펫", color = theme.topInk, fontSize = 13.sp) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onAdd(today) }) { Text("+ 일정", color = theme.topInk, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            TextButton(onClick = { onOpenApp(Route.Mini) }) { Text("앱 열기", color = theme.topInk, fontSize = 13.sp) }
            TextButton(onClick = onClose) { Text("✕", color = theme.topInk, fontSize = 15.sp) }
        }
        // The week runs sideways (today, tomorrow, …); swipe left/right for the rest of the 7 days.
        // Text follows 설정 > 달력 글자 크기, like the in-app board.
        CompositionLocalProvider(
            LocalCalendarTextScale provides data.calendarTextFactor,
            LocalCalendarToday provides today,
        ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (day in days) {
                val holiday = KoreanHolidays.nameOf(day)
                val tasks = scheduleByDay[day].orEmpty()
                val dayEvents = eventsByDay[day].orEmpty()
                // Same per-day colour assignment as the mini calendar board.
                val blocks = ScheduleColors.blockColors(tasks)
                val dayColor = when {
                    holiday != null || day.dayOfWeek == DayOfWeek.SUNDAY -> theme.holiday
                    day.dayOfWeek == DayOfWeek.SATURDAY -> theme.saturday
                    else -> theme.weekday
                }
                Column(
                    Modifier.width(104.dp).height(dayHeight.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (day == today) theme.today else theme.day)
                        .clickable { onAdd(day) }
                        .padding(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${day.dayOfMonth}", color = dayColor, fontWeight = FontWeight.Bold, fontSize = calSp(17f))
                        Spacer(Modifier.width(4.dp))
                        Text(day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN), color = dayColor, fontSize = calSp(11f),
                            modifier = Modifier.padding(bottom = 2.dp))
                    }
                    holiday?.let { Text(it, color = dayColor, fontSize = calSp(10f), lineHeight = calSp(12f), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    Column(
                        Modifier.padding(top = 4.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        if (tasks.isEmpty() && dayEvents.isEmpty()) Text("일정 없음", color = theme.muted, fontSize = calSp(11f))
                        for (t in tasks) {
                            val bg = blocks[t.id] ?: theme.accent
                            Chip(
                                text = (if (t.important) "★ " else "") + (if (t.rangeLabel.isEmpty()) "" else "${t.rangeLabel} ") +
                                    (t.time?.let { "$it " } ?: "") + t.title, background = bg,
                                ink = ScheduleColors.readableOn(bg), done = t.isCompleted,
                                trailing = t.dDay(today).takeIf { data.miniBlockDDayVisible },
                                onClick = { context.startActivity(QuickAddActivity.editIntent(context, t.id)) },
                            )
                        }
                        for (e in dayEvents) {
                            Chip(
                                text = (e.time?.let { "$it " } ?: "") + e.title, done = e.done,
                                background = theme.surface, ink = theme.ink, border = e.color?.let { Color(it or 0xFF000000.toInt()) } ?: theme.accent,
                                onClick = { DeviceCalendar.open(context, e.date, e.id) },
                            )
                        }
                    }
                }
            }
        }
        }
        Spacer(Modifier.padding(bottom = 4.dp))
    }
    // Two binding rings on the top edge, like the desktop paper calendar and the in-app board.
    // matchParentSize so they never grow the window; no pointer input, so taps pass through to the header.
    // The card starts 10dp down (outer padding), so rings at 4dp stick out 6dp above its top border.
    Box(Modifier.matchParentSize().padding(start = 10.dp, end = 10.dp, top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            repeat(2) {
                Box(
                    Modifier.size(width = 10.dp, height = 18.dp).clip(RoundedCornerShape(5.dp)).background(theme.ring)
                        .border(1.5.dp, theme.frame, RoundedCornerShape(5.dp)),
                )
            }
        }
    }
    // matchParentSize: the grips take the card's size and never stretch the wrap-content window themselves.
    if (onResize != null) Box(Modifier.matchParentSize()) {
        // Four corners, plus the left, right and bottom sides (the top side is the header with its buttons).
        val corner = 34.dp
        ResizeGrip(ResizeEdges(left = true, top = true), theme.muted, onResize, onResizeEnd, Modifier.align(Alignment.TopStart).size(corner))
        ResizeGrip(ResizeEdges(right = true, top = true), theme.muted, onResize, onResizeEnd, Modifier.align(Alignment.TopEnd).size(corner))
        ResizeGrip(ResizeEdges(left = true, bottom = true), theme.muted, onResize, onResizeEnd, Modifier.align(Alignment.BottomStart).size(corner))
        ResizeGrip(ResizeEdges(right = true, bottom = true), theme.muted, onResize, onResizeEnd, Modifier.align(Alignment.BottomEnd).size(corner))
        ResizeGrip(ResizeEdges(left = true), null, onResize, onResizeEnd, Modifier.align(Alignment.CenterStart).width(16.dp).fillMaxHeight(0.6f))
        ResizeGrip(ResizeEdges(right = true), null, onResize, onResizeEnd, Modifier.align(Alignment.CenterEnd).width(16.dp).fillMaxHeight(0.6f))
        ResizeGrip(ResizeEdges(bottom = true), null, onResize, onResizeEnd, Modifier.align(Alignment.BottomCenter).height(16.dp).fillMaxWidth(0.6f))
    }
    }
}

/** Which sides a grip moves: right/bottom grow the panel away from the top-left; left/top keep the opposite side still. */
data class ResizeEdges(val left: Boolean = false, val top: Boolean = false, val right: Boolean = false, val bottom: Boolean = false)

/**
 * A resize handle. Uses raw screen coordinates because the window itself moves/grows under the finger.
 * Corner grips draw a small L mark ([tint]); side grips are invisible strips.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ResizeGrip(
    edges: ResizeEdges, tint: Color?, onResize: (ResizeEdges, Float, Float) -> Unit, onEnd: () -> Unit, modifier: Modifier,
) {
    var lastX by remember { mutableFloatStateOf(0f) }
    var lastY by remember { mutableFloatStateOf(0f) }
    Canvas(
        modifier.pointerInteropFilter { e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.rawX; lastY = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    onResize(edges, e.rawX - lastX, e.rawY - lastY)
                    lastX = e.rawX; lastY = e.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onEnd()
            }
            true
        },
    ) {
        if (tint == null) return@Canvas
        val inset = 7.dp.toPx()
        val len = 11.dp.toPx()
        val stroke = 3.dp.toPx()
        val x = if (edges.left) inset else size.width - inset
        val y = if (edges.top) inset else size.height - inset
        val dx = if (edges.left) len else -len
        val dy = if (edges.top) len else -len
        drawLine(tint, Offset(x, y), Offset(x + dx, y), stroke, StrokeCap.Round)
        drawLine(tint, Offset(x, y), Offset(x, y + dy), stroke, StrokeCap.Round)
    }
}

@Composable
private fun Chip(
    text: String, background: Color, ink: Color, done: Boolean = false, trailing: String? = null, border: Color? = null,
    onClick: () -> Unit = {},
) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(background.copy(alpha = if (done) 0.45f else 1f))
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            text, color = ink, fontSize = calSp(11f), lineHeight = calSp(13f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            textDecoration = if (done) TextDecoration.LineThrough else null,
        )
        if (trailing != null && !done) Text(trailing, color = ink, fontSize = calSp(10f), fontWeight = FontWeight.Bold)
    }
}
