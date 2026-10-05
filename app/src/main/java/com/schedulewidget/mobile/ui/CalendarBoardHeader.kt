package com.schedulewidget.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import android.provider.Settings
import kotlin.math.roundToInt
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.semantics.Role
import com.schedulewidget.mobile.data.AppData
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Header(
    theme: MiniTheme, view: String, anchor: LocalDate, start: LocalDate, count: Int, today: LocalDate,
    onMove: (Int) -> Unit, onPickDate: (LocalDate, Boolean) -> Unit, onView: (String, Int?) -> Unit, onTheme: (String) -> Unit,
    onUpcoming: () -> Unit,
    navigate: (Route) -> Unit,
) {
    var themeMenu by remember { mutableStateOf(false) }
    var rangeMenu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var customDialog by remember { mutableStateOf(false) }
    val end = start.plusDays(count - 1L)
    val (unit, label, sub) = when (view) {
        AppData.VIEW_MONTH -> Triple("달", "${anchor.year}년 ${anchor.monthValue}월", "월간")
        AppData.VIEW_WEEK -> Triple("주", "${start.monthValue}.${start.dayOfMonth} — ${end.monthValue}.${end.dayOfMonth}", "${start.year}년 · 주간")
        else -> Triple(
            "${count}일",
            if (count == 1) start.format(DateTimeFormatter.ofPattern("M.d (E)", Locale.KOREAN))
            else "${start.monthValue}.${start.dayOfMonth} — ${end.monthValue}.${end.dayOfMonth}",
            "${start.year}년 · ${count}일 보기",
        )
    }
    val ink = theme.topInk

    Row(
        Modifier.fillMaxWidth().background(theme.top).padding(horizontal = 4.dp, vertical = 6.dp).padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onMove(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 $unit", tint = ink) }
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { themeMenu = true }.padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(theme.label, color = ink, fontSize = 13.sp)
                Icon(Icons.Filled.ArrowDropDown, "스타일", tint = ink, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                MiniThemes.all.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t.label) },
                        leadingIcon = {
                            Box(Modifier.size(20.dp).clip(CircleShape).background(t.paper).border(3.dp, t.frame, CircleShape))
                        },
                        trailingIcon = { if (t.id == theme.id) Icon(Icons.Filled.Check, "선택됨") },
                        onClick = { themeMenu = false; onTheme(t.id) },
                    )
                }
            }
        }
        Box(Modifier.weight(1f)) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { rangeMenu = true }.padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(label, color = ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                Text(sub, color = ink.copy(alpha = 0.65f), fontSize = 11.sp)
            }
            DropdownMenu(expanded = rangeMenu, onDismissRequest = { rangeMenu = false }) {
                DropdownMenuItem(
                    text = { Text("월간") },
                    trailingIcon = { if (view == AppData.VIEW_MONTH) Icon(Icons.Filled.Check, "선택됨") },
                    onClick = { rangeMenu = false; onView(AppData.VIEW_MONTH, null) },
                )
                DropdownMenuItem(
                    text = { Text("주간 (월~일, 세로)") },
                    trailingIcon = { if (view == AppData.VIEW_WEEK) Icon(Icons.Filled.Check, "선택됨") },
                    onClick = { rangeMenu = false; onView(AppData.VIEW_WEEK, null) },
                )
                DropdownMenuItem(
                    text = { Text(if (view == AppData.VIEW_CUSTOM) "사용자 설정 (${count}일)…" else "사용자 설정…") },
                    trailingIcon = { if (view == AppData.VIEW_CUSTOM) Icon(Icons.Filled.Check, "선택됨") },
                    onClick = { rangeMenu = false; customDialog = true },
                )
                HorizontalDivider()
                DropdownMenuItem(text = { Text("오늘로") }, onClick = { rangeMenu = false; onPickDate(today, true) })
                DropdownMenuItem(text = { Text("다음 일정") }, onClick = { rangeMenu = false; onUpcoming() })
                DropdownMenuItem(text = { Text("날짜 선택…") }, onClick = { rangeMenu = false; picking = true })
            }
        }
        IconButton(onClick = { navigate(Route.Settings) }) { Icon(Icons.Outlined.Settings, "설정", tint = ink) }
        IconButton(onClick = { navigate(Route.Schedules) }) { Icon(Icons.Outlined.Checklist, "TODO 목록", tint = ink) }
        Box(
            Modifier.size(48.dp).combinedClickable(
                role = Role.Button, onClick = { onMove(1) }, onLongClick = onUpcoming,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 $unit · 길게 눌러 다음 일정", tint = ink)
        }
    }
    if (picking) DatePickDialog(anchor, onDismiss = { picking = false }, onPick = { onPickDate(it, false) })
    if (customDialog) CustomRangeDialog(
        initial = if (view == AppData.VIEW_CUSTOM) count else 3,
        onDismiss = { customDialog = false },
        onConfirm = { n -> customDialog = false; onView(AppData.VIEW_CUSTOM, n) },
    )
}

/** Picks how many days the custom view shows (1–14), starting from the chosen date. */
@Composable
internal fun CustomRangeDialog(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var days by remember { mutableFloatStateOf(initial.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("사용자 설정 간격") },
        text = {
            Column {
                Text("${days.roundToInt()}일씩 보기", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = days, onValueChange = { days = it },
                    valueRange = 1f..AppData.MAX_CUSTOM_DAYS.toFloat(), steps = AppData.MAX_CUSTOM_DAYS - 2,
                )
                Text("선택한 날짜부터 가로로 나란히 표시하고, 화살표는 이 간격만큼 이동합니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(days.roundToInt()) }) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
