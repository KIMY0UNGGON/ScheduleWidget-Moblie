package com.schedulewidget.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.data.ScheduleItem
import com.schedulewidget.mobile.pet.TypingReaction
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Star colour of 중요 schedules. */
internal val ImportantColor = Color(0xFFF59E0B)

/**
 * Everything the add / edit dialogs ask for: title, date, time, 메모, 중요, 반복 (new schedules) and the series scope
 * (edits of a repeating schedule). Remembers which fields the user set by hand, so quick entry never overrides them.
 */
internal class ScheduleFormState(
    title: String, date: LocalDate, timeOn: Boolean, time: String, memo: String, important: Boolean,
    repeat: Repeat = Repeat.NONE, until: LocalDate? = null,
    dateTouched: Boolean = false, timeTouched: Boolean = false, scope: SeriesScope = SeriesScope.THIS,
) {
    var title by mutableStateOf(title)
    var date by mutableStateOf(date)
    var timeOn by mutableStateOf(timeOn)
    var time by mutableStateOf(time)
    var memo by mutableStateOf(memo)
    var important by mutableStateOf(important)
    var repeat by mutableStateOf(repeat)
    /** 종료일 picked by hand; null = the repeat's default from the start date. */
    var until by mutableStateOf(until)
    var dateTouched by mutableStateOf(dateTouched)
    var timeTouched by mutableStateOf(timeTouched)
    var scope by mutableStateOf(scope)

    val timeError: Boolean get() = timeOn && normalizeTime(time) == null
    val dateError: Boolean get() = repeat != Repeat.NONE && until?.let {
        if (repeat == Repeat.CONTINUOUS) !ScheduleSeries.validRange(date, it) else it < date
    } == true
    val canSave: Boolean get() = title.isNotBlank() && !timeError && !dateError

    /** What quick entry would take from the title (date / time only where the fields were not set by hand), or null. */
    fun quick(today: LocalDate = LocalDate.now()): QuickParse.Result? {
        if (dateTouched && timeTouched) return null
        return QuickParse.parse(title.trim(), today, dates = !dateTouched, times = !timeTouched).takeIf { it.found }
    }

    /** New schedules from the form: one, or one per repeat date (sharing a seriesId). [quick] applies quick entry. */
    fun build(quick: Boolean): List<ScheduleItem> {
        check(canSave) { "일정의 제목, 날짜와 시간을 확인해 주세요." }
        var t = title.trim()
        var d = date
        var tm = if (timeOn) normalizeTime(time) else null
        if (quick) quick()?.let { r ->
            t = r.title
            r.date?.let { d = it }
            r.time?.let { tm = it }
        }
        val first = ScheduleItem(title = t, period = d.toString(), time = tm, memo = memo.trim().ifEmpty { null }, important = important)
        return ScheduleSeries.expand(first, repeat, until ?: repeat.defaultUntil(d))
    }

    /** [item] with the form's fields (the edit dialogs). */
    fun applyTo(item: ScheduleItem): ScheduleItem = item.copy(
        title = title.trim(), period = date.toString(), time = if (timeOn) normalizeTime(time) else null,
        memo = memo.trim().ifEmpty { null }, important = important,
        endPeriod = if (repeat == Repeat.CONTINUOUS) ScheduleItem.normalizeEndPeriod(date.toString(), until?.toString()) else null,
    )

    /** Back to an empty form on [day] (after an add). */
    fun clear(day: LocalDate) {
        title = ""; time = ""; timeOn = false; memo = ""; important = false
        repeat = Repeat.NONE; until = null; date = day; dateTouched = false; timeTouched = false
    }

    companion object {
        fun of(item: ScheduleItem?, date: LocalDate) = ScheduleFormState(
            title = item?.title.orEmpty(), date = item?.date ?: date, timeOn = !item?.time.isNullOrBlank(),
            time = item?.time.orEmpty(), memo = item?.memo.orEmpty(), important = item?.important ?: false,
            repeat = if (item?.isMultiDay == true) Repeat.CONTINUOUS else Repeat.NONE, until = item?.endDate,
        )

        val Saver = listSaver<ScheduleFormState, Any?>(
            save = {
                listOf(it.title, it.date, it.timeOn, it.time, it.memo, it.important, it.repeat.name, it.until,
                    it.dateTouched, it.timeTouched, it.scope.name)
            },
            restore = {
                ScheduleFormState(
                    title = it[0] as String, date = it[1] as LocalDate, timeOn = it[2] as Boolean, time = it[3] as String,
                    memo = it[4] as String, important = it[5] as Boolean, repeat = Repeat.valueOf(it[6] as String),
                    until = it[7] as LocalDate?, dateTouched = it[8] as Boolean, timeTouched = it[9] as Boolean,
                    scope = SeriesScope.valueOf(it[10] as String),
                )
            },
        )
    }
}

