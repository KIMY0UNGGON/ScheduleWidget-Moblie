package com.schedulewidget.mobile.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.schedulewidget.mobile.apps.MusicHub
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.data.AppData
import com.schedulewidget.mobile.music.WidgetPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** Registers repo/music listeners that refresh the home-screen widgets. */
@OptIn(FlowPreview::class)
object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dataChanged = Channel<Unit>(Channel.CONFLATED)
    // Device-calendar changes arrive in bursts (one per synced row while Google syncs); coalesce them.
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var initialized = false

    private const val MIDNIGHT_WORK = "widget-midnight-refresh-once"
    private const val OLD_MIDNIGHT_WORK = "widget-midnight-refresh"

    fun init(context: Context, repository: Repository) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        repository.changeListeners += { dataChanged.trySend(Unit) }
        scope.launch {
            var calendar = calendarKey(repository.data.value)
            var theme = repository.data.value.miniTheme
            var playlist = playlistKey(repository.data.value)
            dataChanged.consumeAsFlow().debounce(300).collect {
                val data = repository.data.value
                val nextCalendar = calendarKey(data)
                val nextPlaylist = playlistKey(data)
                if (nextCalendar != calendar) { update(app, CalendarWidget()); calendar = nextCalendar }
                if (data.miniTheme != theme) { update(app, MusicWidget()); theme = data.miniTheme }
                if (nextPlaylist != playlist) { update(app, PlaylistWidget()); playlist = nextPlaylist }
            }
        }
        scope.launch {
            refreshRequests.consumeAsFlow().debounce(1000).collect { runCatching { refreshNow(app) } }
        }
        scope.launch {
            // Playlist widget: highlight the song that is playing now.
            combine(WidgetPlayer.current, WidgetPlayer.state.map { it.isPlaying }) { ref, playing -> ref to playing }
                .distinctUntilChanged()
                .drop(1)
                .debounce(250)
                .collect { update(app, PlaylistWidget()) }
        }
        scope.launch {
            MusicHub.state
                .map { Triple(it.title + '\u0000' + it.artist, it.isPlaying, it.sourceId + '\u0000' + it.sourceLabel) }
                .distinctUntilChanged()
                .drop(1)
                .debounce(250)
                .collect { update(app, MusicWidget()) }
        }
        // KEEP: the process is often started just to run that work; replacing it here would push it to the next day.
        scheduleMidnightRefresh(app, ExistingWorkPolicy.KEEP)
        // The former daily periodic work drifted (flex window) and could run hours after midnight.
        WorkManager.getInstance(app).cancelUniqueWork(OLD_MIDNIGHT_WORK)
    }

    // Character positions, animation choices and sync timestamps don't change a home-screen widget.
    private fun calendarKey(data: AppData): List<Any?> = listOf(
        data.schedules, data.miniDayCount, data.miniTheme, data.miniBlockDDayVisible, data.calendar,
        data.googleCalendar.enabled, data.googleCalendar.account,
    )

    private fun playlistKey(data: AppData) = data.music.copy(volume = 0.0) to data.miniTheme

    /** Re-reads device calendars and redraws every widget (debounced; safe to call in bursts). */
    fun refreshAll(context: Context) {
        if (initialized) refreshRequests.trySend(Unit)
        else scope.launch { refreshNow(context.applicationContext) }
    }

    internal suspend fun refreshNow(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        val tick = System.currentTimeMillis()
        manager.getGlanceIds(CalendarWidget::class.java).forEach { id ->
            runCatching { updateAppWidgetState(context, id) { it[TickKey] = tick } }
        }
        update(context, CalendarWidget())
        update(context, MusicWidget())
        update(context, PlaylistWidget())
    }

    private suspend fun update(context: Context, widget: GlanceAppWidget) {
        val manager = GlanceAppWidgetManager(context)
        manager.getGlanceIds(widget.javaClass).forEach { id -> runCatching { widget.update(context, id) } }
    }

    /**
     * One-shot refresh shortly after the coming midnight so "today" and D-day roll over; the worker schedules the next
     * one. (A 1-day periodic work may run anywhere in its flex window and drifts, so it isn't used.)
     */
    internal fun scheduleMidnightRefresh(context: Context, policy: ExistingWorkPolicy) {
        val now = LocalDateTime.now()
        val nextRun = LocalDate.now().plusDays(1).atTime(0, 1)
        val request = OneTimeWorkRequestBuilder<MidnightRefreshWorker>()
            .setInitialDelay(Duration.between(now, nextRun).toMillis().coerceAtLeast(60_000), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(MIDNIGHT_WORK, policy, request)
    }
}

class MidnightRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { WidgetUpdater.refreshNow(applicationContext) }
        // APPEND_OR_REPLACE: queued after this (running) work instead of cancelling it.
        WidgetUpdater.scheduleMidnightRefresh(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}
