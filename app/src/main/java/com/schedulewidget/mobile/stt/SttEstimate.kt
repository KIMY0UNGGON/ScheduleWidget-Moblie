package com.schedulewidget.mobile.stt

import kotlin.math.ceil

/**
 * Rough minutes a phone needs to transcribe [durationMs] of audio with [model] (plus speaker separation).
 * Real-time factors are the PC benchmark's desktop numbers x ~3 for a mid-range phone; treat as a hint only.
 */
fun estimateMinutes(model: SttModel, durationMs: Long, diarize: Boolean = false): Int {
    val rtf = when (model) {
        SttModel.SENSE_VOICE -> 0.05
        SttModel.QWEN3_ASR -> 0.5
        SttModel.WHISPER_SMALL -> 0.6
        SttModel.WHISPER_TURBO -> 1.0
    } + 0.02 /* decode + VAD */ + (if (diarize) 0.2 else 0.0)
    return ceil(durationMs / 60_000.0 * rtf).toInt().coerceAtLeast(1)
}
