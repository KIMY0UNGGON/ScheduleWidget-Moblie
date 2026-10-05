package com.schedulewidget.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Recording and on-device speech-to-text options. */
@Serializable
data class SttSettings(
    // The whole recording feature (pet gestures, bubble page, 녹음 tab); off = everything behaves as before it existed.
    @SerialName("Enabled") val enabled: Boolean = true,
    // Recording microphone: "default" (MIC), "far" (CAMCORDER, lecture hall), "raw" (UNPROCESSED / VOICE_RECOGNITION).
    @SerialName("Mic") val mic: String = "default",
    // Recording input gain in percent (50–400, software gain with a limiter) — independent of the playback volume.
    @SerialName("MicGain") val micGain: Int = 100,
    // Playback volume of recordings inside the app in percent (0–100) — independent of the mic gain and system volume.
    @SerialName("PlaybackVolume") val playbackVolume: Int = 100,
    // User correction dictionary: applied to every new transcript (e.g. "포스트 그 스프레랩" -> "PostgreSQL").
    @SerialName("Corrections") val corrections: List<SttCorrection> = emptyList(),
    // stt.SttModel.id of the recognizer used for new transcriptions.
    @SerialName("Model") val model: String = "sense_voice",
    // Split the transcript by speaker (화자 분리).
    @SerialName("Diarize") val diarize: Boolean = true,
    // Known number of speakers, 0 = detect automatically.
    @SerialName("Speakers") val speakers: Int = 0,
    // Start transcribing as soon as a recording stops.
    @SerialName("AutoTranscribe") val autoTranscribe: Boolean = false,
    // record.RecordingQuality.id
    @SerialName("Quality") val quality: String = "high",
)

/** One entry of the transcript correction dictionary: [from] is replaced by [to] (plain text, case-sensitive). */
@Serializable
data class SttCorrection(
    @SerialName("From") val from: String = "",
    @SerialName("To") val to: String = "",
)
