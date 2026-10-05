package com.schedulewidget.mobile.ui

import androidx.compose.runtime.key
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schedulewidget.mobile.calendar.DeviceCalendar
import com.schedulewidget.mobile.calendar.DeviceEvent
import com.schedulewidget.mobile.data.ScheduleItem
import com.schedulewidget.mobile.data.ScheduleDates
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

internal fun dayInk(theme: MiniTheme, day: DayModel): Color = when {
    day.holiday != null || day.date.dayOfWeek == DayOfWeek.SUNDAY -> theme.holiday
    day.date.dayOfWeek == DayOfWeek.SATURDAY -> theme.saturday
    else -> theme.weekday
}

internal fun weekdayName(date: LocalDate) = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)

@Composable
internal fun dayCellModifier(theme: MiniTheme, day: DayModel, onDay: (LocalDate) -> Unit): Modifier {
    val today = day.date == LocalCalendarToday.current
    val shape = RoundedCornerShape(12.dp)
    // A dragged schedule block over this day: tinted with the accent (drop target).
    val (dropModifier, dropHere) = rememberDropTarget(day.date)
    return dropModifier.clip(shape)
        .background(if (dropHere) theme.accent.copy(alpha = 0.3f) else if (today) theme.today else theme.day)
        .then(
            if (dropHere) Modifier.border(2.dp, theme.accent, shape)
            else if (today) Modifier.border(2.dp, theme.todayBorder, shape) else Modifier,
        )
        .clickable { onDay(day.date) }
}

