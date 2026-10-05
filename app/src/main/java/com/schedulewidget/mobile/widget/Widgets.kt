package com.schedulewidget.mobile.widget

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.schedulewidget.mobile.data.GoogleCalendarLink
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.schedulewidget.mobile.calendar.DeviceCalendar
import com.schedulewidget.mobile.calendar.DeviceEvent
import com.schedulewidget.mobile.data.AppData
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.data.ScheduleItem
import com.schedulewidget.mobile.data.ScheduleDates
import com.schedulewidget.mobile.ui.QuickAddActivity
import com.schedulewidget.mobile.ui.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

private val AnchorKey = longPreferencesKey("anchor_epoch_day")
/** Bumped by [WidgetUpdater.refreshAll] so device-calendar events are re-read. */
internal val TickKey = longPreferencesKey("refresh_tick")
private val DeltaParam = ActionParameters.Key<Int>("delta")

private data class DayEntry(
    val title: String, val time: String?, val color: Color, val done: Boolean, val dDay: String? = null,
    /** Set for our own schedules: tapping opens edit / 완료 / 삭제. */
    val scheduleId: String? = null,
    /** 중요 schedules come first in their day and carry a ★. */
    val important: Boolean = false,
)

private const val AGENDA_DAYS = 14L

class CalendarWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(DpSize(120.dp, 110.dp), DpSize(250.dp, 110.dp), DpSize(250.dp, 200.dp), DpSize(320.dp, 280.dp))
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = Repository.get(context)
        // Starting value read outside composition; the flow keeps it current.
        val initialData = repo.data.value
        provideContent {
            val prefs = currentState<Preferences>()
            // Only what this widget draws: pet drags, music volume, sync timestamps etc. don't redraw it.
            val calendarData = remember {
                repo.data.map { d ->
                    AppData(
                        schedules = d.schedules, miniDayCount = d.miniDayCount, miniTheme = d.miniTheme,
                        miniBlockDDayVisible = d.miniBlockDDayVisible, calendar = d.calendar,
                        googleCalendar = GoogleCalendarLink(enabled = d.googleCalendar.enabled, account = d.googleCalendar.account),
                    )
                }.distinctUntilChanged()
            }
            val data by calendarData.collectAsState(initialData)
            val today = LocalDate.now()
            val count = data.miniDayCount.coerceIn(1, 7)
            val anchor = prefs[AnchorKey]?.let(LocalDate::ofEpochDay) ?: today
            val start = periodStart(anchor, count)
            // Schedules and the Google link are keys too: DeviceCalendar.events hides events that duplicate a schedule.
            val events by produceState(
                emptyList<DeviceEvent>(), start, count, today, data.calendar, data.schedules, data.googleCalendar, prefs[TickKey],
            ) {
                value = withContext(Dispatchers.IO) {
                    val windows = listOf(
                        start to start.plusDays(count.toLong()),
                        today to today.plusDays(AGENDA_DAYS),
                    )
                    windows.flatMap { (from, to) ->
                        runCatching { DeviceCalendar.events(context, from, to) }.getOrDefault(emptyList())
                    }.distinctBy { it.id }
                }
            }
            CalendarContent(data, events, today, start, count, anchor != today)
        }
    }
}

internal fun periodStart(anchor: LocalDate, count: Int): LocalDate =
    if (count == 7) anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else anchor

class ShiftPeriodAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val delta = parameters[DeltaParam] ?: 0
        val count = Repository.get(context).data.value.miniDayCount.coerceIn(1, 7)
        updateAppWidgetState(context, glanceId) { prefs ->
            if (delta == 0) prefs.remove(AnchorKey)
            else {
                val cur = prefs[AnchorKey] ?: LocalDate.now().toEpochDay()
                val next = cur + delta.toLong() * count
                if (next == LocalDate.now().toEpochDay()) prefs.remove(AnchorKey) else prefs[AnchorKey] = next
            }
        }
        CalendarWidget().update(context, glanceId)
    }
}

