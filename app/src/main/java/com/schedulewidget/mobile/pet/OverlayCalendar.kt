package com.schedulewidget.mobile.pet

import com.schedulewidget.mobile.ui.QuickAddActivity
import com.schedulewidget.mobile.ui.LocalCalendarTextScale
import com.schedulewidget.mobile.ui.LocalCalendarToday
import com.schedulewidget.mobile.ui.calSp
import com.schedulewidget.mobile.ui.calendarTextFactor
import com.schedulewidget.mobile.ui.rememberCalendarToday
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.ExperimentalComposeUiApi
import android.view.MotionEvent
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.schedulewidget.mobile.ui.CalendarPageFlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Tablet-size panel width used by the UI checks; its seven day columns show without horizontal scrolling. */
const val OVERLAY_CALENDAR_FULL_WIDTH_DP = 800

/** Compact 7-day agenda shown next to the floating pet on double-tap. */
@OptIn(ExperimentalComposeUiApi::class)
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
    /** Drag the date-range header to move the whole panel; deltas are raw screen pixels. */
    onMove: ((dx: Float, dy: Float) -> Unit)? = null,
) {
    val context = LocalContext.current
    val data by remember { Repository.get(context) }.data.collectAsState()
    val theme = MiniThemes.of(data.miniTheme)
    val today = rememberCalendarToday()
    var shownStart by rememberSaveable { mutableStateOf(today.toString()) }
    var followsToday by rememberSaveable { mutableStateOf(true) }
    val headerHeightPx = remember { mutableIntStateOf(0) }
    val start = if (followsToday) today else runCatching { LocalDate.parse(shownStart) }.getOrDefault(today)
    val days = remember(start) { (0L until 7L).map { start.plusDays(it) } }
    val addDate = if (today in days) today else start
    var themeMenu by remember { mutableStateOf(false) }
    fun movePage(offsetDays: Long) {
        val current = if (followsToday) today else runCatching { LocalDate.parse(shownStart) }.getOrDefault(today)
        val next = current.plusDays(offsetDays)
        shownStart = next.toString()
        followsToday = next == today
    }
    // Same paper-calendar card as the in-app board (ui/CalendarBoard.kt): 18dp corners, 2dp frame.
    val shape = RoundedCornerShape(18.dp)

    BoxWithConstraints {
    val density = LocalDensity.current
    val compactHeader = maxWidth < 380.dp
    // Six 48dp controls need a 316dp panel (6×48 + 2×4 + 2×10); below that the week arrows flank the period.
    val arrowsBesidePeriod = maxWidth < 316.dp
    // 48 = 2×10 outer + 2×8 row padding + 6×2 gaps. Columns are 90% of a full share (centred, never below 48dp);
    // text keeps the full-share scale so only the columns get narrower.
    val fullDayWidth = ((maxWidth.value - 48f) / 7f).coerceAtLeast(48f)
    val dayWidth = (fullDayWidth * 0.9f).coerceAtLeast(48f)
    val panelTextScale = ((fullDayWidth / 64f) * (dayHeight / 260f).coerceIn(0.9f, 1.2f)).coerceIn(0.75f, 1.35f)
    val period = if (compactHeader) {
        "${start.monthValue}.${start.dayOfMonth}-${days.last().monthValue}.${days.last().dayOfMonth}"
    } else {
        "${start.monthValue}.${start.dayOfMonth} — ${days.last().monthValue}.${days.last().dayOfMonth}"
    }
    val previousButton: @Composable () -> Unit = {
        IconButton(onClick = { movePage(-7) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 7일", tint = theme.topInk) }
    }
    val nextButton: @Composable () -> Unit = {
        IconButton(onClick = { movePage(7) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 7일", tint = theme.topInk) }
    }
    // Back to the live today; like a fresh panel, it then follows midnight again.
    val todayButton: @Composable () -> Unit = {
        IconButton(onClick = { shownStart = today.toString(); followsToday = true }) {
            Icon(Icons.Filled.Today, contentDescription = "오늘로 이동", tint = theme.topInk)
        }
    }
    val themeButton: @Composable () -> Unit = {
        Box(Modifier.size(48.dp)) {
            IconButton(onClick = { themeMenu = true }) {
                Box(Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Palette, contentDescription = "테마 선택: ${theme.label}", tint = theme.topInk)
                    Icon(
                        Icons.Filled.ArrowDropDown, contentDescription = null,
                        modifier = Modifier.align(Alignment.BottomEnd).size(14.dp).background(theme.top), tint = theme.topInk,
                    )
                }
            }
            DropdownMenu(
                expanded = themeMenu, onDismissRequest = { themeMenu = false },
                containerColor = theme.paper, tonalElevation = 0.dp,
            ) {
                MiniThemes.all.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        leadingIcon = {
                            Box(Modifier.size(20.dp).clip(CircleShape).background(option.paper).border(3.dp, option.frame, CircleShape))
                        },
                        trailingIcon = { if (option.id == theme.id) Icon(Icons.Filled.Check, "선택됨") },
                        colors = MenuDefaults.itemColors(
                            textColor = theme.ink, leadingIconColor = theme.muted, trailingIconColor = theme.ink,
                        ),
                        onClick = {
                            themeMenu = false
                            Repository.get(context).update { it.copy(miniTheme = option.id) }
                        },
                    )
                }
                HorizontalDivider(color = theme.hairline)
                DropdownMenuItem(
                    text = { Text("일정 추가") },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    colors = MenuDefaults.itemColors(textColor = theme.ink, leadingIconColor = theme.muted),
                    onClick = { themeMenu = false; onAdd(addDate) },
                )
                DropdownMenuItem(
                    text = { Text("달력 닫기") },
                    leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                    colors = MenuDefaults.itemColors(textColor = theme.ink, leadingIconColor = theme.muted),
                    onClick = { themeMenu = false; onClose() },
                )
            }
        }
    }
    Column(
        Modifier.padding(10.dp).shadow(8.dp, shape).clip(shape).background(theme.paper)
            .border(2.dp, theme.frame, shape),
    ) {
        Column(
            // Extra top padding keeps the header text clear of the binding rings that hang over the top edge.
            Modifier.fillMaxWidth().onSizeChanged { headerHeightPx.intValue = it.height }
                .background(theme.top).padding(start = 4.dp, end = 4.dp, top = 8.dp),
        ) {
            if (compactHeader) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (arrowsBesidePeriod) previousButton()
                    Box(
                        Modifier.weight(1f).heightIn(min = if (arrowsBesidePeriod) 48.dp else 20.dp).calendarMoveHandle(onMove),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            period, color = theme.topInk, fontWeight = FontWeight.Bold,
                            fontSize = (15f * panelTextScale).coerceAtLeast(11f).sp,
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (arrowsBesidePeriod) nextButton()
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!arrowsBesidePeriod) previousButton()
                    themeButton()
                    todayButton()
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { onOpenApp(Route.Mini) }) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = "앱 열기", tint = theme.topInk)
                    }
                    IconButton(onClick = { onOpenApp(Route.Settings) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "설정", tint = theme.topInk)
                    }
                    if (!arrowsBesidePeriod) nextButton()
                }
            } else {
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Box(
                        Modifier.fillMaxWidth().padding(start = 144.dp, end = 144.dp).heightIn(min = 48.dp).calendarMoveHandle(onMove),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            period, color = theme.topInk, fontWeight = FontWeight.Bold,
                            fontSize = (15f * panelTextScale).coerceAtLeast(11f).sp,
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.width(144.dp), verticalAlignment = Alignment.CenterVertically) {
                            previousButton()
                            themeButton()
                            todayButton()
                        }
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.width(144.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onOpenApp(Route.Mini) }) {
                                Icon(Icons.Filled.OpenInNew, contentDescription = "앱 열기", tint = theme.topInk)
                            }
                            IconButton(onClick = { onOpenApp(Route.Settings) }) {
                                Icon(Icons.Filled.Settings, contentDescription = "설정", tint = theme.topInk)
                            }
                            nextButton()
                        }
                    }
                }
            }
        }
        // The seven dates share 90% of the width, centred; the user's font setting scales with the panel.
        CompositionLocalProvider(
            LocalCalendarTextScale provides data.calendarTextFactor * panelTextScale,
            LocalCalendarToday provides today,
        ) {
            CalendarPageFlip(
                start = start, effect = data.miniFlipEffect, paper = theme.paper, aboveRoomPx = headerHeightPx.intValue,
                modifier = Modifier.fillMaxWidth(),
            ) { pageStart ->
                val pageDays = remember(pageStart) { (0L until 7L).map { pageStart.plusDays(it) } }
                val pageEvents by produceState(emptyList<DeviceEvent>(), pageStart, data.calendar, data.schedules, data.googleCalendar) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { DeviceCalendar.events(context, pageStart, pageStart.plusDays(7)) }.getOrDefault(emptyList())
                    }
                }
                val pageSchedules = remember(data.schedules, pageStart) {
                    ScheduleDates.byDay(data.schedules, pageStart, pageStart.plusDays(6))
                }
                val pageEventsByDay = remember(pageEvents) { pageEvents.groupBy { it.date } }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).horizontalScroll(rememberScrollState())
                        .widthIn(min = (this@BoxWithConstraints.maxWidth - 36.dp).coerceAtLeast(0.dp)),
                    horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                ) {
                    for (day in pageDays) {
                        val holiday = KoreanHolidays.nameOf(day)
                        val tasks = pageSchedules[day].orEmpty()
                        val dayEvents = pageEventsByDay[day].orEmpty()
                        // Same per-day colour assignment as the mini calendar board.
                        val blocks = ScheduleColors.blockColors(tasks)
                        val dayColor = when {
                            holiday != null || day.dayOfWeek == DayOfWeek.SUNDAY -> theme.holiday
                            day.dayOfWeek == DayOfWeek.SATURDAY -> theme.saturday
                            else -> theme.weekday
                        }
                        Column(
                            Modifier.width(dayWidth.dp).height(dayHeight.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (day == today) theme.today else theme.day)
                                .clickable { onAdd(day) }
                                .padding(horizontal = 2.dp, vertical = 6.dp),
                        ) {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${day.dayOfMonth}", color = dayColor, fontWeight = FontWeight.Bold, fontSize = calSp(17f), textAlign = TextAlign.Center)
                                Spacer(Modifier.height(1.dp))
                                Text(day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN), color = dayColor, fontSize = calSp(11f),
                                    textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 2.dp))
                            }
                            holiday?.let {
                                Text(it, color = dayColor, fontSize = calSp(10f), lineHeight = calSp(12f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            }
                            Column(
                                Modifier.padding(top = 4.dp).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
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
        val cornerPx = with(density) { corner.toPx() }
        val edgePx = with(density) { 10.dp.toPx() }
        ResizeGrip(ResizeEdges(left = true, top = true), onResize, onResizeEnd, Modifier.align(Alignment.TopStart).size(corner), cornerPx, edgePx)
        ResizeGrip(ResizeEdges(right = true, top = true), onResize, onResizeEnd, Modifier.align(Alignment.TopEnd).size(corner), cornerPx, edgePx)
        ResizeGrip(ResizeEdges(left = true, bottom = true), onResize, onResizeEnd, Modifier.align(Alignment.BottomStart).size(corner), cornerPx, edgePx)
        ResizeGrip(ResizeEdges(right = true, bottom = true), onResize, onResizeEnd, Modifier.align(Alignment.BottomEnd).size(corner), cornerPx, edgePx)
        ResizeGrip(ResizeEdges(left = true), onResize, onResizeEnd, Modifier.align(Alignment.CenterStart).width(16.dp).fillMaxHeight(0.6f))
        ResizeGrip(ResizeEdges(right = true), onResize, onResizeEnd, Modifier.align(Alignment.CenterEnd).width(16.dp).fillMaxHeight(0.6f))
        ResizeGrip(ResizeEdges(bottom = true), onResize, onResizeEnd, Modifier.align(Alignment.BottomCenter).height(16.dp).fillMaxWidth(0.6f))
    }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.calendarMoveHandle(onMove: ((Float, Float) -> Unit)?): Modifier {
    if (onMove == null) return this
    return composed {
        var lastX by remember { mutableFloatStateOf(0f) }
        var lastY by remember { mutableFloatStateOf(0f) }
        pointerInteropFilter { event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = event.rawX; lastY = event.rawY }
                MotionEvent.ACTION_MOVE -> {
                    onMove(event.rawX - lastX, event.rawY - lastY)
                    lastX = event.rawX; lastY = event.rawY
                }
            }
            true
        }
    }
}