@Composable
internal fun DayColumn(
    day: DayModel, theme: MiniTheme,
    onDay: (LocalDate) -> Unit, onSchedule: (ScheduleItem) -> Unit, onEvent: (DeviceEvent) -> Unit, modifier: Modifier,
) {
    val ink = dayInk(theme, day)
    Column(modifier.then(dayCellModifier(theme, day, onDay)).padding(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${day.date.dayOfMonth}", color = ink, fontSize = calSp(20f), fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            Text(weekdayName(day.date), color = ink, fontSize = calSp(12f), modifier = Modifier.padding(bottom = 2.dp))
        }
        day.holiday?.let { Text(it, color = theme.holiday, fontSize = calSp(10f), lineHeight = calSp(12f), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.padding(top = 4.dp))
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            day.schedules.forEach { key(it.id) { ScheduleChip(it, day.colors[it.id], onSchedule, compact = true) } }
            day.events.forEach { EventChip(it, theme, onEvent, compact = true) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpcomingScheduleSheet(
    schedules: List<ScheduleItem>, from: LocalDate, today: LocalDate, theme: MiniTheme,
    onDismiss: () -> Unit, onSchedule: (ScheduleItem) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val upcoming = remember(schedules, from, expanded) { ScheduleDates.upcoming(schedules, from, expanded) }
    val groups = remember(upcoming.items) { upcoming.items.groupBy { it.date!! }.toList() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp).navigationBarsPadding()) {
            Text("다음 일정", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
            if (groups.isEmpty()) {
                Text("예정된 일정이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
            } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                groups.forEach { (date, groupItems) ->
                    item(key = "day-${date.toEpochDay()}") {
                        Text(
                            if (date == today) "오늘" else date.format(DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)),
                            style = MaterialTheme.typography.titleSmall,
                            color = theme.topInk,
                            modifier = Modifier.fillMaxWidth().background(theme.top).padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                    items(groupItems, key = { "schedule-${it.id}" }) { item ->
                        val color = ScheduleColors.parse(item.color) ?: theme.accent
                        Surface(
                            onClick = { onSchedule(item) }, shape = RoundedCornerShape(10.dp), color = color,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    if (item.rangeLabel.isNotEmpty()) Text(item.rangeLabel, color = ScheduleColors.readableOn(color), fontSize = 11.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        item.time?.takeIf { it.isNotBlank() }?.let {
                                            Text(it, color = ScheduleColors.readableOn(color), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(
                                            item.title, color = ScheduleColors.readableOn(color), fontWeight = FontWeight.SemiBold,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                                        )
                                    }
                                }
                                Text(item.dDay(today), color = ScheduleColors.readableOn(color), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            if (!expanded && upcoming.more > 0) {
                TextButton(onClick = { expanded = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("+${upcoming.more}개 더")
                }
            }
        }
    }
}

@Composable
internal fun ScheduleChip(item: ScheduleItem, color: Color?, onClick: (ScheduleItem) -> Unit, compact: Boolean) {
    val bg = color ?: Color(0xFF64748B)
    val fg = ScheduleColors.readableOn(bg)
    val title = @Composable {
        Text(
            listOfNotNull(item.rangeLabel.takeIf { it.isNotEmpty() }, item.title).joinToString(" "),
            color = fg, fontSize = if (compact) calSp(12f) else calSp(14f),
            maxLines = if (compact) 3 else 2, overflow = TextOverflow.Ellipsis,
            textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null,
        )
    }
    val time = item.time?.takeIf { it.isNotBlank() }
    val showDDay = LocalBlockDDay.current
    val dDay = item.dDay(LocalCalendarToday.current).takeIf { !item.isCompleted && showDDay }
    val marked = item.important || item.seriesId != null
    val modifier = Modifier.fillMaxWidth().then(rememberDragSource(item, bg)).alpha(if (item.isCompleted) 0.5f else 1f)
        .clip(RoundedCornerShape(6.dp)).background(bg)
        .clickable { onClick(item) }.padding(horizontal = if (compact) 6.dp else 8.dp, vertical = if (compact) 3.dp else 5.dp)
    // Narrow day columns: time on its own line above the title so the title keeps the full width.
    if (compact) Column(modifier) {
        val head = listOfNotNull(time, dDay).joinToString(" · ")
        if (head.isNotEmpty() || marked) Row(verticalAlignment = Alignment.CenterVertically) {
            ScheduleMarks(item, tint = fg, size = 11)
            if (head.isNotEmpty()) Text(head, color = fg, fontSize = calSp(10f), fontWeight = FontWeight.Bold)
        }
        title()
    } else Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (marked) {
            ScheduleMarks(item, tint = fg, size = 14)
            Spacer(Modifier.width(4.dp))
        }
        time?.let {
            Text(it, color = fg, fontSize = calSp(12f), fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
        }
        Box(Modifier.weight(1f)) { title() }
        dDay?.let {
            Spacer(Modifier.width(6.dp))
            Text(it, color = fg, fontSize = calSp(12f), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun EventChip(event: DeviceEvent, theme: MiniTheme, onClick: (DeviceEvent) -> Unit, compact: Boolean) {
    val accent = event.color?.let { Color(it or 0xFF000000.toInt()) } ?: theme.muted
    val modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(1.dp, accent, RoundedCornerShape(6.dp))
        .background(accent.copy(alpha = 0.10f)).clickable { onClick(event) }
        .padding(horizontal = 6.dp, vertical = if (compact) 2.dp else 4.dp)
    val header = @Composable {
        Icon(Icons.Outlined.Event, "캘린더 일정", tint = accent, modifier = Modifier.size(if (compact) 12.dp else 14.dp))
        Spacer(Modifier.width(4.dp))
        event.time?.let {
            Text(it, color = theme.ink, fontSize = if (compact) calSp(10f) else calSp(12f), fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
        }
    }
    val title = @Composable {
        Text(
            event.title, color = theme.ink.copy(alpha = if (event.done) 0.5f else 1f), fontSize = if (compact) calSp(12f) else calSp(13f),
            maxLines = 2, overflow = TextOverflow.Ellipsis, textDecoration = if (event.done) TextDecoration.LineThrough else null,
        )
    }
    if (compact) Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) { header() }
        title()
    } else Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        header()
        title()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DaySheet(
    model: DayModel, theme: MiniTheme,
    onDismiss: () -> Unit, onSchedule: (ScheduleItem) -> Unit, onEvent: (DeviceEvent) -> Unit,
) {
    val context = LocalContext.current
    var title by rememberSaveable { mutableStateOf("") }
    var time by rememberSaveable { mutableStateOf("") }
    val timeError = time.isNotBlank() && normalizeTime(time) == null
    val add = {
        if (title.isNotBlank() && !timeError) {
            ScheduleActions.save(context, null, ScheduleItem(title = title.trim(), period = model.date.toString(), time = normalizeTime(time)))
            title = ""
            time = ""
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp).navigationBarsPadding().imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.date.format(KoreanDayFormat), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { DeviceCalendar.open(context, model.date) }) { Text("캘린더 앱") }
            }
            model.holiday?.let { Text(it, color = theme.holiday, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.padding(top = 8.dp))
            if (model.schedules.isEmpty() && model.events.isEmpty()) {
                Text("할 일 없음", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            }
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                model.schedules.forEach { s ->
                    Surface(
                        onClick = { onSchedule(s) }, shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(model.colors[s.id] ?: ScheduleColors.parse(s.color) ?: theme.muted))
                            Spacer(Modifier.width(8.dp))
                            s.time?.let { Text(it, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp)) }
                            Text(
                                s.title, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                textDecoration = if (s.isCompleted) TextDecoration.LineThrough else null,
                            )
                            if (s.rangeLabel.isNotEmpty()) Text(s.rangeLabel, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 6.dp))
                            ScheduleMarks(s, tint = Color.Unspecified, size = 16)
                            Spacer(Modifier.width(4.dp))
                            DdayBadge(s)
                        }
                    }
                }
                model.events.forEach { EventChip(it, theme, onEvent, compact = false) }
            }
            Spacer(Modifier.padding(top = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, singleLine = true, placeholder = { Text("새 일정") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = time, onValueChange = { time = it }, singleLine = true, isError = timeError,
                    placeholder = { Text("시간") }, modifier = Modifier.width(88.dp),
                )
                TextButton(onClick = add, enabled = title.isNotBlank() && !timeError) { Text("추가") }
            }
        }
    }
}
