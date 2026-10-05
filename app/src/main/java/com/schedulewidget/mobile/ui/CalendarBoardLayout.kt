package com.schedulewidget.mobile.ui

import androidx.compose.runtime.key
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.LazyColumn
import com.schedulewidget.mobile.data.AppData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.calendar.DeviceEvent
import com.schedulewidget.mobile.data.ScheduleItem
import java.time.LocalDate

private val MIN_DAY_WIDTH = 96.dp
private val PHONE_DAY_WIDTH = 112.dp

@Composable
internal fun Board(
    theme: MiniTheme, view: String, anchor: LocalDate, start: LocalDate, count: Int, days: List<DayModel>, today: LocalDate,
    schedules: List<ScheduleItem>, onUpcoming: () -> Unit,
    onMove: (Int) -> Unit, onPickDate: (LocalDate, Boolean) -> Unit, onView: (String, Int?) -> Unit, onTheme: (String) -> Unit,
    onDay: (LocalDate) -> Unit, onSchedule: (ScheduleItem) -> Unit, onEvent: (DeviceEvent) -> Unit,
    navigate: (Route) -> Unit, modifier: Modifier,
) {
    Box(modifier.padding(top = 6.dp)) {
        Column(
            (if (view == AppData.VIEW_WEEK) Modifier.fillMaxWidth() else Modifier.fillMaxSize()).clip(RoundedCornerShape(18.dp)).background(theme.paper)
                .border(2.dp, theme.frame, RoundedCornerShape(18.dp)),
        ) {
            Header(theme, view, anchor, start, count, today, onMove, onPickDate, onView, onTheme, onUpcoming, navigate)
            HorizontalDivider(color = theme.frame.copy(alpha = 0.35f))
            when (view) {
                AppData.VIEW_MONTH -> MonthGrid(theme, anchor, days, onDay)
                AppData.VIEW_WEEK -> WeekList(theme, days, onDay, onSchedule, onEvent)
                else -> CustomColumns(theme, start, count, days, onDay, onSchedule, onEvent)
            }
        }
        // Two binding rings on the top edge, like the desktop paper calendar.
        Row(Modifier.fillMaxWidth().offset(y = (-6).dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            repeat(2) {
                Box(Modifier.size(width = 10.dp, height = 18.dp).clip(RoundedCornerShape(5.dp)).background(theme.ring)
                    .border(1.5.dp, theme.frame, RoundedCornerShape(5.dp)))
            }
        }
    }
}

/** Custom range: N days side by side; scrolls sideways (starting at today) when the columns would be too narrow. */
@Composable
internal fun CustomColumns(
    theme: MiniTheme, start: LocalDate, count: Int, days: List<DayModel>,
    onDay: (LocalDate) -> Unit, onSchedule: (ScheduleItem) -> Unit, onEvent: (DeviceEvent) -> Unit,
) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Days run left to right like the desktop board. When the columns would get too narrow on a phone,
                // they keep a readable width and scroll sideways, starting at today (today, tomorrow, the day after…).
                val gap = 6.dp
                val fits = (maxWidth - gap * (count + 1)) / count >= MIN_DAY_WIDTH
                if (fits) {
                    Row(Modifier.fillMaxSize().padding(gap), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        days.forEach { DayColumn(it, theme, onDay, onSchedule, onEvent, Modifier.weight(1f).fillMaxHeight()) }
                    }
                } else {
                    val todayIndex = days.indexOfFirst { it.date == LocalCalendarToday.current }.coerceAtLeast(0)
                    // Start at today on the very first frame (no visible jump from the first column).
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = todayIndex)
                    LaunchedEffect(start, count) { if (listState.firstVisibleItemIndex != todayIndex) listState.scrollToItem(todayIndex) }
                    LazyRow(
                        state = listState,
                        contentPadding = PaddingValues(gap),
                        horizontalArrangement = Arrangement.spacedBy(gap),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(days, key = { it.date.toEpochDay() }) {
                            DayColumn(it, theme, onDay, onSchedule, onEvent, Modifier.width(PHONE_DAY_WIDTH).fillMaxHeight())
                        }
                    }
                }
            }
}

/** Week: Monday to Sunday, one day per row going down (fits a tall phone screen). */
@Composable
internal fun WeekList(
    theme: MiniTheme, days: List<DayModel>,
    onDay: (LocalDate) -> Unit, onSchedule: (ScheduleItem) -> Unit, onEvent: (DeviceEvent) -> Unit,
) {
    // Wraps its content: the frame ends right after Sunday; when the days need more room than the screen has, the list
    // takes the available height and scrolls.
    LazyColumn(
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(days, key = { it.date.toEpochDay() }) { DayRow(it, theme, onDay, onSchedule, onEvent) }
    }
}

