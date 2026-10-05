package com.schedulewidget.mobile.record

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.AudioDeviceInfo
import android.os.Build

/** AppData.stt.quality: AAC in .m4a. Sizes are per hour of recording. */
enum class RecordingQuality(
    val id: String,
    val label: String,
    val description: String,
    val sampleRate: Int,
    val bitRate: Int,
    /** Stereo when the device has two or more built-in microphones. */
    val stereo: Boolean,
) {
    STANDARD("standard", "표준", "44.1kHz · 96kbps · 1시간 약 43MB", 44_100, 96_000, false),
    HIGH("high", "고음질", "48kHz · 128kbps · 1시간 약 58MB", 48_000, 128_000, false),
    MAX("max", "최고", "48kHz · 192kbps · 마이크가 2개 이상이면 스테레오 · 1시간 약 86MB", 48_000, 192_000, true);

    companion object {
        fun of(id: String?): RecordingQuality = entries.firstOrNull { it.id == id } ?: HIGH
    }
}

/**
 * Which microphone path MediaRecorder uses ("녹음 마이크", AppData.stt.mic).
 *
 * - MIC: the phone's normal recording path, with automatic gain control. Best all-round default: a lecturer a few
 *   metres away is levelled up instead of coming out faint.
 * - CAMCORDER: tuned for video recording; on Samsung and many other phones it uses the multi-mic setup aimed at
 *   sound in front of / around the phone, which tends to pick up a distant speaker better in a lecture hall.
 * - UNPROCESSED (or VOICE_RECOGNITION when the device does not support it): no noise suppression / AGC, the
 *   cleanest signal for speech recognition, but quieter for distant voices.
 */
enum class RecordingMic(val id: String, val label: String, val description: String) {
    DEFAULT("default", "기본", "자동 음량 조절이 켜진 일반 녹음. 대부분 이걸로 충분해요."),
    FAR("far", "원거리(강의실)", "동영상 녹화용 마이크 경로. 멀리 있는 교수님 목소리가 더 잘 잡히는 기기가 많아요."),
    RAW("raw", "처리 최소", "잡음 제거·자동 음량 없이 원음 그대로. 받아쓰기에는 좋지만 소리가 작게 녹음될 수 있어요.");

    fun audioSource(context: Context): Int = when (this) {
        DEFAULT -> MediaRecorder.AudioSource.MIC
        FAR -> MediaRecorder.AudioSource.CAMCORDER
        RAW -> if (supportsUnprocessed(context)) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    companion object {
        fun of(id: String?): RecordingMic = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

fun supportsUnprocessed(context: Context): Boolean = runCatching {
    context.getSystemService(AudioManager::class.java)
        .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
}.getOrDefault(false)

/** Two or more built-in microphones (needed for a real stereo recording). Unknown before Android 9: assume one. */
fun builtInMicCount(context: Context): Int {
    if (Build.VERSION.SDK_INT < 28) return 1
    return runCatching {
        context.getSystemService(AudioManager::class.java).microphones.count { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }.getOrDefault(1)
}
