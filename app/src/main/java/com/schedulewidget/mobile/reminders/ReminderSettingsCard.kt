package com.schedulewidget.mobile.reminders

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.Repository
import java.util.Locale

/** Settings section "마감 알림": on/off, days before, time of day, permission hints and a test notification. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val data by repo.data.collectAsStateWithLifecycle()
    val settings = data.reminders
    // Permissions can change in system settings; re-read them whenever the screen comes back.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val canPost = remember(resumed) { ReminderScheduler.notificationsAllowed(context) }
    val canExact = remember(resumed) { ReminderScheduler.canExact(context) }
    // Notifications allowed in system settings: post what came due while they were blocked.
    LaunchedEffect(canPost) { if (canPost) ReminderScheduler.run(context) }
    var askedPermission by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        askedPermission = true
        resumed++
        // Reminders that came due while notifications were blocked post now.
        if (granted) ReminderScheduler.run(context)
    }
    fun requestPermission() {
        if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else openNotificationSettings(context)
    }
    fun setEnabled(on: Boolean) {
        repo.update { it.copy(reminders = it.reminders.copy(enabled = on)) }
        if (on && !ReminderScheduler.notificationsAllowed(context) && Build.VERSION.SDK_INT >= 33) requestPermission()
    }

    Column(modifier.fillMaxWidth()) {
        Text(
            "마감 알림", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        ListItem(
            headlineContent = { Text("켜기") },
            supportingContent = {
                Text(if (settings.enabled) ReminderRules.describe(settings) else "완료하지 않은 할 일의 마감이 다가오면 알려줘요")
            },
            trailingContent = { Switch(checked = settings.enabled, onCheckedChange = { setEnabled(it) }) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { setEnabled(!settings.enabled) },
        )
        if (settings.enabled) {
            ListItem(
                headlineContent = { Text("며칠 전") },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val days = settings.daysBefore.coerceIn(0, 30)
                        IconButton(onClick = { setDays(repo, days - 1) }, enabled = days > 0) { Icon(Icons.Filled.Remove, "하루 줄이기") }
                        Text(
                            if (days == 0) "당일" else "${days}일 전",
                            textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 56.dp),
                        )
                        IconButton(onClick = { setDays(repo, days + 1) }, enabled = days < 30) { Icon(Icons.Filled.Add, "하루 늘리기") }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text("알림 시각") },
                supportingContent = { Text("시간이 정해진 일정은 이 시각 대신 일정의 시간에 알려줘요") },
                trailingContent = {
                    Text(
                        String.format(Locale.ROOT, "%02d:%02d", settings.hour, settings.minute),
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { pickTime = true },
            )
            if (!canPost) Hint(
                if (askedPermission || Build.VERSION.SDK_INT < 33) "알림이 꺼져 있어 마감 알림을 보낼 수 없어요. 설정에서 알림을 허용해 주세요."
                else "마감 알림을 받으려면 알림 권한이 필요해요.",
                "알림 설정 열기",
            ) { if (askedPermission || Build.VERSION.SDK_INT < 33) openNotificationSettings(context) else requestPermission() }
            if (!canExact) Hint("정확한 알람이 허용되지 않아 알림이 몇 분 늦게 올 수 있어요.", "정확한 알람 허용") {
                openExactAlarmSettings(context)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = {
                if (ReminderScheduler.postTest(context)) return@OutlinedButton
                if (Build.VERSION.SDK_INT >= 33 && !askedPermission) requestPermission()
                else {
                    Toast.makeText(context, "알림이 꺼져 있어요. 설정에서 허용해 주세요.", Toast.LENGTH_SHORT).show()
                    openNotificationSettings(context)
                }
            }) { Text("테스트 알림") }
        }
    }

    if (pickTime) {
        val state = rememberTimePickerState(settings.hour.coerceIn(0, 23), settings.minute.coerceIn(0, 59), is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text("알림 시각") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    pickTime = false
                    repo.update { it.copy(reminders = it.reminders.copy(hour = state.hour, minute = state.minute)) }
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun Hint(text: String, action: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onClick, modifier = Modifier.align(Alignment.End)) { Text(action) }
    }
}

private fun setDays(repo: Repository, days: Int) =
    repo.update { it.copy(reminders = it.reminders.copy(daysBefore = days.coerceIn(0, 30))) }

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < 31) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