@Composable
private fun DayRow(
    day: DayModel, theme: MiniTheme,
    onDay: (LocalDate) -> Unit, onSchedule: (ScheduleItem) -> Unit, onEvent: (DeviceEvent) -> Unit,
    minHeight: Dp = 64.dp,
) {
    val ink = dayInk(theme, day)
    Row(dayCellModifier(theme, day, onDay).fillMaxWidth().heightIn(min = minHeight).padding(10.dp)) {
        Column(Modifier.width(56.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${day.date.dayOfMonth}", color = ink, fontSize = calSp(22f), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Text(weekdayName(day.date), color = ink, fontSize = calSp(12f), modifier = Modifier.padding(bottom = 3.dp))
            }
            day.holiday?.let { Text(it, color = theme.holiday, fontSize = calSp(10f), lineHeight = calSp(12f), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (day.schedules.isEmpty() && day.events.isEmpty()) Text("일정 없음", color = theme.muted, fontSize = calSp(13f), modifier = Modifier.padding(top = 4.dp))
            day.schedules.forEach { key(it.id) { ScheduleChip(it, day.colors[it.id], onSchedule, compact = false) } }
            day.events.forEach { EventChip(it, theme, onEvent, compact = false) }
        }
    }
}

/** Month: Mon–Sun grid of 6 weeks; each cell shows up to three items, tap a day for the full list. */
@Composable
internal fun MonthGrid(theme: MiniTheme, anchor: LocalDate, days: List<DayModel>, onDay: (LocalDate) -> Unit) {
    val today = LocalCalendarToday.current
    val showDDay = LocalBlockDDay.current
    Column(Modifier.fillMaxSize().padding(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("월", "화", "수", "목", "금", "토", "일").forEachIndexed { i, w ->
                Text(
                    w, Modifier.weight(1f).padding(vertical = 4.dp), textAlign = TextAlign.Center, fontSize = calSp(12f),
                    color = when (i) { 5 -> theme.saturday; 6 -> theme.holiday; else -> theme.weekday },
                )
            }
        }
        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                week.forEach { day ->
                    val inMonth = day.date.month == anchor.month
                    val isToday = day.date == today
                    val (dropModifier, dropHere) = rememberDropTarget(day.date)
                    Column(
                        Modifier.weight(1f).fillMaxHeight().padding(1.dp).then(dropModifier).clip(RoundedCornerShape(6.dp))
                            .background(if (dropHere) theme.accent.copy(alpha = 0.3f) else if (isToday) theme.today else if (inMonth) theme.day else Color.Transparent)
                            .then(
                                if (dropHere) Modifier.border(2.dp, theme.accent, RoundedCornerShape(6.dp))
                                else if (isToday) Modifier.border(1.5.dp, theme.todayBorder, RoundedCornerShape(6.dp)) else Modifier,
                            )
                            .clickable { onDay(day.date) }
                            .padding(horizontal = 2.dp, vertical = 2.dp)
                            .alpha(if (inMonth) 1f else 0.4f),
                    ) {
                        Text("${day.date.dayOfMonth}", color = dayInk(theme, day), fontSize = calSp(12f), fontWeight = FontWeight.Bold)
                        day.holiday?.let { Text(it, color = theme.holiday, fontSize = calSp(8f), lineHeight = calSp(9f), maxLines = 1, overflow = TextOverflow.Clip) }
                        // (color, text, schedule — null for a phone-calendar event)
                        val items = day.schedules.map { s ->
                            val text = (if (s.important) "★" else "") +
                                (if (s.isCompleted || !showDDay) "" else "${s.dDay(today)} ") +
                                (if (s.rangeLabel.isEmpty()) "" else "${s.rangeLabel} ") + s.title
                            Triple(day.colors[s.id] ?: theme.accent, text, s)
                        } +
                            day.events.map { Triple(it.color?.let { c -> Color(c or 0xFF000000.toInt()) } ?: theme.muted, it.title, null) }
                        items.take(3).forEach { (color, title, schedule) ->
                            key(schedule?.id ?: title) {
                                Text(
                                    title, maxLines = 1, overflow = TextOverflow.Clip, fontSize = calSp(9f), lineHeight = calSp(11f),
                                    color = ScheduleColors.readableOn(color),
                                    modifier = Modifier.fillMaxWidth().padding(top = 1.dp)
                                        .then(if (schedule != null) rememberDragSource(schedule, color) else Modifier)
                                        .clip(RoundedCornerShape(3.dp)).background(color).padding(horizontal = 2.dp),
                                )
                            }
                        }
                        if (items.size > 3) Text("+${items.size - 3}", color = theme.muted, fontSize = calSp(9f))
                    }
                }
            }
        }
    }
}
