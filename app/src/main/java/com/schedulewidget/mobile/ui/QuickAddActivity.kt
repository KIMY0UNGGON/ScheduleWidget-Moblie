package com.schedulewidget.mobile.ui

import com.schedulewidget.mobile.pet.FloatingPet
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.MainActivity
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.data.ScheduleItem
import java.time.LocalDate

/**
 * Small schedule dialog opened from the home-screen calendar widget and the floating pet's calendar, so a schedule
 * can be handled without opening the whole app: "일정 추가" for a new one, or — with a schedule id — edit, 완료 and
 * 삭제 for an existing one. "앱 열기" jumps into the app instead.
 */
class QuickAddActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = intent.getLongExtra(EXTRA_DATE, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
            ?.let(LocalDate::ofEpochDay) ?: LocalDate.now()
        val editId = intent.getStringExtra(EXTRA_ID)
        val existing = editId?.let { id -> Repository.get(this).data.value.schedules.firstOrNull { it.id == id } }
        if (editId != null && existing == null) {
            Toast.makeText(this, "이미 삭제된 일정입니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            AppTheme { ScheduleQuickDialog(existing, initial) }
        }
    }

    // The floating pet and its calendar sit above every app; step them aside so they don't cover this dialog.
    override fun onStart() {
        super.onStart()
        FloatingPet.screenVisible("quick", true)
    }

    override fun onStop() {
        if (!isChangingConfigurations) FloatingPet.screenVisible("quick", false)
        super.onStop()
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ScheduleQuickDialog(existing: ScheduleItem?, initial: LocalDate) {
        val form = rememberScheduleForm(existing, initial)
        var confirmDelete by remember { mutableStateOf(false) }
        val inSeries = existing?.seriesId != null
        val danger = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        // Deletes from this dialog cannot be undone (it closes right away); the toast says what happened.
        fun deleteDone(item: ScheduleItem, scope: SeriesScope) {
            ScheduleActions.deleteSeries(this@QuickAddActivity, item, scope, undoable = false)
            toast(if (scope == SeriesScope.THIS) "‘${item.title}’ 일정을 삭제했어요" else "‘${item.title}’ 반복 일정을 삭제했어요")
            finish()
        }

        AlertDialog(
            onDismissRequest = ::finish,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (existing == null) "일정 추가" else "일정", modifier = Modifier.weight(1f))
                    existing?.let { DdayBadge(it) }
                }
            },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScheduleFormFields(
                        form, quick = existing == null,
                        allowRepeat = existing?.seriesId == null, seriesScope = inSeries,
                    )
                    if (existing != null) FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(onClick = {
                            ScheduleActions.toggleComplete(this@QuickAddActivity, existing)
                            toast(if (existing.isCompleted) "‘${existing.title}’ 미완료로 바꿨어요" else "‘${existing.title}’ 완료!")
                            finish()
                        }) {
                            Icon(if (existing.isCompleted) Icons.AutoMirrored.Outlined.Undo else Icons.Filled.Check, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (existing.isCompleted) "미완료" else "완료")
                        }
                        FilledTonalButton(onClick = { ScheduleActions.share(this@QuickAddActivity, existing) }) {
                            Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("공유")
                        }
                        if (confirmDelete && !inSeries) Button(onClick = { deleteDone(existing, SeriesScope.THIS) }, colors = danger) {
                            Text("정말 삭제")
                        }
                        else OutlinedButton(onClick = { confirmDelete = !confirmDelete }) {
                            Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("삭제")
                        }
                    }
                    if (existing != null && confirmDelete && inSeries) FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(SeriesScope.THIS to "이 일정만", SeriesScope.FOLLOWING to "이후 모두", SeriesScope.ALL to "반복 전체").forEach { (scope, label) ->
                            Button(onClick = { deleteDone(existing, scope) }, colors = danger) { Text(label) }
                        }
                    }
                    if (existing != null && confirmDelete) DeleteGoogleNote(existing)
                }
            },
            confirmButton = {
                Row(Modifier.padding(top = 4.dp)) {
                    TextButton(onClick = ::openApp) { Text("앱 열기") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = ::finish) { Text("취소") }
                    TextButton(enabled = form.canSave, onClick = {
                        if (existing == null) {
                            val items = form.build(quick = true)
                            ScheduleActions.saveAll(this@QuickAddActivity, items.map { null to it })
                            toast(if (items.size > 1) "‘${items[0].title}’ 반복 일정 ${items.size}개를 추가했어요" else "‘${items[0].title}’ 일정을 추가했어요")
                        } else {
                            ScheduleActions.saveEditFromForm(this@QuickAddActivity, existing, form)
                            toast("일정을 수정했어요")
                        }
                        finish()
                    }) { Text(if (existing == null) "추가" else "저장") }
                }
            },
        )
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ROUTE, Route.Mini.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    companion object {
        private const val EXTRA_DATE = "date"
        private const val EXTRA_ID = "schedule_id"

        fun intent(context: Context, date: LocalDate = LocalDate.now()): Intent =
            Intent(context, QuickAddActivity::class.java)
                // Distinct data per date so widget PendingIntents for different days don't collapse into one.
                .setData(Uri.parse("schedulewidget://add/${date.toEpochDay()}"))
                .putExtra(EXTRA_DATE, date.toEpochDay())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        /** Edit / complete / delete an existing schedule. */
        fun editIntent(context: Context, scheduleId: String): Intent =
            Intent(context, QuickAddActivity::class.java)
                .setData(Uri.parse("schedulewidget://edit/${Uri.encode(scheduleId)}"))
                .putExtra(EXTRA_ID, scheduleId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
