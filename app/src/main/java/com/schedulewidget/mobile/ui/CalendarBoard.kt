package com.schedulewidget.mobile.ui

import androidx.compose.runtime.Composable
import com.schedulewidget.mobile.pet.PetApps
import com.schedulewidget.mobile.pet.PetMenu
import com.schedulewidget.mobile.record.Recorder
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.schedulewidget.mobile.data.AppData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.apps.MusicBar
import com.schedulewidget.mobile.calendar.DeviceCalendar
import com.schedulewidget.mobile.calendar.DeviceEvent
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.data.ScheduleDates
import com.schedulewidget.mobile.pet.DraggablePet
import com.schedulewidget.mobile.pet.FloatingPet
import com.schedulewidget.mobile.pet.PetMusicPanel
import com.schedulewidget.mobile.pet.petSlots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
internal fun MiniBoardScreen(navigate: (Route) -> Unit, onNotes: (() -> Unit)? = null) {
    val context = LocalContext.current
    val repo = Repository.get(context)
    val data by repo.data.collectAsStateWithLifecycle()
    val theme = MiniThemes.of(data.miniTheme)
    val view = data.calendarView
    val today = rememberCalendarToday()
    var anchorDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var followsToday by rememberSaveable { mutableStateOf(true) }
    val anchor = if (followsToday) today else LocalDate.ofEpochDay(anchorDay)
    // Days loaded for the current view: a 6-week grid for the month, Mon–Sun for the week, N days for custom.
    val start = when (view) {
        AppData.VIEW_MONTH -> startOfWeek(anchor.withDayOfMonth(1))
        AppData.VIEW_CUSTOM -> anchor
        else -> startOfWeek(anchor)
    }
    val count = when (view) {
        AppData.VIEW_MONTH -> 42
        AppData.VIEW_CUSTOM -> data.miniDayCount.coerceIn(1, AppData.MAX_CUSTOM_DAYS)
        else -> 7
    }

    var reload by remember { mutableIntStateOf(0) }
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reload++; resumed++ }
    // Also pick up device-calendar changes (account sync, other apps) while the board stays open. Re-registered on
    // resume because the calendar permission may have just been granted.
    DisposableEffect(resumed) {
        val unregister = DeviceCalendar.observeChanges(context) { reload++ }
        onDispose { unregister() }
    }
    val events by produceState(emptyList<DeviceEvent>(), start, count, data.calendar, data.schedules, data.googleCalendar, reload) {
        value = withContext(Dispatchers.IO) {
            runCatching { DeviceCalendar.events(context, start, start.plusDays(count.toLong())) }.getOrDefault(emptyList())
        }
    }

    val scheduleByDay = remember(data.schedules, start, count) {
        ScheduleDates.byDay(data.schedules, start, start.plusDays(count - 1L))
    }
    val eventsByDay = remember(events) { events.groupBy { it.date } }
    val days = remember(scheduleByDay, eventsByDay, start, count) {
        (0 until count).map { offset ->
            val date = start.plusDays(offset.toLong())
            val tasks = scheduleByDay[date].orEmpty()
            DayModel(date, KoreanHolidays.nameOf(date), tasks, ScheduleColors.blockColors(tasks),
                eventsByDay[date].orEmpty().sortedBy { it.time ?: "" })
        }
    }

    // Saveable so an open day sheet / detail sheet / MP3 panel survives rotation.
    var sheetDayEpoch by rememberSaveable { mutableStateOf<Long?>(null) }
    val sheetDay = sheetDayEpoch?.let(LocalDate::ofEpochDay)
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var eventDetail by remember { mutableStateOf<DeviceEvent?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    var musicOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var activePetIndex by rememberSaveable { mutableIntStateOf(0) }
    var upcomingOpen by rememberSaveable { mutableStateOf(false) }
    val visiblePets = remember(data) {
        if (data.miniCharacterVisible) data.petSlots().filterNot { it.hidden }.take(3) else emptyList()
    }
    val showPet = visiblePets.isNotEmpty()
    LaunchedEffect(visiblePets) {
        if (visiblePets.none { it.index == activePetIndex }) {
            activePetIndex = visiblePets.firstOrNull()?.index ?: 0
            menuOpen = false
            musicOpen = false
        }
    }
    // Crisp when the pet also stays on screen after the app closes (화면 위에 펫 띄우기), see-through when it does not.
    val floating = remember(resumed, data.floatingPet) { FloatingPet.isActive(context) }

    // Long-press a schedule block and drop it on another day.
    val drag = remember { BoardDrag() }
    var boardOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier.fillMaxSize().background(theme.canvas).safeDrawingPadding()
            .onGloballyPositioned { boardOrigin = it.positionInRoot() },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Reserve the pet's default spot above the board until the user drags it somewhere else.
            visiblePets.filter { it.y == null }.maxOfOrNull { it.scale }?.let { scale ->
                Spacer(Modifier.height((PET_HEIGHT * scale.coerceIn(50, 300) / 100).dp))
            }
            // Only the in-app pet can bring the board back, so never keep it hidden while that pet is away.
            val hidden = data.calendarHidden && showPet
            // The board stays composed and only fades: tearing it down and rebuilding it (lazy lists, scroll-to-today,
            // header) on every double tap made it flash. While hidden, a cover takes the touches and hides it from TalkBack.
            val boardAlpha by animateFloatAsState(if (hidden) 0f else 1f, tween(180), label = "boardAlpha")
            // The week list ends at Sunday: its frame is only as tall as the seven days (no empty band under them on tall
            // screens / tablets), and the MP3 bar follows right below. Month / custom views fill the space.
            val wrapBoard = view == AppData.VIEW_WEEK && !hidden
            Box(Modifier.weight(1f, fill = !wrapBoard).fillMaxWidth()) {
                CompositionLocalProvider(
                    LocalCalendarTextScale provides data.calendarTextFactor,
                    LocalBlockDDay provides data.miniBlockDDayVisible,
                    LocalCalendarToday provides today,
                    LocalBoardDrag provides drag,
                ) {
                Board(
                    theme = theme, view = view, anchor = anchor, start = start, count = count, days = days, today = today,
                    onMove = { dir ->
                        followsToday = false
                        anchorDay = when (view) {
                            AppData.VIEW_MONTH -> anchor.withDayOfMonth(1).plusMonths(dir.toLong())
                            AppData.VIEW_CUSTOM -> anchor.plusDays(dir * count.toLong())
                            else -> anchor.plusWeeks(dir.toLong())
                        }.toEpochDay()
                    },
                    onPickDate = { date, follow ->
                        followsToday = follow
                        anchorDay = date.toEpochDay()
                    },
                    onView = { v, n -> repo.update { it.copy(calendarView = v, miniDayCount = n ?: it.miniDayCount) } },
                    onTheme = { id -> repo.update { it.copy(miniTheme = id) } },
                    onDay = { sheetDayEpoch = it.toEpochDay() },
                    onSchedule = { detailId = it.id },
                    onEvent = { eventDetail = it },
                    schedules = data.schedules,
                    onUpcoming = { upcomingOpen = true },
                    navigate = navigate,
                    modifier = (if (wrapBoard) Modifier.fillMaxWidth() else Modifier.fillMaxSize()).graphicsLayer { alpha = boardAlpha }
                        .then(rememberBoardPageMotion(view, start, theme, data, hidden))
                        .then(if (hidden) Modifier.clearAndSetSemantics { } else Modifier),
                )
                }
                // New schedule on today (or on the shown period when today is not on screen).
                if (!hidden) FloatingActionButton(
                    onClick = { adding = true },
                    containerColor = theme.accent, contentColor = ScheduleColors.readableOn(theme.accent),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) { Icon(Icons.Filled.Add, "일정 추가") }
                if (hidden) Box(
                    Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("펫을 누르고 ‘달력 보이기’를 켜면 달력이 다시 나타납니다", color = theme.muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (data.miniPlayerVisible) MusicBar(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                onOpenPlaylists = { navigate(Route.Playlists) },
                onOpenMusicApps = { navigate(Route.MusicApps) },
            )
        }
        visiblePets.forEach { pet -> key(pet.index) {
            DraggablePet(
            slotIndex = pet.index,
            heightDp = PET_HEIGHT,
            alpha = if (floating) 1f else 0.45f,
            // One tap: the menu bubble (with the "달력 보이기" switch); three taps: straight to the MP3 tool — or, with
            // 수업 녹음 on, three taps start recording and two taps while recording stop and save it.
            onTap = { activePetIndex = pet.index; musicOpen = false; menuOpen = !menuOpen },
            onDoubleTap = { activePetIndex = pet.index; if (data.stt.enabled && Recorder.isRecording) Recorder.stop(context) },
            onTripleTap = {
                if (data.stt.enabled) {
                    activePetIndex = pet.index
                    if (!Recorder.isRecording) { menuOpen = false; musicOpen = false }
                    Recorder.start(context)
                } else { activePetIndex = pet.index; menuOpen = false; musicOpen = !musicOpen }
            },
            onLongPress = { FloatingPet.enable(context) },
            panel = if (pet.index != activePetIndex) null else when {
                musicOpen -> {
                    {
                        PetMusicPanel(
                            onClose = { musicOpen = false },
                            onOpenPlaylists = { musicOpen = false; navigate(Route.Playlists) },
                            onOpenMusicApps = { musicOpen = false; navigate(Route.MusicApps) },
                        )
                    }
                }
                menuOpen -> {
                    {
                        PetMenu(
                            onApp = { pkg -> menuOpen = false; PetApps.launch(context, pkg) },
                            onNotes = onNotes?.let { openNotes -> { menuOpen = false; openNotes() } },
                            onMusic = { menuOpen = false; musicOpen = true },
                            onCalendar = { menuOpen = false; repo.update { it.copy(calendarHidden = false) } },
                            onSettings = { menuOpen = false; navigate(Route.Pets) },
                            calendarShown = !data.calendarHidden,
                            onCalendarShown = { shown -> repo.update { it.copy(calendarHidden = !shown) } },
                            onOpenRecordings = { menuOpen = false; navigate(Route.Recordings) },
                            onOpenTranscript = { id -> menuOpen = false; Recorder.openTranscript.value = id; navigate(Route.Recordings) },
                        )
                    }
                }
                else -> null
            },
            onPanelDismiss = { musicOpen = false; menuOpen = false },
        )
        } }
        // The block being dragged follows the finger (no pointer input: the drag stays with the source block).
        drag.item?.let { item ->
            val density = LocalDensity.current
            val size = drag.chipSize
            Text(
                (if (item.important) "★ " else "") + listOfNotNull(item.rangeLabel.takeIf { it.isNotEmpty() }, item.title).joinToString(" "),
                color = ScheduleColors.readableOn(drag.color),
                fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .offset {
                        val p = drag.pointer - drag.grab - boardOrigin
                        IntOffset(p.x.roundToInt(), p.y.roundToInt())
                    }
                    .width(with(density) { size.width.toDp() }.coerceAtLeast(72.dp))
                    .graphicsLayer { alpha = 0.9f; scaleX = 1.05f; scaleY = 1.05f }
                    .shadow(8.dp, RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp)).background(drag.color)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
    }

    sheetDay?.let { day ->
        val model = days.firstOrNull { it.date == day } ?: run {
            val tasks = ScheduleDates.byDay(data.schedules, day, day)[day].orEmpty()
            DayModel(day, KoreanHolidays.nameOf(day), tasks, ScheduleColors.blockColors(tasks), emptyList())
        }
        DaySheet(
            model = model, theme = theme,
            onDismiss = { sheetDayEpoch = null },
            onSchedule = { sheetDayEpoch = null; detailId = it.id },
            onEvent = { sheetDayEpoch = null; eventDetail = it },
        )
    }
    detailId?.let { ScheduleDetailSheet(it, onDismiss = { detailId = null }) }
    eventDetail?.let { DeviceEventSheet(it, onDismiss = { eventDetail = null }) }
    if (adding) {
        val initial = if (days.any { it.date == today }) today else if (view == AppData.VIEW_MONTH) anchor.withDayOfMonth(1) else start
        ScheduleAddDialog(initial, onDismiss = { adding = false })
    }
    if (upcomingOpen) UpcomingScheduleSheet(
        schedules = data.schedules,
        from = maxOf(today, start.plusDays(count.toLong())),
        today = today,
        theme = theme,
        onDismiss = { upcomingOpen = false },
        onSchedule = { upcomingOpen = false; detailId = it.id },
    )
}
