package com.schedulewidget.mobile.stt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.schedulewidget.mobile.MainActivity
import com.schedulewidget.mobile.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

// Foreground services that keep model downloads and transcriptions alive while the app is in the background.
// The work itself runs in ModelStore / TranscribeEngine coroutines; these services only hold the
// foreground notification (with a cancel action) and stop themselves once nothing is running.
// If Android refuses to start them (background start restrictions) the work still runs in-process.

internal object SttNotifications {
    const val CHANNEL = "stt_work"
    const val DOWNLOAD_ID = 7301
    const val TRANSCRIBE_ID = 7302

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "받아쓰기 · 모델 다운로드", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            )
        }
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(context: Context, title: String, text: String, percent: Int?, cancel: PendingIntent): Notification =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, percent ?: 0, percent == null)
            .addAction(0, "취소", cancel)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /** Promotes [service] to the foreground; on failure stops it (the work continues in-process) and returns false. */
    fun startForegroundCompat(service: Service, id: Int, notification: Notification, type: Int): Boolean = try {
        ServiceCompat.startForeground(service, id, notification, if (Build.VERSION.SDK_INT >= 34) type else 0)
        true
    } catch (e: Exception) {
        Log.w("SttService", "startForeground failed for ${service.javaClass.simpleName}", e)
        service.stopSelf()
        false
    }

    /** Starts [cls] as a foreground service; false when Android refuses (e.g. started from the background). */
    fun startService(context: Context, cls: Class<out Service>): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, cls))
        true
    } catch (e: Exception) {
        Log.w("SttService", "could not start ${cls.simpleName}", e)
        false
    }
}

/** Shows download progress for every model package being fetched. Type dataSync. */
class SttDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SttNotifications.ensureChannel(this)
        if (!SttNotifications.startForegroundCompat(this, SttNotifications.DOWNLOAD_ID, build(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)) return
        scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            combine(ModelStore.modelStates, ModelStore.diarizationState) { models, diar -> models.values + diar }
                .sample(700)
                .collect { states ->
                    val active = states.filterIsInstance<ModelState.Downloading>()
                    if (active.isEmpty() && !ModelStore.anyDownloading()) {
                        stopSelfCompat()
                    } else {
                        val done = active.sumOf { it.downloadedBytes }
                        val total = active.sumOf { it.totalBytes }.coerceAtLeast(1)
                        getSystemService(NotificationManager::class.java)
                            .notify(SttNotifications.DOWNLOAD_ID, build((done * 100 / total).toInt().takeIf { done < total }))
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            ModelStore.cancelAll()
            stopSelfCompat()
        }
        return START_NOT_STICKY
    }

    // Android 15 caps dataSync services at 6 hours a day; downloads keep going in-process after this.
    override fun onTimeout(startId: Int, fgsType: Int) = stopSelfCompat()

    private fun build(percent: Int?): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, SttDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (percent == null) "압축 푸는 중" else "$percent%"
        return SttNotifications.progress(this, "음성 인식 모델 받는 중", text, percent, cancel)
    }

    private fun stopSelfCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_CANCEL = "com.schedulewidget.mobile.stt.CANCEL_DOWNLOADS"
        internal fun start(context: Context) { SttNotifications.startService(context, SttDownloadService::class.java) }
    }
}

/** Shows transcription progress and offers "취소". Type specialUse (long, CPU-bound, user-initiated work). */
class TranscribeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SttNotifications.ensureChannel(this)
        val started = SttNotifications.startForegroundCompat(
            this, SttNotifications.TRANSCRIBE_ID, build(null, null),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (!started) return
        scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            TranscribeEngine.states.sample(700).collect { states ->
                val running = states.entries.firstOrNull { it.value is TranscribeState.Running }
                if (running == null && !TranscribeEngine.anyActive()) {
                    stopSelfCompat()
                } else if (running != null) {
                    val queued = states.values.count { it is TranscribeState.Running } - 1
                    getSystemService(NotificationManager::class.java)
                        .notify(SttNotifications.TRANSCRIBE_ID, build(running.value as TranscribeState.Running, queued))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            TranscribeEngine.cancelAll()
            stopSelfCompat()
        }
        return START_NOT_STICKY
    }

    private fun build(state: TranscribeState.Running?, queued: Int?): Notification {
        val cancel = PendingIntent.getService(
            this, 2, Intent(this, TranscribeService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stage = state?.stage ?: "준비 중"
        val text = if ((queued ?: 0) > 0) "$stage · 대기 ${queued}개" else stage
        return SttNotifications.progress(this, "녹음 받아쓰는 중", text, state?.let { (it.fraction * 100).toInt() }, cancel)
    }

    private fun stopSelfCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_CANCEL = "com.schedulewidget.mobile.stt.CANCEL_TRANSCRIBE"
        internal fun start(context: Context) { SttNotifications.startService(context, TranscribeService::class.java) }
    }
}
