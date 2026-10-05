package com.schedulewidget.mobile.stt

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
/** Recognizers the user can pick. [sizeMb] is the download size shown in settings. */
// Sizes (MiB) come from the PC benchmark: SenseVoice/Whisper are the download, Qwen3-ASR the unpacked size on disk.
// Qwen3-ASR comes as one 838 MiB archive that unpacks to ~941 MiB, so it briefly needs both on disk.
enum class SttModel(val id: String, val label: String, val sizeMb: Int, val description: String) {
    SENSE_VOICE("sense_voice", "SenseVoice", 228, "빠름 · 정확 (기본)"),
    WHISPER_TURBO("whisper_turbo", "Whisper turbo", 988, "가장 정확 · 느림 · 메모리 2GB"),
    WHISPER_SMALL("whisper_small", "Whisper small", 357, "중간"),
    QWEN3_ASR("qwen3_asr", "Qwen3-ASR 0.6B", 941, "정확 · 숫자를 한글로 씀");

    companion object {
        fun of(id: String?): SttModel = entries.firstOrNull { it.id == id } ?: SENSE_VOICE
    }
}

/** Download state of one model (or of the shared VAD / diarization models). */
sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

object SttModelManager {
    /** Per-model state; a model missing from the map is [ModelState.Missing]. */
    val states: StateFlow<Map<SttModel, ModelState>> = ModelStore.modelStates

    /** State of the extra models speaker separation needs (segmentation + speaker embedding). */
    val diarizationState: StateFlow<ModelState> = ModelStore.diarizationState

    fun refresh(context: Context) = ModelStore.refresh(context)
    /** Starts (or resumes) downloading [model] plus the shared VAD model; runs in the background. */
    fun download(context: Context, model: SttModel) = ModelStore.download(context, model)
    fun downloadDiarization(context: Context) = ModelStore.downloadDiarization(context)
    fun cancel(context: Context, model: SttModel) = ModelStore.cancel(model)
    fun delete(context: Context, model: SttModel) = ModelStore.delete(context, model)
    fun isReady(context: Context, model: SttModel): Boolean = ModelStore.isReady(context.applicationContext, model)
    /** Bytes the downloaded models take on disk. */
    fun usedBytes(context: Context): Long = ModelStore.usedBytes(context.applicationContext)
}
