package com.schedulewidget.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.AppData
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.reminders.ReminderSettingsCard
import com.schedulewidget.mobile.record.RecordSettingsCard
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Theme, day count, music bar/pet visibility, desktop JSON import/export, links to other settings screens. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, navigate: (Route) -> Unit) {
    val context = LocalContext.current
    val repo = Repository.get(context)
    val data by repo.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmImport by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    repo.importDesktopJson(text).getOrThrow()
                }.fold(
                    onSuccess = { d ->
                        val tracks = d.music.playlists.flatMap { it.tracks }
                        val relink = tracks.count { it.isDesktopPath }
                        buildString {
                            append("가져왔습니다 · 일정 ${d.schedules.size}개, 플레이리스트 ${d.music.playlists.size}개 (곡 ${tracks.size}개)")
                            if (relink > 0) append("\nPC 경로 곡 ${relink}개는 다시 연결해야 재생됩니다")
                        }
                    },
                    onFailure = { "가져오기 실패: schedules.json 형식이 아닙니다" },
                )
            }
            snackbar.showSnackbar(message)
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter(Charsets.UTF_8).use { it.write(repo.exportJson()) }
                }.isSuccess
            }
            snackbar.showSnackbar(if (ok) "내보냈습니다 · 일정 ${data.schedules.size}개" else "내보내기 실패")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionTitle("미니 달력")
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("보기 방식", style = MaterialTheme.typography.bodyLarge)
                Text("월간은 6주 격자, 주간은 월~일 세로, 1~${AppData.MAX_CUSTOM_DAYS}일은 선택한 날짜부터 가로로 표시합니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // The board's view is calendarView; the day count only matters for the custom view, so picking a
                // number switches to it (before, these chips changed miniDayCount only and the board did not change).
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    FilterChip(
                        selected = data.calendarView == AppData.VIEW_MONTH,
                        onClick = { repo.update { it.copy(calendarView = AppData.VIEW_MONTH) } },
                        label = { Text("월간") },
                    )
                    FilterChip(
                        selected = data.calendarView == AppData.VIEW_WEEK,
                        onClick = { repo.update { it.copy(calendarView = AppData.VIEW_WEEK) } },
                        label = { Text("주간") },
                    )
                    (1..AppData.MAX_CUSTOM_DAYS).forEach { n ->
                        FilterChip(
                            selected = data.calendarView == AppData.VIEW_CUSTOM && data.miniDayCount == n,
                            onClick = { repo.update { it.copy(calendarView = AppData.VIEW_CUSTOM, miniDayCount = n) } },
                            label = { Text("${n}일") },
                        )
                    }
                }
                Text("스타일", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    val current = MiniThemes.of(data.miniTheme)
                    MiniThemes.all.forEach { t ->
                        ThemeSwatch(t, selected = t.id == current.id) { repo.update { it.copy(miniTheme = t.id) } }
                    }
                }
            }
            SwitchRow("일정 블록에 D-day 표시", data.miniBlockDDayVisible) { v -> repo.update { it.copy(miniBlockDDayVisible = v) } }
            CalendarTextScaleRow(data) { v -> repo.update { it.copy(calendarTextScale = v) } }
            SwitchRow("달력 아래 음악 막대 표시", data.miniPlayerVisible) { v -> repo.update { it.copy(miniPlayerVisible = v) } }
            SwitchRow("캐릭터 표시", data.miniCharacterVisible) { v -> repo.update { it.copy(miniCharacterVisible = v) } }
            MiniFlipEffectRow(data.miniFlipEffect) { value -> repo.update { it.copy(miniFlipEffect = value) } }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // Draws its own "마감 알림" section title.
            ReminderSettingsCard(Modifier.fillMaxWidth())

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // Draws its own "수업 녹음" section title.
            RecordSettingsCard(onOpenRecordings = { navigate(Route.Recordings) }, modifier = Modifier.fillMaxWidth())

            // notes: settings section "노트" (draws its own title; notes/library/NotesSettingsCard.kt).
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            com.schedulewidget.mobile.notes.library.NotesSettingsCard(Modifier.fillMaxWidth())

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("연동")
            LinkRow(Icons.Outlined.CalendarMonth, "구글/기기 캘린더 연동", "기기 캘린더 일정을 달력에 표시하고 일정을 내보냅니다") { navigate(Route.CalendarSync) }
            LinkRow(Icons.Outlined.LibraryMusic, "음악 앱 편집", "음악 막대에서 조작할 앱을 고릅니다") { navigate(Route.MusicApps) }
            LinkRow(Icons.AutoMirrored.Outlined.QueueMusic, "플레이리스트", "음악 파일과 YouTube 링크") { navigate(Route.Playlists) }
            LinkRow(Icons.Outlined.Pets, "캐릭터 설정", "캐릭터 선택, 동작, 크기") { navigate(Route.Pets) }
            LinkRow(Icons.Outlined.Checklist, "할 일 목록", "일정 추가·수정·삭제") { navigate(Route.Schedules) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("데이터")
            LinkRow(Icons.Outlined.PrivacyTip, "개인정보", "개인정보처리방침과 문서 업로드 허용 관리") { navigate(Route.Privacy) }
            LinkRow(Icons.Outlined.FileDownload, "PC에서 가져오기", "데스크톱 앱의 schedules.json을 불러옵니다") { confirmImport = true }
            LinkRow(Icons.Outlined.FileUpload, "내보내기", "schedules.json으로 저장합니다 (PC 앱에서 사용 가능)") { exportLauncher.launch("schedules.json") }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingsResetRow { scope.launch { snackbar.showSnackbar("표시 설정을 기본값으로 되돌렸습니다") } }
        }
    }

    if (confirmImport) AlertDialog(
        onDismissRequest = { confirmImport = false },
        title = { Text("PC에서 가져오기") },
        text = { Text("현재 일정과 플레이리스트가 가져온 파일의 내용으로 바뀝니다. 구글 캘린더 자동 동기화는 해제됩니다. 가져온 일정을 확인한 뒤 다시 연결해 주세요.") },
        confirmButton = {
            TextButton(onClick = {
                confirmImport = false
                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }) { Text("파일 선택") }
        },
        dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("취소") } },
    )
}
