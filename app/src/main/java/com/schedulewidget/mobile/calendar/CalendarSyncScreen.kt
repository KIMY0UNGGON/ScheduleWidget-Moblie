package com.schedulewidget.mobile.calendar

import androidx.activity.compose.LocalActivity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.data.ScheduleItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val data by repo.data.collectAsState()
    val settings = data.calendar
    val scope = rememberCoroutineScope()
    val googleCardState = remember { GoogleCalendarSettingsState() }

    var granted by remember { mutableStateOf(DeviceCalendar.hasPermission(context)) }
    var reload by remember { mutableIntStateOf(0) }
    var calendars by remember { mutableStateOf(emptyList<DeviceCalendarInfo>()) }
    var busy by remember { mutableStateOf(false) }

    val permissionActivity = LocalActivity.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = DeviceCalendar.hasPermission(context)
        reload++
        if (granted) {
            (context.applicationContext as? com.schedulewidget.mobile.ScheduleApp)?.ensureCalendarObserver()
            com.schedulewidget.mobile.widget.WidgetUpdater.refreshAll(context)
        } else if (permissionActivity != null && !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                permissionActivity, android.Manifest.permission.READ_CALENDAR)
        ) {
            // "Don't ask again": the system no longer shows the dialog, so the button would do nothing. Open app settings.
            Toast.makeText(context, "설정 > 권한에서 캘린더를 허용해 주세요.", Toast.LENGTH_LONG).show()
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", context.packageName, null))
                )
            }
        }
    }
    LaunchedEffect(granted, reload) {
        calendars = if (granted) withContext(Dispatchers.IO) { DeviceCalendar.calendars(context) } else emptyList()
    }
    DisposableEffect(granted) {
        val unregister = if (granted) DeviceCalendar.observeChanges(context) { reload++ } else ({})
        onDispose { unregister() }
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun setSync(transform: (com.schedulewidget.mobile.data.CalendarSync) -> com.schedulewidget.mobile.data.CalendarSync) =
        repo.update { it.copy(calendar = transform(it.calendar)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("캘린더 연동") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { GoogleCalendarSettingsCard(data = data, repo = repo, scope = scope, state = googleCardState) }
            item {
                Text(
                    "구글 캘린더와 기기 기본 캘린더(삼성 캘린더 등)에 동기화된 일정을 읽고, 할 일을 캘린더에 저장합니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!granted) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("캘린더 권한이 필요합니다", style = MaterialTheme.typography.titleMedium)
                            Text("기기 캘린더의 일정을 읽고 쓰려면 권한을 허용해 주세요.", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { launcher.launch(DeviceCalendar.PERMISSIONS) }) { Text("권한 허용") }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = { DeviceCalendar.open(context, LocalDate.now()) }, modifier = Modifier.fillMaxWidth()) {
                        Text("캘린더 앱 열기")
                    }
                }
                return@LazyColumn
            }

            item {
                SwitchRow("기기 캘린더 일정 표시", "미니 달력과 위젯에 캘린더 일정을 함께 보여 줍니다.", settings.showEvents) { on ->
                    setSync { it.copy(showEvents = on) }
                }
            }
            item { HorizontalDivider() }
            item { Text("표시할 캘린더", style = MaterialTheme.typography.titleMedium) }
            if (calendars.isEmpty()) {
                item {
                    Text(
                        "기기에 표시되는 캘린더가 없습니다. 설정 > 계정에서 구글 계정의 캘린더 동기화를 켜 주세요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val allIds = calendars.map { it.id }
            val selected = allIds.toSet() - settings.excludedIds.toSet()
            calendars.groupBy { it.account to it.accountType }.forEach { (key, group) ->
                val (account, type) = key
                item(key = "acc-$account-$type") {
                    Text(
                        accountLabel(account, type),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(group, key = { "cal-${it.id}" }) { cal ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            setSync { s -> s.copy(excludedIds = if (cal.id in selected) (s.excludedIds + cal.id).distinct() else s.excludedIds - cal.id) }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = cal.id in selected, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                        Box(Modifier.size(12.dp).background(Color(cal.color), CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text(cal.name, Modifier.weight(1f))
                        if (!cal.writable) Text("읽기 전용", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { HorizontalDivider() }
            item {
                TargetPicker(
                    calendars.filter { it.writable },
                    settings.targetCalendarId,
                ) { id -> setSync { it.copy(targetCalendarId = id) } }
            }
            // With the API sync on, schedules already reach Google; pushing them into the same account's calendar again
            // would duplicate every one (DeviceCalendar.onScheduleChanged skips auto-save then, so show it as off).
            val apiSync = data.googleCalendar.enabled
            val gAccount = data.googleCalendar.account
            val targetIsSynced = apiSync && gAccount != null &&
                calendars.firstOrNull { it.id == settings.targetCalendarId }?.isPrimaryOf(gAccount) == true
            item {
                SwitchRow(
                    "일정 추가/수정 시 자동으로 캘린더에 저장",
                    when {
                        apiSync -> "구글 캘린더 연동을 켜 둔 동안은 쓰지 않습니다(중복 방지)."
                        settings.targetCalendarId == null -> "먼저 저장할 캘린더를 선택해 주세요."
                        else -> "삭제하면 캘린더에서도 지우고, 완료하면 제목 앞에 ✓를 붙입니다."
                    },
                    settings.autoPush && !apiSync,
                    enabled = settings.targetCalendarId != null && !apiSync,
                ) { on -> setSync { it.copy(autoPush = on) } }
            }
            item {
                Button(
                    enabled = !busy && settings.targetCalendarId != null && !targetIsSynced,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val target = settings.targetCalendarId ?: return@Button
                        busy = true
                        scope.launch {
                            val ids = withContext(Dispatchers.IO) {
                                repo.data.value.schedules.filter { it.date != null }
                                    .associate { it.id to DeviceCalendar.push(context, it, target) }
                            }
                            repo.update { d ->
                                d.copy(schedules = d.schedules.map { s -> ids[s.id]?.let { s.copy(eventId = it) } ?: s })
                            }
                            busy = false
                            val ok = ids.values.count { it != null }
                            toast(if (ok == 0 && ids.isNotEmpty()) "캘린더에 저장하지 못했습니다" else "${ok}개 일정을 캘린더에 내보냈습니다")
                        }
                    },
                ) { Text("지금 모든 일정 내보내기") }
            }
            item {
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        busy = true
                        scope.launch {
                            val added = withContext(Dispatchers.IO) { importEvents(context, repo) }
                            busy = false
                            if (added > 0) GoogleCalendarSync.requestSoon(context)
                            toast(if (added == 0) "가져올 새 일정이 없습니다" else "${added}개 일정을 할 일로 가져왔습니다")
                        }
                    },
                ) { Text("기기 캘린더 일정을 할 일로 가져오기") }
            }
            item {
                Text(
                    "오늘부터 30일 동안의 일정을 가져오며, 같은 날짜에 같은 제목의 할 일은 건너뜁니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedButton(onClick = { DeviceCalendar.open(context, LocalDate.now()) }, modifier = Modifier.fillMaxWidth()) {
                    Text("캘린더 앱 열기")
                }
            }
        }
    }
}

private fun accountLabel(account: String, type: String): String = when {
    type == "com.google" -> "Google · $account"
    type == "LOCAL" || account.isBlank() -> "이 기기"
    type.contains("samsung", ignoreCase = true) -> "Samsung · $account"
    else -> account
}

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

/** Creates schedules from the next 30 days of device events. Returns how many were added. */
private fun importEvents(context: android.content.Context, repo: Repository): Int {
    val settings = repo.data.value.calendar
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    // The API-synced Google calendar already is the schedule list; importing its device copy would duplicate it.
    val skip = DeviceCalendar.syncedPrimaryCalendars(context)
    val instances = DeviceCalendar.instances(context, today, today.plusDays(30), settings.excludedIds)
        .filter { it.calendarId !in skip }
    if (instances.isEmpty()) return 0
    var added = 0
    repo.update { d ->
        val pushedIds = d.schedules.mapNotNull { it.eventId }.toSet()
        // Same comparison as DeviceCalendar.events (case-insensitive), so what the widget hides as a duplicate isn't imported.
        val seen = d.schedules.map { it.title.trim().lowercase() to it.period }.toMutableSet()
        val fresh = mutableListOf<ScheduleItem>()
        for (inst in instances) {
            if (inst.eventId in pushedIds) continue
            val range = inst.days(zone)
            val start = range.start
            val title = inst.title.removePrefix(DeviceCalendar.DONE_PREFIX).trim()
            val period = start.toString()
            if (!seen.add(title.lowercase() to period)) continue
            fresh += ScheduleItem(
                title = title,
                period = period,
                endPeriod = ScheduleItem.normalizeEndPeriod(period, range.endInclusive.toString()),
                time = if (inst.allDay) null else Instant.ofEpochMilli(inst.begin).atZone(zone).toLocalTime().format(HHMM),
                color = inst.color?.let { String.format("#%08X", it) },
            )
        }
        added = fresh.size
        if (fresh.isEmpty()) d else d.copy(schedules = d.schedules + fresh)
    }
    return added
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun TargetPicker(writable: List<DeviceCalendarInfo>, targetId: Long?, onPick: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = writable.firstOrNull { it.id == targetId }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("할 일을 저장할 캘린더", style = MaterialTheme.typography.titleMedium)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), enabled = writable.isNotEmpty()) {
                if (current != null) {
                    Box(Modifier.size(10.dp).background(Color(current.color), CircleShape))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        writable.isEmpty() -> "쓰기 가능한 캘린더가 없습니다"
                        current != null -> "${current.name} (${accountLabel(current.account, current.accountType)})"
                        else -> "선택 안 함"
                    },
                    Modifier.weight(1f),
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("선택 안 함") }, onClick = { onPick(null); open = false })
                writable.forEach { cal ->
                    DropdownMenuItem(
                        text = { Text("${cal.name} · ${accountLabel(cal.account, cal.accountType)}") },
                        leadingIcon = { Box(Modifier.size(10.dp).background(Color(cal.color), CircleShape)) },
                        onClick = { onPick(cal.id); open = false },
                    )
                }
            }
        }
    }
}