private fun entriesByDay(
    data: AppData, events: List<DeviceEvent>, palette: WidgetPalette, start: LocalDate, count: Int, today: LocalDate,
): Map<LocalDate, List<DayEntry>> {
    val result = HashMap<LocalDate, MutableList<DayEntry>>()
    val scheduleDays = linkedMapOf<LocalDate, LinkedHashMap<String, ScheduleItem>>()
    val windows = listOf(
        start to start.plusDays(count - 1L),
        today to today.plusDays(AGENDA_DAYS - 1),
    )
    windows.forEach { (from, to) ->
        ScheduleDates.byDay(data.schedules, from, to).forEach { (date, items) ->
            val day = scheduleDays.getOrPut(date) { linkedMapOf() }
            items.forEach { day[it.id] = it }
        }
    }
    scheduleDays.forEach { (date, indexed) ->
        val items = indexed.values.sortedWith(ScheduleDates.dayOrder)
        val used = HashSet<Int>()
        result.getOrPut(date) { mutableListOf() } += items.map { s ->
            DayEntry(
                title = (if (s.important) "★ " else "") + listOfNotNull(s.rangeLabel.takeIf { it.isNotEmpty() }, s.title).joinToString(" "),
                time = s.time, color = parseHexColor(s.color) ?: palette.blocks[pickColor(s, palette.blocks.size, used)], done = s.isCompleted,
                // 설정 > 일정 블록에 D-day 표시 (PC MiniBlockDDayVisible) off: the blocks show only the title.
                dDay = s.dDay(today).takeIf { !s.isCompleted && data.miniBlockDDayVisible }, scheduleId = s.id, important = s.important,
            )
        }
    }
    events.forEach { e ->
        result.getOrPut(e.date) { mutableListOf() } += DayEntry(e.title, e.time, e.color?.let { Color(it) } ?: palette.subText, e.done)
    }
    return result.mapValues { (_, list) -> list.sortedWith(compareBy({ it.done }, { !it.important }, { it.time ?: "99:99" }, { it.title })) }
}

/** Stable color per schedule id; on the same day tries to avoid repeating a color. */
private fun pickColor(s: ScheduleItem, size: Int, used: MutableSet<Int>): Int {
    val base = abs(s.id.hashCode() % size)
    val idx = (0 until size).map { (base + it) % size }.firstOrNull { it !in used } ?: base
    used += idx
    return idx
}

private fun nearestDDay(data: AppData, today: LocalDate): Pair<String, String>? =
    data.schedules.filter { !it.isCompleted && (it.remainingDays(today) ?: -1) >= 0 }
        .minWithOrNull(compareBy<ScheduleItem>({ it.date }, { it.time ?: "" }))
        ?.let { it.dDay(today) to it.title }

private fun periodLabel(start: LocalDate, count: Int): String {
    if (count == 1) return "${start.monthValue}월 ${start.dayOfMonth}일 (${start.weekdayKo()})"
    val end = start.plusDays(count - 1L)
    return if (start.month == end.month) "${start.monthValue}월 ${start.dayOfMonth}–${end.dayOfMonth}일"
    else "${start.monthValue}.${start.dayOfMonth} – ${end.monthValue}.${end.dayOfMonth}"
}

