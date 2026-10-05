package com.schedulewidget.mobile.ui

import com.schedulewidget.mobile.pet.TypingReaction
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.data.ScheduleItem
import java.time.LocalDate

/** Title + date + optional time fields, used by the add panel and the edit dialog. */
@Composable
internal fun ScheduleFields(
    title: String, onTitle: (String) -> Unit,
    date: LocalDate, onDate: (LocalDate) -> Unit,
    timeOn: Boolean, onTimeOn: (Boolean) -> Unit,
    time: String, onTime: (String) -> Unit,
    timeError: Boolean,
    titleHint: String? = null,
) {
    var picking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = title, onValueChange = { if (it != title) TypingReaction.notifyInput(); onTitle(it) }, singleLine = true,
            label = { Text("할 일") }, modifier = Modifier.fillMaxWidth(),
            // Quick entry: what the title's date / time words will set when the schedule is added.
            supportingText = titleHint?.let { { Text(it, color = MaterialTheme.colorScheme.primary) } },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { picking = true }) {
                Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(date.format(KoreanFullDateFormat))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = timeOn, onCheckedChange = onTimeOn)
            Spacer(Modifier.width(8.dp))
            Text("시간 지정", Modifier.padding(end = 12.dp))
            if (timeOn) OutlinedTextField(
                value = time, onValueChange = { if (it != time) TypingReaction.notifyInput(); onTime(it) }, singleLine = true, isError = timeError,
                placeholder = { Text("HH:mm") },
                supportingText = if (timeError) ({ Text("예: 9:30, 0930, 14") }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(130.dp),
            )
        }
    }
    if (picking) DatePickDialog(date, onDismiss = { picking = false }, onPick = onDate)
}

@Composable
internal fun ScheduleEditorDialog(item: ScheduleItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val form = rememberScheduleForm(item, LocalDate.now())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("일정 수정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ScheduleFormFields(form, quick = false, allowRepeat = item.seriesId == null, seriesScope = item.seriesId != null)
            }
        },
        confirmButton = {
            TextButton(
                enabled = form.canSave,
                onClick = {
                    ScheduleActions.saveEditFromForm(context, item, form)
                    onDismiss()
                },
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** New schedule on any date: the main screen's + button. */
@Composable
internal fun ScheduleAddDialog(initialDate: LocalDate, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val form = rememberScheduleForm(null, initialDate)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("일정 추가") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ScheduleFormFields(form, quick = true, allowRepeat = true, seriesScope = false)
            }
        },
        confirmButton = {
            TextButton(
                enabled = form.canSave,
                onClick = {
                    ScheduleActions.saveAll(context, form.build(quick = true).map { null to it })
                    onDismiss()
                },
            ) { Text("추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
