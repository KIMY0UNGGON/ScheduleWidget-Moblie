package com.schedulewidget.mobile.ui

import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import com.schedulewidget.mobile.widget.WidgetUpdater
import com.schedulewidget.mobile.calendar.DeviceEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.calendar.DeviceCalendar

/**
 * Bottom sheet for an event from a phone calendar (Google / Samsung ...): open it in the calendar app, or delete it
 * here. A repeating event can lose just this day or the whole series.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DeviceEventSheet(event: DeviceEvent, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // "one" = this day only, "all" = the whole event; set by the first tap, carried out by the second.
    var confirm by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    fun failed() {
        error = if (!DeviceCalendar.canWrite(context)) "캘린더 쓰기 권한이 없습니다. 캘린더 설정에서 권한을 허용해 주세요."
        else "바꾸지 못했습니다. 캘린더 앱에서 해 주세요."
    }
    fun remove(mode: String) {
        if (confirm != mode) { confirm = mode; return }
        val ok = if (mode == "one") DeviceCalendar.deleteOccurrence(context, event.id, event.begin)
        else DeviceCalendar.delete(context, event.id)
        if (ok) {
            WidgetUpdater.refreshAll(context)
            onDismiss()
        } else {
            confirm = null
            error = if (!DeviceCalendar.canWrite(context)) "캘린더 쓰기 권한이 없습니다. 캘린더 설정에서 권한을 허용해 주세요."
            else "삭제하지 못했습니다. 캘린더 앱에서 삭제해 주세요."
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                event.color?.let {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(Color(it or 0xFF000000.toInt())))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    event.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
                    textDecoration = if (event.done) TextDecoration.LineThrough else null,
                )
            }
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                listOf(event.date.format(KoreanFullDateFormat), event.time ?: "종일").joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                listOfNotNull(event.calendarName.takeIf { it.isNotBlank() }?.let { "$it 캘린더" }, "반복 일정".takeIf { event.recurring })
                    .joinToString(" · ").ifBlank { "휴대폰 캘린더" },
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(
                Modifier.padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = { DeviceCalendar.open(context, event.date, event.id); onDismiss() }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("캘린더 앱에서 열기")
                }
                if (event.writable) {
                    FilledTonalButton(onClick = { editing = true }) {
                        Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("수정")
                    }
                    FilledTonalButton(onClick = {
                        if (DeviceCalendar.setDone(context, event, !event.done)) { WidgetUpdater.refreshAll(context); onDismiss() } else failed()
                    }) {
                        Icon(if (event.done) Icons.AutoMirrored.Outlined.Undo else Icons.Filled.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (event.done) "미완료" else if (event.recurring) "완료 (반복 전체)" else "완료")
                    }
                    val modes = when {
                        event.canDeleteOne -> listOf("one" to "이 날만 삭제", "all" to "반복 전체 삭제")
                        event.recurring -> listOf("all" to "반복 전체 삭제")
                        else -> listOf("all" to "삭제")
                    }
                    modes.forEach { (mode, label) ->
                        if (confirm == mode) Button(
                            onClick = { remove(mode) },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Text("정말 $label") }
                        else OutlinedButton(onClick = { remove(mode) }) {
                            Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(label)
                        }
                    }
                }
            }
            if (event.writable && event.recurring && !event.canDeleteOne) Text(
                "이 날만 지우려면 캘린더 앱에서 삭제해 주세요.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
            if (!event.writable) Text(
                "읽기 전용 캘린더(공휴일·구독 캘린더 등)라 여기서 삭제할 수 없습니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (editing) DeviceEventEditDialog(event, onDismiss = { editing = false }, onSaved = { ok ->
        editing = false
        if (ok) { WidgetUpdater.refreshAll(context); onDismiss() } else failed()
    })
}

/** Edits a phone-calendar event: title, day and time (a repeating event: the title of the whole series). */
@Composable
private fun DeviceEventEditDialog(event: DeviceEvent, onDismiss: () -> Unit, onSaved: (Boolean) -> Unit) {
    val context = LocalContext.current
    var title by rememberSaveable { mutableStateOf(event.title) }
    var date by rememberSaveable { mutableStateOf(event.date) }
    var timeOn by rememberSaveable { mutableStateOf(event.time != null) }
    var time by rememberSaveable { mutableStateOf(event.time.orEmpty()) }
    val timeError = timeOn && time.isNotBlank() && normalizeTime(time) == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("일정 수정") },
        text = {
            if (event.recurring) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("제목") }, modifier = Modifier.fillMaxWidth())
                Text("반복 일정은 제목만 바꿀 수 있어요 (반복 전체에 적용). 날짜·시간은 캘린더 앱에서 바꿔 주세요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ScheduleFields(title, { title = it }, date, { date = it }, timeOn, { timeOn = it }, time, { time = it }, timeError)
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && !timeError, onClick = {
                val t = if (timeOn) normalizeTime(time) else null
                onSaved(DeviceCalendar.updateEvent(context, event, title.trim(), date, t))
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