/** Which sides a grip moves: right/bottom grow the panel away from the top-left; left/top keep the opposite side still. */
data class ResizeEdges(val left: Boolean = false, val top: Boolean = false, val right: Boolean = false, val bottom: Boolean = false)

/** A resize handle. Uses raw screen coordinates because the window itself moves/grows under the finger. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ResizeGrip(
    edges: ResizeEdges, onResize: (ResizeEdges, Float, Float) -> Unit, onEnd: () -> Unit, modifier: Modifier,
    cornerSizePx: Float? = null, edgeSizePx: Float = 0f,
) {
    var lastX by remember { mutableFloatStateOf(0f) }
    var lastY by remember { mutableFloatStateOf(0f) }
    var resizing by remember { mutableStateOf(false) }
    Spacer(
        modifier.pointerInteropFilter { e ->
            val wasResizing = resizing
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resizing = cornerSizePx?.let { size ->
                        (edges.left && e.x <= edgeSizePx) || (edges.right && e.x >= size - edgeSizePx) ||
                            (edges.top && e.y <= edgeSizePx) || (edges.bottom && e.y >= size - edgeSizePx)
                    } ?: true
                    if (resizing) { lastX = e.rawX; lastY = e.rawY }
                }
                MotionEvent.ACTION_MOVE -> if (resizing) {
                    onResize(edges, e.rawX - lastX, e.rawY - lastY)
                    lastX = e.rawX; lastY = e.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (resizing) {
                    onEnd()
                    resizing = false
                }
            }
            wasResizing || resizing
        },
    )
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
