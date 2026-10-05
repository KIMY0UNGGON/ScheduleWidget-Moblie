package com.schedulewidget.mobile.ui

import com.schedulewidget.mobile.data.ScheduleDates
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.schedulewidget.mobile.calendar.DeviceCalendar
import com.schedulewidget.mobile.calendar.GoogleCalendarSync
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.data.ScheduleItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Schedule CRUD shared by every screen; keeps the device calendar in sync (DeviceCalendar.onScheduleChanged). */
internal object ScheduleActions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun save(context: Context, before: ScheduleItem?, after: ScheduleItem) {
        val repo = Repository.get(context)
        repo.update { d ->
            d.copy(schedules = if (d.schedules.any { it.id == after.id }) d.schedules.map { if (it.id == after.id) after else it } else d.schedules + after)
        }
        sync(context.applicationContext, before, after)
        GoogleCalendarSync.requestSoon(context)
    }

    /** Several adds / edits in one data write (a new repeat, an edit of a whole series), then each one's sync hooks. */
    fun saveAll(context: Context, changes: List<Pair<ScheduleItem?, ScheduleItem>>) {
        if (changes.isEmpty()) return
        if (changes.size == 1) return save(context, changes[0].first, changes[0].second)
        val byId = changes.associate { it.second.id to it.second }
        Repository.get(context).update { d ->
            val known = d.schedules.map { it.id }.toSet()
            d.copy(schedules = d.schedules.map { byId[it.id] ?: it } + changes.map { it.second }.filter { it.id !in known })
        }
        val app = context.applicationContext
        changes.forEach { (before, after) -> sync(app, before, after) }
        GoogleCalendarSync.requestSoon(context)
    }

    /** Edit of [before] into [after]; for a series item with [scope] FOLLOWING/ALL the other members get the same change. */
    fun saveEdit(context: Context, before: ScheduleItem, after: ScheduleItem, scope: SeriesScope = SeriesScope.THIS) {
        val all = Repository.get(context).data.value.schedules
        saveAll(context, ScheduleSeries.propagate(all, before, after, scope))
    }

    /** Saves an edit dialog: a chosen 반복 turns the schedule into a series from its date; otherwise [saveEdit]. */
    fun saveEditFromForm(context: Context, item: ScheduleItem, form: ScheduleFormState) {
        val edited = form.applyTo(item)
        if (item.seriesId == null && form.repeat != Repeat.NONE) {
            val series = ScheduleSeries.expand(edited, form.repeat, form.until ?: form.repeat.defaultUntil(form.date))
            saveAll(context, listOf(item to series[0]) + series.drop(1).map { null to it })
        } else saveEdit(context, item, edited, form.scope)
    }

    fun toggleComplete(context: Context, item: ScheduleItem) = save(context, item, item.copy(isCompleted = !item.isCompleted))

    fun setColor(context: Context, item: ScheduleItem, color: String?, scope: SeriesScope = SeriesScope.THIS) =
        saveEdit(context, item, item.copy(color = color), scope)

    fun setImportant(context: Context, item: ScheduleItem, important: Boolean) = save(context, item, item.copy(important = important))

    /** Moves the schedule to [date], keeping its time (only this one, also inside a series). */
    fun move(context: Context, item: ScheduleItem, date: LocalDate) {
        if (item.date == date) return
        save(context, item, item.movedTo(date))
    }

    fun delete(context: Context, item: ScheduleItem, undoable: Boolean = true) = deleteAll(context, listOf(item), undoable)

    /** Deletes [items] in one data write; [undoable] offers "되돌리기" (MainActivity's snackbar) for them. */
    fun deleteAll(context: Context, items: List<ScheduleItem>, undoable: Boolean = true) {
        if (items.isEmpty()) return
        val ids = items.map { it.id }.toSet()
        Repository.get(context).update { d -> d.copy(schedules = d.schedules.filterNot { it.id in ids }) }
        val app = context.applicationContext
        scope.launch { items.forEach { item -> runCatching { DeviceCalendar.onScheduleChanged(app, item, null) } } }
        GoogleCalendarSync.requestSoon(context)
        _lastDeleted.value = if (undoable) items else null
    }

    /** Deletes [item] and, for a series, the members [scope] covers. */
    fun deleteSeries(context: Context, item: ScheduleItem, scope: SeriesScope, undoable: Boolean = true) =
        deleteAll(context, ScheduleSeries.members(Repository.get(context).data.value.schedules, item, scope), undoable)

    // The last deletion that can still be undone; kept in memory only.
    private val _lastDeleted = MutableStateFlow<List<ScheduleItem>?>(null)
    val lastDeleted: StateFlow<List<ScheduleItem>?> = _lastDeleted.asStateFlow()

    fun forgetDeleted() { _lastDeleted.value = null }

    /** Puts the last deleted schedules back; the sync hooks create their calendar entries again. */
    fun undoDelete(context: Context) {
        val items = _lastDeleted.value ?: return
        _lastDeleted.value = null
        // The device-calendar event was removed with the schedule; a new one is pushed.
        saveAll(context, items.map { null to it.copy(eventId = null) })
    }

    /** Within a day: open before done, important first, then by time. */
    val dayOrder: Comparator<ScheduleItem> = ScheduleDates.dayOrder

    /**
     * The PC's "이 일정 보내기" text (MainWindow.Features ShareSchedule_Click): "Title\n마감: yyyy-MM-dd (D-n)", plus the
     * memo (mobile only) on the following lines.
     */
    fun shareText(item: ScheduleItem): String =
        item.title + "\n마감: " + item.periodText + (item.time?.let { " $it" } ?: "") + " (" + item.dDay() + ")" + (item.memo?.takeIf { it.isNotBlank() }?.let { "\n" + it.trim() } ?: "")

    /** Sends the schedule as plain text through the system share sheet (messenger, mail, notes ...). */
    fun share(context: Context, item: ScheduleItem) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText(item))
        val chooser = Intent.createChooser(send, "일정 보내기")
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }

    private fun sync(app: Context, before: ScheduleItem?, after: ScheduleItem) = scope.launch {
        val synced = runCatching { DeviceCalendar.onScheduleChanged(app, before, after) }.getOrNull() ?: return@launch
        if (synced.eventId == after.eventId) return@launch
        Repository.get(app).update { d ->
            d.copy(schedules = d.schedules.map { if (it.id == synced.id) it.copy(eventId = synced.eventId) else it })
        }
    }
}

/**
 * Accepts H:mm / HH:mm (also "." or space as separator) and bare digits (9 → 09:00, 930 → 09:30, 1430 → 14:30),
 * since the time field uses the number keypad, which has no ":" on many phones. Returns HH:mm, or null when invalid.
 */
internal fun normalizeTime(input: String): String? {
    val s = input.trim()
    val m = Regex("""^(\d{1,2})[:. ](\d{2})$""").find(s)
    val (h, min) = when {
        m != null -> m.groupValues[1].toInt() to m.groupValues[2].toInt()
        s.matches(Regex("""\d{1,2}""")) -> s.toInt() to 0
        s.matches(Regex("""\d{3,4}""")) -> s.dropLast(2).toInt() to s.takeLast(2).toInt()
        else -> return null
    }
    return if (h in 0..23 && min in 0..59) "%02d:%02d".format(h, min) else null
}
