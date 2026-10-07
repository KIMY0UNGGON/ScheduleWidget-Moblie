package com.schedulewidget.mobile.record

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.schedulewidget.mobile.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the pets and the recordings screen show. [startedElapsed] is SystemClock.elapsedRealtime() at start. */
data class RecordingState(
    val isRecording: Boolean = false,
    val id: String? = null,
    val startedAt: Long = 0L,
    val startedElapsed: Long = 0L,
) {
    fun elapsedMs(now: Long = SystemClock.elapsedRealtime()): Long = if (isRecording) (now - startedElapsed).coerceAtLeast(0) else 0L
}

/** Peak input level in dBFS (-90..0) after the mic gain; [clipping] = it hit the limiter often in the last seconds. */
data class InputLevel(val peakDb: Float = -90f, val clipping: Boolean = false)

/**
 * Lecture recording (수업 녹음). Recording itself runs in [RecordService] (a microphone foreground service, so it
 * keeps going with the screen off); this is the entry point for the pets' gestures and the screens.
 */
object Recorder {
    internal val mutableState = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = mutableState
    val isRecording: Boolean get() = state.value.isRecording

    internal val mutableLevel = MutableStateFlow(InputLevel())
    /** Live input level while recording (after the mic gain), about 10 updates a second. */
    val level: StateFlow<InputLevel> = mutableLevel

    /** AppData.stt.enabled: off = no recording gestures, bubble page, tab or indicators. */
    fun enabled(context: Context): Boolean = Repository.get(context).data.value.stt.enabled

    /**
     * A transcript to show once the recordings screen is up (set by the pet bubble's "보기", then the screen opens).
     * The screen clears it after opening.
     */
    val openTranscript = MutableStateFlow<String?>(null)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Starts recording. Without the microphone permission a small transparent activity asks for it first (and starts
     * recording once granted). Already recording: just says so.
     */
    fun start(context: Context, noteId: String? = null) {
        if (!enabled(context)) return
        if (isRecording) {
            Toast.makeText(context.applicationContext, "녹음 중이에요", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasPermission(context)) {
            RecordPermissionActivity.launch(context, noteId)
            return
        }
        val fromActivity = context is Activity
        runCatching { ContextCompat.startForegroundService(context, RecordService.startIntent(context, fromActivity, noteId)) }
            .onFailure {
                // e.g. ForegroundServiceStartNotAllowedException: start it from a (briefly) visible activity instead.
                if (fromActivity) Toast.makeText(context.applicationContext, "녹음을 시작할 수 없어요", Toast.LENGTH_SHORT).show()
                else RecordPermissionActivity.launch(context, noteId)
            }
    }

    /** Stops and saves the recording (no-op when not recording). */
    fun stop(context: Context) {
        val service = RecordService.current
        if (service != null) {
            service.stopRecording(null)
            return
        }
        if (isRecording) runCatching { context.startService(RecordService.stopIntent(context)) }
    }
}
