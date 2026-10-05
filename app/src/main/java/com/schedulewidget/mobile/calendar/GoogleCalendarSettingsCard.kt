package com.schedulewidget.mobile.calendar

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.data.AppData
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Kept at screen scope so a lazy item can leave the viewport while authorization is running. */
internal class GoogleCalendarSettingsState {
    val busy = mutableStateOf(false)
    val status = mutableStateOf<String?>(null)
    val confirmSignOut = mutableStateOf(false)
}

internal data class GoogleCalendarSettingsActions(
    val onEnabledChange: (Boolean) -> Unit,
    val onSync: () -> Unit,
    val onSignOut: () -> Unit,
)

@Composable
internal fun GoogleCalendarSettingsCard(data: AppData, state: GoogleCalendarSettingsState, actions: GoogleCalendarSettingsActions) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var googleBusy by state.busy
    var googleStatus by state.status
    var confirmSignOut by state.confirmSignOut

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("구글 캘린더 연동 (PC와 같음)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "할 일과 구글 캘린더(기본 캘린더)를 양방향으로 맞춥니다. PC 앱의 ‘구글 캘린더 연동’과 같은 계정을 고르면 " +
                            "PC·휴대폰·구글 캘린더가 같은 일정을 보여 줍니다.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = data.googleCalendar.enabled, enabled = !googleBusy, onCheckedChange = actions.onEnabledChange)
            }
            if (data.googleCalendar.enabled) {
                data.googleCalendar.account?.let { Text("계정: $it", style = MaterialTheme.typography.bodySmall) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = actions.onSync, enabled = !googleBusy) { Text("지금 동기화") }
                    if (googleBusy) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
                }
                Text(
                    "일정을 바꾸면 몇 초 뒤, 그리고 15분마다 자동으로 맞춥니다. 켜 둔 동안 아래 ‘자동 저장’은 쓰지 않습니다(중복 방지).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            data.googleCalendar.lastSync.takeIf { it > 0 }?.let { at ->
                Text(
                    "마지막 동기화: " + Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(LAST_SYNC_FORMAT),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val linked = data.googleCalendar.let { google ->
                google.enabled || google.account != null || google.syncedEventIds.isNotEmpty() || google.ownedEventIds.isNotEmpty() ||
                    google.hiddenEvents.isNotEmpty() || data.schedules.any { !it.googleEventId.isNullOrEmpty() }
            }
            if (linked) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (confirmSignOut) {
                        Button(
                            onClick = actions.onSignOut, enabled = !googleBusy,
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Text("정말 해제") }
                        TextButton(onClick = { confirmSignOut = false }) { Text("취소") }
                    } else OutlinedButton(onClick = { confirmSignOut = true }, enabled = !googleBusy) { Text("연결 해제") }
                }
                if (confirmSignOut) Text(
                    "동기화를 끄고 이 기기의 계정·일정 연결 정보를 지웁니다. 구글 일정과 Google 권한은 그대로 남습니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "연결 해제는 이 기기의 연결 정보만 지웁니다. Google 권한을 취소하려면 Google 계정에서 별도로 관리하세요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                runCatching { uriHandler.openUri("https://myaccount.google.com/connections") }
                    .onFailure { toast("Google 계정 권한 페이지를 열지 못했습니다.") }
            }) { Text("Google 계정 권한 관리") }
            (googleStatus ?: data.googleCalendar.lastError)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private val LAST_SYNC_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")
