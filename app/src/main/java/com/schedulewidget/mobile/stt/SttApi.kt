package com.schedulewidget.mobile.stt

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import java.io.File

// Contract between the recording UI (record/*, pet/*, ui/*) and the transcription engine (stt/*).
// The engine side fills in the bodies; signatures stay as they are.
// Implementation: ModelStore + Downloader (downloads), TranscribeEngine + AudioDecoder (pipeline),
// TranscriptStore (JSON on disk), SttServices (foreground notifications).

/**
 * One piece of a transcript. [speaker] is 0-based (화자 1 = 0), null without speaker separation.
 * [edited]: the user changed [text]; start/end stay the recognizer's segment timestamps.
 */
data class TranscriptSegment(val startMs: Long, val endMs: Long, val speaker: Int?, val text: String, val edited: Boolean = false)

/** [speakerNames]: user names for speakers (0 -> "교수님"); [editedAt]: last user edit, 0 if never edited. */
data class Transcript(
    val recordingId: String,
    val model: String,
    val createdAt: Long,
    val segments: List<TranscriptSegment>,
    val speakerNames: Map<Int, String> = emptyMap(),
    val editedAt: Long = 0,
) {
    /** Plain text for copying / sharing: "[00:01:23] 화자 1: …" lines (or the speaker's given name). */
    fun asText(): String = TranscriptFormat.asText(segments, speakerNames)
}

sealed interface TranscribeState {
    data object Idle : TranscribeState
    /** [fraction] 0..1; [stage] e.g. "음성 구간 찾는 중", "받아쓰는 중", "화자 나누는 중". */
    data class Running(val fraction: Float, val stage: String) : TranscribeState
    data object Done : TranscribeState
    data class Failed(val message: String) : TranscribeState
}

object Transcriber {
    /** Per-recording state keyed by recording id. */
    val states: StateFlow<Map<String, TranscribeState>> = TranscribeEngine.states

    data class Options(val model: SttModel, val diarize: Boolean, val speakers: Int = 0)

    /** Transcribes [audio] (m4a/aac/wav) in a foreground service; progress in [states]. */
    fun start(context: Context, recordingId: String, audio: File, options: Options) =
        TranscribeEngine.start(context, recordingId, audio, options)
    fun cancel(context: Context, recordingId: String) = TranscribeEngine.cancel(recordingId)
    /** The saved transcript, or null if none yet. */
    fun load(context: Context, recordingId: String): Transcript? = TranscriptStore.load(context.applicationContext, recordingId)
    /** Atomically writes a (user-edited) transcript to the same file the engine writes. */
    fun save(context: Context, transcript: Transcript) = TranscriptStore.save(context.applicationContext, transcript)
    fun delete(context: Context, recordingId: String) {
        TranscribeEngine.cancel(recordingId)
        TranscriptStore.delete(context.applicationContext, recordingId)
    }
}