@Composable
private fun CalendarContent(
    data: AppData, events: List<DeviceEvent>, today: LocalDate, start: LocalDate, count: Int, shifted: Boolean,
) {
    val size = LocalSize.current
    val palette = WidgetPalette.of(data.miniTheme)
    val byDay = remember(data.schedules, events, palette, start, count, today, data.miniBlockDDayVisible) {
        entriesByDay(data, events, palette, start, count, today)
    }
    val dday = nearestDDay(data, today)
    Column(
        GlanceModifier.fillMaxSize().background(palette.background).cornerRadius(18.dp).padding(8.dp)
    ) {
        if (size.width < 200.dp) {
            AgendaHeader(today, palette)
            DDayLine(dday, palette)
            Agenda(byDay, today, palette)
        } else {
            Header(start, count, shifted, if (size.height < 200.dp) dday else null, palette)
            Spacer(GlanceModifier.height(4.dp))
            val maxItems = ((size.height.value - (if (size.height < 200.dp) 78 else 100)) / 20).toInt().coerceIn(1, 7)
            Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                for (i in 0 until count) {
                    val date = start.plusDays(i.toLong())
                    DayColumn(date, date == today, byDay[date].orEmpty(), maxItems, count, palette, GlanceModifier.defaultWeight())
                    if (i < count - 1) Spacer(GlanceModifier.width(3.dp))
                }
            }
            if (size.height >= 200.dp) {
                Spacer(GlanceModifier.height(4.dp))
                DDayLine(dday, palette)
            }
        }
    }
}