@Composable
internal fun rememberScheduleForm(item: ScheduleItem?, date: LocalDate): ScheduleFormState =
    rememberSaveable(saver = ScheduleFormState.Saver) { ScheduleFormState.of(item, date) }.also { form ->
        val today = rememberCalendarToday()
        val followsToday = rememberSaveable { date == LocalDate.now() }
        var previousToday by rememberSaveable { mutableStateOf(today) }
        LaunchedEffect(today) {
            if (item == null && followsToday && form.title.isBlank() && !form.dateTouched && form.date == previousToday)
                form.date = today
            previousToday = today
        }
    }

/**
 * The form's fields. [quick]: show what quick entry reads from the title ("→ 10월 1일 (목) 15:00").
 * [allowRepeat]: the 반복 selector (new schedules, or an edit of a schedule that is not repeating yet).
 * [seriesScope]: "이 일정만 / 이후 모두" for an edit of a repeating schedule.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScheduleFormFields(state: ScheduleFormState, quick: Boolean, allowRepeat: Boolean, seriesScope: Boolean) {
    var pickingUntil by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScheduleFields(
            state.title, { state.title = it },
            state.date, { state.date = it; state.dateTouched = true },
            state.timeOn, { state.timeOn = it; state.timeTouched = true },
            state.time, { state.time = it; state.timeTouched = true },
            state.timeError,
            titleHint = if (quick) state.quick()?.let(QuickParse::hint) else null,
        )
        OutlinedTextField(
            value = state.memo, onValueChange = { if (it != state.memo) TypingReaction.notifyInput(); state.memo = it }, label = { Text("메모") },
            minLines = 1, maxLines = 4, modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconToggleButton(checked = state.important, onCheckedChange = { state.important = it }) {
                Icon(
                    if (state.important) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    if (state.important) "중요 해제" else "중요 표시",
                    tint = if (state.important) ImportantColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("중요")
        }
        if (allowRepeat) {
            Text("날짜 설정", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Repeat.entries.forEach { r ->
                    FilterChip(selected = state.repeat == r, onClick = {
                        state.repeat = r
                        if (r == Repeat.CONTINUOUS) state.dateTouched = true
                    }, label = { Text(r.label) })
                }
            }
            if (state.repeat != Repeat.NONE) {
                val until = state.until ?: state.repeat.defaultUntil(state.date)
                val continuous = state.repeat == Repeat.CONTINUOUS
                val count = if (state.dateError) 0 else if (continuous) ChronoUnit.DAYS.between(state.date, until).toInt() + 1
                    else ScheduleSeries.dates(state.date, state.repeat, until).size
                OutlinedButton(onClick = { pickingUntil = true }) {
                    Icon(Icons.Outlined.Event, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (continuous) "기간 · ${state.date} ~ $until" else "종료일 · " + until.format(KoreanFullDateFormat))
                }
                Text(
                    when {
                        until < state.date -> "종료일이 시작일보다 빠릅니다."
                        state.dateError -> "종료일은 시작일로부터 ${ScheduleItem.MAX_RANGE_DAYS}일 이내로 선택해 주세요."
                        continuous -> "일정 하나가 ${count}일 동안 표시됩니다. 완료·삭제는 전체 기간에 적용됩니다."
                        else -> "일정 ${count}개가 만들어져요" + if (count >= ScheduleSeries.MAX_ITEMS) " (최대 ${ScheduleSeries.MAX_ITEMS}개)" else ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.dateError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pickingUntil) {
                    if (continuous) DateRangePickDialog(state.date, maxOf(state.date, until), onDismiss = { pickingUntil = false }) { start, end ->
                        state.date = start; state.until = end; state.dateTouched = true
                    } else DatePickDialog(until, onDismiss = { pickingUntil = false }, onPick = { state.until = it })
                }
            }
        }
        if (seriesScope) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Repeat, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text("반복 일정 · 바꾼 내용 적용", style = MaterialTheme.typography.labelLarge)
            }
            SeriesScopeChips(state.scope, following = true) { state.scope = it }
            Text(
                "날짜를 바꾸면 이 일정만 옮겨져요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "이 일정만 / 이후 모두" (and "반복 전체" when [all]). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeriesScopeChips(selected: SeriesScope, following: Boolean, all: Boolean = false, onSelect: (SeriesScope) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = selected == SeriesScope.THIS, onClick = { onSelect(SeriesScope.THIS) }, label = { Text("이 일정만") })
        if (following) FilterChip(selected = selected == SeriesScope.FOLLOWING, onClick = { onSelect(SeriesScope.FOLLOWING) }, label = { Text("이후 모두") })
        if (all) FilterChip(selected = selected == SeriesScope.ALL, onClick = { onSelect(SeriesScope.ALL) }, label = { Text("반복 전체") })
    }
}

/** Small ★ / repeat marks for chips and cards. */
@Composable
internal fun ScheduleMarks(item: ScheduleItem, tint: Color, size: Int = 14) {
    if (item.important) Icon(Icons.Filled.Star, "중요", Modifier.size(size.dp), tint = if (tint == Color.Unspecified) ImportantColor else tint)
    if (item.seriesId != null) Icon(Icons.Filled.Repeat, "반복 일정", Modifier.size(size.dp), tint = if (tint == Color.Unspecified) LocalContentColor.current else tint)
}
