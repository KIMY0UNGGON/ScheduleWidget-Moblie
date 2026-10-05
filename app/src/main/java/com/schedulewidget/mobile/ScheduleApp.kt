package com.schedulewidget.mobile

import android.app.Application
import com.schedulewidget.mobile.apps.MusicHub
import com.schedulewidget.mobile.calendar.DeviceCalendar
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.pet.FloatingPet
import com.schedulewidget.mobile.reminders.ReminderScheduler
import com.schedulewidget.mobile.ui.KoreanHolidays
import com.schedulewidget.mobile.widget.WidgetHolidays
import com.schedulewidget.mobile.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ScheduleApp : Application() {
    private var calendarObserver: (() -> Unit)? = null
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val repo = Repository.get(this)
        MusicHub.init(this)
        WidgetHolidays.provider = { KoreanHolidays.nameOf(it) }
        WidgetUpdater.init(this, repo)
        ensureCalendarObserver()
        FloatingPet.sync(this)
        com.schedulewidget.mobile.calendar.GoogleCalendarSync.schedulePeriodic(this)
        // Deadline reminders: re-plan (and catch up) whenever schedules or reminder settings change, debounced so
        // a burst of edits or a sync sets the alarm once.
        appScope.launch {
            repo.data.map { it.reminders to it.schedules }.distinctUntilChanged().collectLatest {
                delay(500)
                ReminderScheduler.run(this@ScheduleApp)
            }
        }
    }

    /**
     * Refreshes widgets when Google/device calendar events change. Needs calendar permission, which may be granted
     * after launch, so MainActivity calls this again on resume.
     */
    fun ensureCalendarObserver() {
        if (calendarObserver != null || !DeviceCalendar.hasPermission(this)) return
        calendarObserver = DeviceCalendar.observeChanges(this) { WidgetUpdater.refreshAll(this) }
    }
}
