package com.schedulewidget.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.data.ScheduleItem
import com.schedulewidget.mobile.data.ScheduleDates
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** Full TODO list (desktop "원본 일정 창" equivalent): add/edit/delete/complete/color, search and filters. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val data by Repository.get(context).data.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ListFilter.ALL) }
    var hidePast by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val today = rememberCalendarToday()
    val sorted = remember(data.schedules) {
        data.schedules.sortedWith(compareBy<ScheduleItem>({ it.date ?: LocalDate.MAX }).then(ScheduleDates.dayOrder))
    }
    val shown = remember(sorted, query, filter, hidePast, today) {
        val q = query.trim()
        sorted.filter { s ->
            (q.isEmpty() || s.title.contains(q, ignoreCase = true) || s.memo?.contains(q, ignoreCase = true) == true) &&
                when (filter) {
                    ListFilter.ALL -> true
                    ListFilter.OPEN -> !s.isCompleted
                    ListFilter.DONE -> s.isCompleted
                    ListFilter.IMPORTANT -> s.important
                } &&
                (!hidePast || s.lastDate?.isBefore(today) != true)
        }
    }
    val completed = remember(data.schedules) { data.schedules.filter { it.isCompleted } }
    var detailId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("할 일 목록") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { AddSchedulePanel() }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("제목·메모 검색") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "검색어 지우기") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ListFilter.entries.forEach { f ->
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                        }
                        FilterChip(selected = hidePast, onClick = { hidePast = !hidePast }, label = { Text("지난 일정 숨기기") })
                    }
                    // 완료된 일정 모두 삭제: two taps, with the count (can be undone from the snackbar).
                    if (completed.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                        if (confirmClear) {
                            Button(
                                onClick = { confirmClear = false; ScheduleActions.deleteAll(context, completed) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            ) { Text("정말 ${completed.size}개 삭제") }
                            TextButton(onClick = { confirmClear = false }) { Text("취소") }
                        } else OutlinedButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Outlined.DeleteSweep, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                            Text("완료된 일정 모두 삭제 (${completed.size}개)")
                        }
                    }
                }
            }
            if (shown.isEmpty()) item {
                Text(
                    if (sorted.isEmpty()) "아직 일정이 없습니다." else "조건에 맞는 일정이 없습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
            items(shown, key = { it.id }) { item -> ScheduleCard(item, onOpen = { detailId = item.id }) }
        }
    }
    detailId?.let { ScheduleDetailSheet(it, onDismiss = { detailId = null }) }
}

private enum class ListFilter(val label: String) { ALL("전체"), OPEN("미완료"), DONE("완료"), IMPORTANT("중요") }

@Composable
private fun AddSchedulePanel() {
    val context = LocalContext.current
    val form = rememberScheduleForm(null, LocalDate.now())
    // The day the form's date was last set to automatically (PC _addFormDate).
    var formDay by rememberSaveable { mutableStateOf(LocalDate.now()) }
    fun resetDateToToday() {
        form.date = LocalDate.now()
        form.dateTouched = false
        formDay = form.date
    }
    // PC OnDayMaybeChanged: the form still shows the day it was set to and nothing is typed yet, so move it on to the new
    // today (a task added in the morning isn't dated yesterday). Checked on resume and at midnight while the screen is open.
    fun onDayMaybeChanged() {
        if (formDay != LocalDate.now() && form.date == formDay && form.title.isBlank()) resetDateToToday()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onDayMaybeChanged() }
    LaunchedEffect(formDay) {
        while (true) {
            val now = LocalDateTime.now()
            delay(Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toMillis() + 1_000)
            onDayMaybeChanged()
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp)) {
            ScheduleFormFields(form, quick = true, allowRepeat = true, seriesScope = false)
            Button(
                onClick = {
                    ScheduleActions.saveAll(context, form.build(quick = true).map { null to it })
                    // PC ResetDateToToday after each add.
                    form.clear(LocalDate.now())
                    formDay = form.date
                },
                enabled = form.canSave,
                modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
            ) { Text("추가") }
        }
    }
}

@Composable
private fun ScheduleCard(item: ScheduleItem, onOpen: () -> Unit) {
    val context = LocalContext.current
    val custom = ScheduleColors.parse(item.color)
    val bg = custom ?: MaterialTheme.colorScheme.surface
    val fg = custom?.let { ScheduleColors.readableOn(it) } ?: MaterialTheme.colorScheme.onSurface
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = item.isCompleted,
                onCheckedChange = { ScheduleActions.toggleComplete(context, item) },
                colors = if (custom != null) CheckboxDefaults.colors(checkedColor = fg, checkmarkColor = bg, uncheckedColor = fg) else CheckboxDefaults.colors(),
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null,
                        color = if (item.isCompleted) fg.copy(alpha = 0.6f) else fg,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(4.dp))
                    ScheduleMarks(item, tint = if (custom != null) fg else Color.Unspecified, size = 16)
                }
                val holiday = item.date?.let { KoreanHolidays.nameOf(it) }
                Text(
                    listOfNotNull(
                        item.date?.format(KoreanFullDateFormat) ?: item.period,
                        item.rangeLabel.takeIf { it.isNotEmpty() }, item.time, holiday,
                    ).joinToString(" · "),
                    fontSize = 13.sp, color = fg.copy(alpha = 0.75f),
                )
                item.memo?.takeIf { it.isNotBlank() }?.let { memo ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.Notes, "메모", Modifier.size(14.dp), tint = fg.copy(alpha = 0.6f))
                        Spacer(Modifier.width(4.dp))
                        Text(memo.lines().first(), fontSize = 12.sp, color = fg.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            DdayBadge(item)
        }
    }
}
