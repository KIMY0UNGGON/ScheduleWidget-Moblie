package com.schedulewidget.mobile.calendar

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.schedulewidget.mobile.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Kept at screen scope so a lazy item can leave the viewport while authorization is running. */
internal class GoogleCalendarSettingsState {
    val busy = mutableStateOf(false)
    val status = mutableStateOf<String?>(null)
    val confirmSignOut = mutableStateOf(false)
}

@Composable
internal fun GoogleCalendarSettingsCard(data: AppData, repo: Repository, scope: CoroutineScope, state: GoogleCalendarSettingsState) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val uriHandler = LocalUriHandler.current
    var googleBusy by state.busy
    var googleStatus by state.status
    var confirmSignOut by state.confirmSignOut

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun disableGoogle(msg: String?) {
        googleStatus = msg
        repo.update { it.copy(googleCalendar = it.googleCalendar.copy(enabled = false)) }
        GoogleCalendarSync.schedulePeriodic(context)
    }

    fun runGoogle(token: String) {
        scope.launch {
            googleBusy = true
            googleStatus = "구글 캘린더와 맞추는 중…"
            runCatching { GoogleCalendarSync.sync(context, token) }
                .onSuccess { o ->
                    GoogleCalendarSync.schedulePeriodic(context)
                    if (o.more) GoogleCalendarSync.requestMore(context)
                    googleStatus = "동기화 완료 · 가져옴 ${o.added} · 보냄 ${o.sent} · 바뀜 ${o.updated}" +
                        (if (o.deleted > 0) " · 구글에서 삭제 ${o.deleted}" else "") +
                        (if (o.refused > 0) " · 구글이 받지 않음 ${o.refused}" else "") +
                        (if (o.more) " · 나머지는 곧 이어서 보냅니다" else "")
                }
                .onFailure { googleStatus = it.message ?: "동기화하지 못했습니다." }
            googleBusy = false
        }
    }

    val googleConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        googleBusy = false
        val act = activity ?: return@rememberLauncherForActivityResult
        if (r.resultCode != android.app.Activity.RESULT_OK) {
            disableGoogle("로그인이 끝나지 않았습니다.")
            return@rememberLauncherForActivityResult
        }
        when (val auth = GoogleCalendarSync.tokenFrom(act, r.data)) {
            is GoogleCalendarSync.Auth.Token -> runGoogle(auth.value)
            is GoogleCalendarSync.Auth.Failed -> disableGoogle(auth.message)
            is GoogleCalendarSync.Auth.NeedsConsent -> Unit
        }
    }

    fun startGoogle() {
        if (googleBusy) return
        // Busy while asking too, so a second tap can't open a second consent screen.
        googleBusy = true
        scope.launch {
            googleStatus = "구글 계정 확인 중…"
            val auth = GoogleCalendarSync.authorize(activity ?: context)
            googleBusy = false
            when (auth) {
                is GoogleCalendarSync.Auth.Token -> runGoogle(auth.value)
                is GoogleCalendarSync.Auth.NeedsConsent -> {
                    googleBusy = true
                    runCatching { googleConsent.launch(auth.request) }
                        .onFailure { googleBusy = false; disableGoogle(it.message) }
                }
                is GoogleCalendarSync.Auth.Failed -> disableGoogle(auth.message)
            }
        }
    }

    fun signOutGoogle() {
        if (googleBusy) return
        googleBusy = true
        confirmSignOut = false
        scope.launch {
            runCatching { GoogleCalendarSync.signOut(context) }
                .onSuccess { googleStatus = "이 기기의 구글 캘린더 연결 정보를 지웠습니다. 구글 권한과 캘린더 일정은 그대로 남습니다." }
                .onFailure { googleStatus = it.message ?: "연결을 해제하지 못했습니다." }
            googleBusy = false
        }
    }

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
                Switch(checked = data.googleCalendar.enabled, enabled = !googleBusy, onCheckedChange = { enabled ->
                    repo.update { it.copy(googleCalendar = it.googleCalendar.copy(enabled = enabled)) }
                    if (enabled) startGoogle() else { googleStatus = null; GoogleCalendarSync.schedulePeriodic(context) }
                })
            }
            if (data.googleCalendar.enabled) {
                data.googleCalendar.account?.let { Text("계정: $it", style = MaterialTheme.typography.bodySmall) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { startGoogle() }, enabled = !googleBusy) { Text("지금 동기화") }
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
                            onClick = { signOutGoogle() }, enabled = !googleBusy,
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