@Composable
private fun HeaderButton(label: String, palette: WidgetPalette, action: androidx.glance.action.Action) {
    Box(
        GlanceModifier.height(28.dp).width(30.dp).cornerRadius(14.dp).background(palette.surface).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TextStyle(color = palette.accent.provider(), fontSize = 15.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun Header(start: LocalDate, count: Int, shifted: Boolean, dday: Pair<String, String>?, palette: WidgetPalette) {
    val context = LocalContext.current
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HeaderButton("‹", palette, actionRunCallback<ShiftPeriodAction>(actionParametersOf(DeltaParam to -1)))
        Text(
            periodLabel(start, count),
            modifier = GlanceModifier.defaultWeight().padding(horizontal = 6.dp)
                .clickable(actionStartActivity(routeIntent(context, Route.Mini))),
            style = TextStyle(color = palette.text.provider(), fontSize = 14.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        if (dday != null) {
            Text(
                dday.first,
                modifier = GlanceModifier.padding(end = 6.dp),
                style = TextStyle(color = palette.accent.provider(), fontSize = 12.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
        if (shifted) {
            HeaderButton("오늘", palette, actionRunCallback<ShiftPeriodAction>(actionParametersOf(DeltaParam to 0)))
            Spacer(GlanceModifier.width(4.dp))
        }
        HeaderButton("+", palette, actionStartActivity(QuickAddActivity.intent(context, start.coerceAtLeast(LocalDate.now()))))
        Spacer(GlanceModifier.width(4.dp))
        HeaderButton("앱", palette, actionStartActivity(routeIntent(context, Route.Mini)))
        Spacer(GlanceModifier.width(4.dp))
        HeaderButton("›", palette, actionRunCallback<ShiftPeriodAction>(actionParametersOf(DeltaParam to 1)))
    }
}

@Composable
private fun DayColumn(
    date: LocalDate, isToday: Boolean, entries: List<DayEntry>, maxItems: Int, count: Int,
    palette: WidgetPalette, modifier: GlanceModifier,
) {
    val context = LocalContext.current
    val compact = count >= 5
    Column(
        modifier.fillMaxHeight().cornerRadius(10.dp)
            .background(if (isToday) palette.todayBg else palette.surface)
            .padding(horizontal = 3.dp, vertical = 4.dp)
            // Tapping a day adds a schedule on that day.
            .clickable(actionStartActivity(QuickAddActivity.intent(context, date))),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val holiday = WidgetHolidays.name(date)
        val dateText = if (compact) "${date.dayOfMonth} ${date.weekdayKo()}" else "${date.monthValue}/${date.dayOfMonth} (${date.weekdayKo()})"
        Text(
            dateText,
            style = TextStyle(
                color = (if (isToday) palette.accent else palette.dayColor(date)).provider(),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            ),
            maxLines = 1,
        )
        if (holiday != null && !compact) {
            Text(holiday, style = TextStyle(color = palette.sunday.provider(), fontSize = 9.sp), maxLines = 1)
        }
        val shown = if (entries.size > maxItems) entries.take(maxItems - 1) else entries
        shown.forEach { EntryBlock(it, compact, palette) }
        if (entries.size > shown.size) {
            Text(
                "+ ${entries.size - shown.size}개",
                style = TextStyle(color = palette.subText.provider(), fontSize = 10.sp, textAlign = TextAlign.Center),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EntryBlock(entry: DayEntry, compact: Boolean, palette: WidgetPalette) {
    val context = LocalContext.current
    val bg = if (entry.done) entry.color.copy(alpha = 0.35f) else entry.color
    val fg = if (entry.done) palette.subText else contentColorFor(entry.color)
    val base = if (entry.time != null && !compact) "${entry.time} ${entry.title}" else entry.title
    val label = entry.dDay?.let { if (compact) "$it $base" else "$base · $it" } ?: base
    Spacer(GlanceModifier.height(2.dp))
    // Our schedules open edit / 완료 / 삭제; the day around it still adds a new one.
    val tap = entry.scheduleId?.let { GlanceModifier.clickable(actionStartActivity(QuickAddActivity.editIntent(context, it))) } ?: GlanceModifier
    Box(GlanceModifier.fillMaxWidth().cornerRadius(5.dp).background(bg).then(tap).padding(horizontal = 3.dp, vertical = 1.dp)) {
        Text(label, style = TextStyle(color = fg.provider(), fontSize = 10.sp), maxLines = 1)
    }
}

@Composable
private fun DDayLine(dday: Pair<String, String>?, palette: WidgetPalette) {
    Text(
        dday?.let { "${it.first}  ${it.second}" } ?: "다가오는 일정 없음",
        modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        style = TextStyle(
            color = (if (dday != null) palette.accent else palette.subText).provider(),
            fontSize = 12.sp, fontWeight = FontWeight.Bold,
        ),
        maxLines = 1,
    )
}

@Composable
private fun AgendaHeader(today: LocalDate, palette: WidgetPalette) {
    val context = LocalContext.current
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${today.monthValue}월 ${today.dayOfMonth}일 (${today.weekdayKo()})",
            modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(routeIntent(context, Route.Mini))),
            style = TextStyle(color = palette.dayColor(today).provider(), fontSize = 14.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        HeaderButton("+", palette, actionStartActivity(QuickAddActivity.intent(context)))
        Spacer(GlanceModifier.width(4.dp))
        HeaderButton("앱", palette, actionStartActivity(routeIntent(context, Route.Mini)))
    }
}

@Composable
private fun Agenda(byDay: Map<LocalDate, List<DayEntry>>, today: LocalDate, palette: WidgetPalette) {
    val context = LocalContext.current
    val rows = (0 until AGENDA_DAYS).flatMap { d ->
        val date = today.plusDays(d)
        byDay[date].orEmpty().filter { !it.done }.map { date to it }
    }
    if (rows.isEmpty()) {
        Text(
            "예정된 일정이 없어요",
            modifier = GlanceModifier.padding(4.dp),
            style = TextStyle(color = palette.subText.provider(), fontSize = 12.sp),
        )
        return
    }
    LazyColumn(GlanceModifier.fillMaxSize()) {
        items(rows) { (date, entry) ->
            Row(
                GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clickable(actionStartActivity(
                        entry.scheduleId?.let { QuickAddActivity.editIntent(context, it) } ?: routeIntent(context, Route.Mini)
                    )),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(GlanceModifier.width(4.dp).height(16.dp).cornerRadius(2.dp).background(entry.color))
                Text(
                    if (date == today) "오늘" else "${date.monthValue}/${date.dayOfMonth}",
                    modifier = GlanceModifier.width(38.dp).padding(start = 4.dp),
                    style = TextStyle(color = palette.dayColor(date).provider(), fontSize = 11.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Text(
                    listOfNotNull(entry.time, entry.title).joinToString(" "),
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(color = palette.text.provider(), fontSize = 11.sp),
                    maxLines = 1,
                )
                entry.dDay?.let {
                    Text(it, style = TextStyle(color = palette.accent.provider(), fontSize = 11.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                }
            }
        }
    }
}
