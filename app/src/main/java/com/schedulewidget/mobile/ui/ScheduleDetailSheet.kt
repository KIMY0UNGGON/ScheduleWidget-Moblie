package com.schedulewidget.mobile.ui

import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.IconToggleButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatColorReset
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.calendar.GoogleCalendarSync
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.data.ScheduleItem
import java.time.LocalDate

@Composable
internal fun DdayBadge(item: ScheduleItem, modifier: Modifier = Modifier) {
    val color = if (item.isCompleted) Color(0xFF94A3B8) else ScheduleColors.ddayColor(item)
    Text(
        item.dDay(),
        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorPalette(selected: String?, onPick: (String?) -> Unit) {
    val current = ScheduleColors.parse(selected)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScheduleColors.choices.forEach { hex ->
            val c = ScheduleColors.parse(hex)!!
            val isSel = current == c
            Box(
                Modifier.minimumInteractiveComponentSize().size(32.dp).clip(CircleShape)
                    .selectable(selected = isSel, role = Role.RadioButton) { onPick(hex) }
                    .semantics { contentDescription = "색상 ${hex.removePrefix("#")}" }
                    .background(c)
                    .border(if (isSel) 3.dp else 1.dp, if (isSel) MaterialTheme.colorScheme.primary else Color(0x33000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (isSel) Icon(Icons.Filled.Check, "선택됨", tint = ScheduleColors.readableOn(c), modifier = Modifier.size(18.dp)) }
        }
        OutlinedButton(onClick = { onPick(null) }) {
            Icon(Icons.Outlined.FormatColorReset, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("기본 색")
        }
    }
}

/**
 * Under a 삭제 button waiting for its second press: what deleting does on Google Calendar (PC MiniWindow.ShowDeleteNote).
 * Nothing when the schedule is not linked.
 */
@Composable
internal fun DeleteGoogleNote(item: ScheduleItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val note = remember(item) { GoogleCalendarSync.deleteNote(context, item) } ?: return
    Text(note, modifier = modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

/** Bottom sheet for one schedule: D-day, edit, complete, share, delete (tap twice), card color. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ScheduleDetailSheet(itemId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val data by Repository.get(context).data.collectAsStateWithLifecycle()
    val item = data.schedules.firstOrNull { it.id == itemId }
    LaunchedEffect(item == null) { if (item == null) onDismiss() }
    if (item == null) return
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var pickingMove by remember { mutableStateOf(false) }
    var colorScope by remember { mutableStateOf(SeriesScope.THIS) }
    val inSeries = item.seriesId != null
    val danger = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp)
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScheduleColors.parse(item.color)?.let {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(it))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    item.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
                    textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null,
                )
                IconToggleButton(checked = item.important, onCheckedChange = { ScheduleActions.setImportant(context, item, it) }) {
                    Icon(
                        if (item.important) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        if (item.important) "중요 해제" else "중요 표시",
                        tint = if (item.important) ImportantColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DdayBadge(item)
            }
            Spacer(Modifier.padding(top = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(if (item.isMultiDay) item.periodText else item.date?.format(KoreanFullDateFormat) ?: item.period, item.time).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (inSeries) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.Repeat, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" 반복 일정", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            item.date?.let { KoreanHolidays.nameOf(it) }?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            item.memo?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp),
                )
            }
            FlowRow(
                Modifier.padding(top = 16.dp, bottom = if (confirmDelete || moving) 4.dp else 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = { editing = true }) {
                    Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("수정")
                }
                FilledTonalButton(onClick = { ScheduleActions.toggleComplete(context, item) }) {
                    Icon(if (item.isCompleted) Icons.AutoMirrored.Outlined.Undo else Icons.Filled.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (item.isCompleted) "미완료" else "완료")
                }
                FilledTonalButton(onClick = { ScheduleActions.share(context, item) }) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("공유")
                }
                FilledTonalButton(onClick = { moving = !moving; confirmDelete = false }) {
                    Icon(Icons.Outlined.EventRepeat, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("옮기기")
                }
                if (confirmDelete && !inSeries) Button(
                    onClick = { ScheduleActions.delete(context, item); onDismiss() },
                    colors = danger,
                ) { Text("정말 삭제") }
                else OutlinedButton(onClick = { confirmDelete = !confirmDelete; moving = false }) {
                    Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("삭제")
                }
            }
            if (moving) {
                // Another day, keeping the time (a repeating schedule: only this one moves).
                val today = LocalDate.now()
                FlowRow(
                    Modifier.padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AssistChip(onClick = { ScheduleActions.move(context, item, today.plusDays(1)); moving = false }, label = { Text("내일") })
                    AssistChip(
                        onClick = { ScheduleActions.move(context, item, (item.date ?: today).plusWeeks(1)); moving = false },
                        label = { Text("+1주") },
                    )
                    AssistChip(onClick = { pickingMove = true }, label = { Text("날짜 선택…") })
                }
            }
            if (confirmDelete && inSeries) {
                Text("반복 일정 삭제", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(SeriesScope.THIS to "이 일정만", SeriesScope.FOLLOWING to "이후 모두", SeriesScope.ALL to "반복 전체").forEach { (scope, label) ->
                        Button(onClick = { ScheduleActions.deleteSeries(context, item, scope); onDismiss() }, colors = danger) { Text(label) }
                    }
                    TextButton(onClick = { confirmDelete = false }) { Text("취소") }
                }
            }
            if (confirmDelete) DeleteGoogleNote(item, Modifier.padding(top = 4.dp, bottom = 12.dp))
            Text("카드 색상", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 8.dp))
            if (inSeries) Box(Modifier.padding(bottom = 8.dp)) {
                SeriesScopeChips(colorScope, following = true) { colorScope = it }
            }
            ColorPalette(item.color) { ScheduleActions.setColor(context, item, it, colorScope) }
        }
    }
    if (editing) ScheduleEditorDialog(item, onDismiss = { editing = false })
    if (pickingMove) DatePickDialog(item.date ?: LocalDate.now(), onDismiss = { pickingMove = false }) {
        ScheduleActions.move(context, item, it)
        moving = false
    }
}
