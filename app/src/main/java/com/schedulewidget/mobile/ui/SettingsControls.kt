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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MiniFlipEffectRow(value: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("넘김 애니메이션", style = MaterialTheme.typography.bodyLarge)
        Text("달력 기간을 넘길 때 적용할 효과를 고릅니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            (listOf(0 to "없음") + (1..6).map { it to "효과 $it" }).forEach { (effect, label) ->
                FilterChip(selected = value == effect, onClick = { onChange(effect) }, label = { Text(label) })
            }
        }
    }
}

/** 설정 > 달력 글자 크기 (PC 폰트 크기): 80–150 % for the calendar board and the pet's calendar, with a live preview. */
@Composable
internal fun CalendarTextScaleRow(data: AppData, onChange: (Int) -> Unit) {
    // Local while dragging; saved once the finger lifts (each save rewrites the data file and refreshes the widgets).
    var draft by remember(data.calendarTextScale) { mutableFloatStateOf(data.calendarTextScale.coerceIn(80, 150).toFloat()) }
    val percent = (draft / 10f).roundToInt() * 10
    val theme = MiniThemes.of(data.miniTheme)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("달력 글자 크기", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("$percent%", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = draft, onValueChange = { draft = it },
            onValueChangeFinished = { onChange(percent) },
            valueRange = 80f..150f, steps = 6,
        )
        // Preview: a day of the board in the current style, at the chosen size.
        CompositionLocalProvider(LocalCalendarTextScale provides percent / 100f) {
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(theme.paper).border(2.dp, theme.frame, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("15", color = theme.weekday, fontSize = calSp(20f), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).background(theme.accent).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val ink = ScheduleColors.readableOn(theme.accent)
                    Text("14:00", color = ink, fontSize = calSp(12f), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text("과제 제출", color = ink, fontSize = calSp(14f))
                    if (data.miniBlockDDayVisible) {
                        Spacer(Modifier.width(6.dp))
                        Text("D-3", color = ink, fontSize = calSp(12f), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * PC InlineSettingsResetButton (two taps, "정말 초기화"): display/behaviour settings back to the AppData() defaults.
 * Schedules, playlists, music apps, Google/Drive sync, pet apps, the chosen character and the floating pet are kept.
 */
@Composable
internal fun SettingsResetRow(onDone: () -> Unit) {
    val context = LocalContext.current
    var armed by remember { mutableStateOf(false) }
    val d = remember { AppData() }
    val view = when (d.calendarView) {
        AppData.VIEW_MONTH -> "월간"
        AppData.VIEW_WEEK -> "주간"
        else -> "${d.miniDayCount}일"
    }
    fun onOff(v: Boolean) = if (v) "켬" else "끔"
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("설정 초기화", style = MaterialTheme.typography.bodyLarge)
        Text(
            "스타일(${MiniThemes.of(d.miniTheme).label}), 보기 방식($view), 사용자 설정 일수(${d.miniDayCount}일), " +
                "일정 블록 D-day 표시(${onOff(d.miniBlockDDayVisible)}), 넘김 애니메이션(${d.miniFlipEffect}), 달력 글자 크기(${d.calendarTextScale}%), " +
                "음악 막대 표시(${onOff(d.miniPlayerVisible)}), 캐릭터 표시(${onOff(d.miniCharacterVisible)}), " +
                "캐릭터 크기(${d.miniCharacterScale}%), 캐릭터 위치, 달력 숨김(해제), 펫 달력 창 크기를 기본값으로 되돌립니다. " +
                "일정, 플레이리스트, 음악 앱, 구글 캘린더·드라이브 연동, 펫 앱, 고른 캐릭터, 화면 위 펫 띄우기는 그대로입니다.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (armed) {
                Button(
                    onClick = {
                        armed = false
                        Repository.get(context).update {
                            it.copy(
                                miniTheme = d.miniTheme,
                                calendarView = d.calendarView,
                                miniDayCount = d.miniDayCount,
                                miniBlockDDayVisible = d.miniBlockDDayVisible,
                                miniFlipEffect = d.miniFlipEffect,
                                calendarTextScale = d.calendarTextScale,
                                miniPlayerVisible = d.miniPlayerVisible,
                                miniCharacterVisible = d.miniCharacterVisible,
                                miniCharacterScale = d.miniCharacterScale,
                                petX = d.petX,
                                petY = d.petY,
                                calendarHidden = d.calendarHidden,
                                petCalendarWidth = d.petCalendarWidth,
                                petCalendarHeight = d.petCalendarHeight,
                            )
                        }
                        onDone()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("정말 초기화") }
                TextButton(onClick = { armed = false }) { Text("취소") }
            } else OutlinedButton(onClick = { armed = true }) {
                Icon(Icons.Outlined.RestartAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("설정 초기화")
            }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
internal fun LinkRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
internal fun ThemeSwatch(theme: MiniTheme, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(10.dp))
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(4.dp)) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(theme.paper)
                .border(if (selected) 4.dp else 3.dp, if (selected) MaterialTheme.colorScheme.primary else theme.frame, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(16.dp).clip(CircleShape).background(theme.top)) }
        Text(theme.label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}
