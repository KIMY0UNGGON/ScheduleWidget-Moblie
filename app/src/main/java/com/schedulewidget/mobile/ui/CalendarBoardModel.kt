package com.schedulewidget.mobile.ui

import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.TextUnit
import com.schedulewidget.mobile.data.AppData
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.schedulewidget.mobile.calendar.DeviceEvent
import com.schedulewidget.mobile.data.ScheduleItem
import java.time.LocalDate

internal data class DayModel(
    val date: LocalDate,
    val holiday: String?,
    val schedules: List<ScheduleItem>,
    val colors: Map<String, Color>,
    val events: List<DeviceEvent>,
)

internal const val PET_HEIGHT = 96
internal fun startOfWeek(d: LocalDate) = d.minusDays((d.dayOfWeek.value - 1).toLong())

/** 설정 > 달력 글자 크기 as a factor (100 % = 1f), clamped to the slider's 80–150 % range. */
internal val AppData.calendarTextFactor: Float get() = calendarTextScale.coerceIn(80, 150) / 100f

/** Calendar text size factor for the board and the pet's calendar (PC 폰트 크기). */
internal val LocalCalendarTextScale = staticCompositionLocalOf { 1f }

/** Whether calendar blocks show their D-day (PC MiniBlockDDayVisible, 설정 > 일정 블록에 D-day 표시). */
internal val LocalBlockDDay = staticCompositionLocalOf { true }

/** [value] sp scaled by the calendar text size. */
@Composable
@ReadOnlyComposable
internal fun calSp(value: Float): TextUnit = (value * LocalCalendarTextScale.current).sp
