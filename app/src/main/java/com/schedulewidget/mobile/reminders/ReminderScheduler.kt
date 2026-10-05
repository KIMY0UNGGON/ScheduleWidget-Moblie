package com.schedulewidget.mobile.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.schedulewidget.mobile.R
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.data.ScheduleItem
import com.schedulewidget.mobile.ui.QuickAddActivity
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Deadline reminders on the phone. [run] posts every due, not yet delivered reminder (catch-up included, like the PC's
 * 30-second check), records it in ReminderReceipts["Mobile"] and sets one alarm for the next start time.
 * Called from ScheduleApp whenever schedules/settings change, and from [ReminderReceiver] (alarm, boot, clock change).
 */
object ReminderScheduler {
    const val CHANNEL_ID = "deadline_reminders"
    private const val NOTIFICATION_ID = 1
    private const val TAG_PREFIX = "reminder:"
    private const val TAG_TEST = "reminder-test"

    @Synchronized
    fun run(context: Context) {
        val app = context.applicationContext
        val repo = Repository.get(app)
        val data = repo.data.value
        val settings = data.reminders
        if (!settings.enabled) {
            cancelAlarm(app)
            return
        }
        val now = LocalDateTime.now()
        val canPost = notificationsAllowed(app)
        val delivered = mutableMapOf<String, String>()
        var next: LocalDateTime? = null
        for (item in data.schedules) {
            val start = ReminderRules.startOf(item, settings) ?: continue
            if (ReminderRules.delivered(item, settings)) continue
            if (ReminderRules.isDue(item, settings, now)) {
                // Without notification permission nothing is recorded: it posts once the user allows notifications.
                if (canPost && post(app, item)) delivered[item.id] = ReminderRules.key(item, settings)
            } else if (start.isAfter(now) && (next == null || start.isBefore(next))) {
                next = start
            }
        }
        if (delivered.isNotEmpty()) {
            repo.update { d ->
                d.copy(schedules = d.schedules.map { s ->
                    val key = delivered[s.id]
                    // Only if unchanged meanwhile; the PC's Desktop/Telegram/Kakao receipts stay as they are.
                    if (key != null && ReminderRules.key(s, d.reminders) == key) {
                        s.copy(reminderReceipts = s.reminderReceipts + (ReminderRules.CHANNEL to key))
                    } else s
                })
            }
        }
        cancelStale(app, data.schedules)
        if (next != null) setAlarm(app, next) else cancelAlarm(app)
    }

    fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** A sample notification for the settings card; false when notifications are not allowed. */
    fun postTest(context: Context): Boolean {
        val app = context.applicationContext
        if (!notificationsAllowed(app)) return false
        ensureChannel(app)
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("테스트 알림")
            .setContentText("마감 알림은 이렇게 표시돼요 (D-day)")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        return notify(app, TAG_TEST, notification)
    }

    fun cancelNotification(context: Context, scheduleId: String) {
        NotificationManagerCompat.from(context).cancel(TAG_PREFIX + scheduleId, NOTIFICATION_ID)
    }

    private fun post(context: Context, item: ScheduleItem): Boolean {
        ensureChannel(context)
        // Distinct request codes and data per schedule, so PendingIntents of different schedules never merge.
        val code = item.id.hashCode()
        val open = PendingIntent.getActivity(
            context, code, QuickAddActivity.editIntent(context, item.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val complete = PendingIntent.getBroadcast(
            context, code,
            Intent(context, ReminderReceiver::class.java)
                .setAction(ReminderReceiver.ACTION_COMPLETE)
                .setData("schedulewidget://reminder/${Uri.encode(item.id)}".toUri())
                .putExtra(ReminderReceiver.EXTRA_ID, item.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = ReminderRules.message(item)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(ReminderRules.title(item))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "완료", complete)
            .build()
        return notify(context, TAG_PREFIX + item.id, notification)
    }

    private fun notify(context: Context, tag: String, notification: android.app.Notification): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return runCatching { NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, notification) }.isSuccess
    }

    /** Removes posted reminders whose schedule was completed or deleted in the app (or on the PC, via import). */
    private fun cancelStale(context: Context, schedules: List<ScheduleItem>) {
        val open = schedules.filter { !it.isCompleted }.mapTo(HashSet()) { it.id }
        val manager = context.getSystemService(NotificationManager::class.java)
        runCatching {
            manager.activeNotifications.forEach { n ->
                val tag = n.tag ?: return@forEach
                if (tag.startsWith(TAG_PREFIX) && tag.removePrefix(TAG_PREFIX) !in open) manager.cancel(tag, n.id)
            }
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "마감 알림", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_ALARM),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun setAlarm(context: Context, at: LocalDateTime) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pending = alarmIntent(context)
        // Exact when allowed; otherwise Android may batch it, so it can come a few minutes late.
        if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
            val exact = runCatching { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending) }
            if (exact.isSuccess) return
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
    }

    private fun cancelAlarm(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
    }
}
