package com.schedulewidget.mobile.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.schedulewidget.mobile.MainActivity
import com.schedulewidget.mobile.R
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.stt.ModelState
import com.schedulewidget.mobile.stt.SttModel
import com.schedulewidget.mobile.stt.SttModelManager
import com.schedulewidget.mobile.stt.Transcriber
import com.schedulewidget.mobile.ui.Route
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Records a lecture as a microphone foreground service, so it keeps going with the screen off and the app in the
 * background: [AacRecorder] (AudioRecord + software mic gain + AAC in .m4a). Holds a partial wake lock; stops and
 * saves by itself when storage runs low or the microphone fails. Phone calls / other apps taking audio focus don't
 * stop it (recording needs no focus; during a call Android feeds it silence).
 */
class RecordService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var recorder: AacRecorder? = null
    private var audio: File? = null
    private var meta: RecordingMeta? = null
    private var startedElapsed = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    // Live gain (AppData.stt.micGain), read by the recording thread for every buffer.
    @Volatile private var gain = 1f
    // Times (elapsedRealtime) of recent level windows that had to be limited, for the "too loud" warning.
    private val clips = ArrayDeque<Long>()

    private val spaceCheck = object : Runnable {
        override fun run() {
            val file = audio ?: return
            if ((file.parentFile?.usableSpace ?: Long.MAX_VALUE) < MIN_FREE_BYTES) stopRecording(MESSAGE_FULL)
            else main.postDelayed(this, 10_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Turning 수업 녹음 off in settings (or a data import doing it) stops and saves a running recording.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        current = this
        scope.launch {
            Repository.get(this@RecordService).data.map { it.stt.enabled to it.stt.micGain }.distinctUntilChanged().collect { (on, g) ->
                gain = g.coerceIn(MIN_GAIN, MAX_GAIN) / 100f
                if (!on && recorder != null) stopRecording(null)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (recorder == null) begin(intent.getBooleanExtra(EXTRA_FROM_ACTIVITY, false))
            ACTION_STOP -> if (recorder != null) stopRecording(null) else finishService()
            // Restarted by the system without an intent: nothing to resume (the old file was finalised or is lost).
            else -> if (recorder == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(fromActivity: Boolean) {
        val now = System.currentTimeMillis()
        startedElapsed = SystemClock.elapsedRealtime()
        // startForeground first: Android requires it soon after startForegroundService, whatever happens next.
        val inForeground = runCatching {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(now), type)
        }
        if (inForeground.isFailure) {
            Log.w(TAG, "startForeground refused", inForeground.exceptionOrNull())
            stopSelf()
            // Not allowed from the overlay (Android 14+ rules): retry from a briefly visible activity.
            if (!fromActivity) RecordPermissionActivity.launch(this)
            else toast("녹음을 시작할 수 없어요")
            return
        }
        if (!Recorder.enabled(this)) return finishService()
        if (!Recorder.hasPermission(this)) {
            finishService()
            RecordPermissionActivity.launch(this)
            return
        }
        val file = Recordings.newFile(this, now)
        val started = startRecorder(file)
        if (started == null) {
            file.delete()
            toast("마이크를 사용할 수 없어요. 다른 앱이 녹음 중인지 확인해 주세요")
            finishService()
            return
        }
        recorder = started
        audio = file
        val m = RecordingMeta(id = file.nameWithoutExtension, title = Recordings.defaultTitle(now), createdAt = now, file = file.name)
        meta = m
        // Written now (length 0) so an interrupted recording still shows up with its title.
        Recordings.save(this, m)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScheduleWidget:lecture-recording")
            .apply { setReferenceCounted(false); acquire(MAX_WAKE_MS) }
        Recorder.mutableState.value = RecordingState(isRecording = true, id = m.id, startedAt = now, startedElapsed = startedElapsed)
        main.postDelayed(spaceCheck, 10_000)
    }

    /** Starts [AacRecorder]; falls back to the plain MIC source when the chosen one is not supported. */
    private fun startRecorder(file: File): AacRecorder? {
        val stt = Repository.get(this).data.value.stt
        val quality = RecordingQuality.of(stt.quality)
        val channels = if (quality.stereo && builtInMicCount(this) >= 2) 2 else 1
        gain = stt.micGain.coerceIn(MIN_GAIN, MAX_GAIN) / 100f
        // Stop (and keep what we have) before the disk fills up; MP4 also can't pass 4GB.
        val maxBytes = ((file.parentFile?.usableSpace ?: 0L) - MIN_FREE_BYTES).coerceIn(1L * 1024 * 1024, 3_900L * 1024 * 1024)
        clips.clear()
        val sources = listOf(RecordingMic.of(stt.mic).audioSource(this), MediaRecorder.AudioSource.MIC).distinct()
        for (source in sources) {
            val r = AacRecorder(
                file, source, quality.sampleRate, channels, quality.bitRate,
                gain = { gain },
                maxBytes = maxBytes,
                onLevel = { db, clipped -> main.post { onLevel(db, clipped) } },
                onStoppedItself = { full ->
                    main.post { stopRecording(if (full) MESSAGE_FULL else "녹음 중 마이크에 문제가 생겨 지금까지 녹음한 것을 저장했어요") }
                },
            )
            if (r.start()) return r
        }
        return null
    }

    private fun onLevel(db: Float, clipped: Boolean) {
        if (recorder == null) return
        val now = SystemClock.elapsedRealtime()
        if (clipped) clips.addLast(now)
        while (clips.isNotEmpty() && now - clips.first() > 3_000) clips.removeFirst()
        // "Often": limited in 3+ of the last 30 level windows (3 s).
        Recorder.mutableLevel.value = InputLevel(db, clipping = clips.size >= 3)
    }

    /** Stops, saves and leaves the foreground. [message] replaces the usual "녹음을 저장했어요". */
    fun stopRecording(message: String?) {
        val r = recorder ?: return finishService()
        recorder = null
        main.removeCallbacks(spaceCheck)
        val file = audio
        val m = meta
        val duration = r.durationMs.takeIf { it > 0 } ?: (SystemClock.elapsedRealtime() - startedElapsed)
        val ok = r.stop()
        Recorder.mutableState.value = RecordingState()
        Recorder.mutableLevel.value = InputLevel()
        if (file != null && m != null) {
            if (!ok || file.length() == 0L) {
                // Nothing encoded yet (stopped right away) or the file could not be finalized.
                file.delete()
                File(file.parentFile, file.nameWithoutExtension + ".json").delete()
                toast("녹음이 너무 짧아 저장하지 않았어요")
            } else {
                val saved = m.copy(durationMs = Recordings.durationOf(file).takeIf { it > 0 } ?: duration)
                Recordings.save(this, saved)
                toast(message ?: "녹음을 저장했어요")
                autoTranscribe(saved, file)
            }
            Recordings.notifyChanged()
        }
        audio = null
        meta = null
        finishService()
    }

    private fun autoTranscribe(meta: RecordingMeta, file: File) {
        val stt = Repository.get(this).data.value.stt
        if (!stt.autoTranscribe) return
        val model = SttModel.of(stt.model)
        runCatching {
            SttModelManager.refresh(this)
            if (!SttModelManager.isReady(this, model)) {
                toast("받아쓰기 모델(${model.label})을 먼저 내려받아 주세요 · 설정 > 수업 녹음")
                return
            }
            val diarize = stt.diarize && SttModelManager.diarizationState.value == ModelState.Ready
            Transcriber.start(this, meta.id, file, Transcriber.Options(model, diarize, stt.speakers))
        }.onFailure { Log.w(TAG, "auto transcribe failed", it) }
    }

    private fun finishService() {
        main.removeCallbacks(spaceCheck)
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Stopped from outside (e.g. the app's data was cleared, or the system stops us): keep what was recorded.
        if (recorder != null) stopRecording(null)
        if (current === this) current = null
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun toast(text: String) {
        main.post { Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show() }
    }

    private fun notification(startedAt: Long): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "수업 녹음", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, Route.Recordings.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // The chronometer counts up from [startedAt] on its own: "수업 녹음 중 · 00:12:34" without per-second updates.
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("수업 녹음 중")
            .setContentText("캐릭터를 두 번 누르거나 정지를 누르면 저장돼요")
            .setWhen(startedAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setContentIntent(open)
            .addAction(0, "정지", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "RecordService"
        private const val CHANNEL = "lecture_recording"
        private const val NOTIFICATION_ID = 43
        private const val ACTION_START = "com.schedulewidget.mobile.RECORD_START"
        private const val ACTION_STOP = "com.schedulewidget.mobile.RECORD_STOP"
        private const val EXTRA_FROM_ACTIVITY = "from_activity"
        private const val MIN_FREE_BYTES = 50L * 1024 * 1024
        private const val MAX_WAKE_MS = 12L * 60 * 60 * 1000
        private const val MESSAGE_FULL = "저장 공간이 부족해 녹음을 멈추고 저장했어요"
        const val MIN_GAIN = 50
        const val MAX_GAIN = 400

        /** The running service (main thread only), so Recorder.stop can stop without another service start. */
        @Volatile internal var current: RecordService? = null

        fun startIntent(context: Context, fromActivity: Boolean): Intent =
            Intent(context, RecordService::class.java).setAction(ACTION_START).putExtra(EXTRA_FROM_ACTIVITY, fromActivity)

        fun stopIntent(context: Context): Intent = Intent(context, RecordService::class.java).setAction(ACTION_STOP)
    }
}
