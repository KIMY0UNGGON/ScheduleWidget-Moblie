package com.schedulewidget.mobile.reminders

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.ui.ScheduleActions

/**
 * The reminder alarm, the notification's "완료" button, and the system events after which the alarm must be set
 * again (reboot, app update, clock or time zone change, exact-alarm permission granted).
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_COMPLETE -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                ReminderScheduler.cancelNotification(context, id)
                val item = Repository.get(context).data.value.schedules.firstOrNull { it.id == id }
                // Same path as completing in the app, so the device/Google calendars follow.
                if (item != null && !item.isCompleted) ScheduleActions.save(context, item, item.copy(isCompleted = true))
            }
            ACTION_ALARM,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> ReminderScheduler.run(context)
        }
    }

    companion object {
        const val ACTION_ALARM = "com.schedulewidget.mobile.action.REMINDER_ALARM"
        const val ACTION_COMPLETE = "com.schedulewidget.mobile.action.REMINDER_COMPLETE"
        const val EXTRA_ID = "scheduleId"
    }
}
