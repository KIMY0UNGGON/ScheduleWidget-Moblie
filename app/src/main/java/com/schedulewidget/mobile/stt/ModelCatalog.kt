package com.schedulewidget.mobile.stt

import android.content.Context
import java.io.File

// Where every on-device speech model comes from and where it lives on disk.
// Layout under filesDir/stt/:
//   <model-id>/...           one recognizer (SttModel.id)
//   vad/silero_vad.onnx      shared voice-activity detector
//   diarization/...          speaker segmentation + speaker embedding

/** A file fetched as-is. [bytes] is the published size (used for progress and sanity checks). */
internal data class RemoteFile(val url: String, val name: String, val bytes: Long)

/**
 * A .tar.bz2 archive streamed into a directory. The first path component of every entry is dropped;
 * only entries whose remaining path is in [keep] (or under a kept directory, "dir/") are written.
 * [required] maps an extracted path to its minimum plausible size.
 */
internal data class RemoteArchive(
    val url: String,
    val archiveBytes: Long,
    val extractedBytes: Long,
    val keep: List<String>,
    val required: Map<String, Long>,
)

/** One downloadable unit: a directory filled from plain files and/or one archive. */
internal data class ModelPackage(val key: String, val dirName: String, val files: List<RemoteFile>, val archive: RemoteArchive?) {
    fun dir(context: Context): File = File(SttPaths.root(context), dirName)
    fun downloadBytes(): Long = files.sumOf { it.bytes } + (archive?.archiveBytes ?: 0L)
}

internal object SttPaths {
    fun root(context: Context): File = File(context.filesDir, "stt")
    fun transcripts(context: Context): File = File(context.filesDir, "transcripts")
    fun work(context: Context): File = File(context.cacheDir, "stt")
}

internal object ModelCatalog {
    private const val HF = "https://huggingface.co"
    private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

    private fun hf(repo: String, file: String, bytes: Long) = RemoteFile("$HF/$repo/resolve/main/$file", file, bytes)

    // The general SenseVoice-Small (punctuation + ITN). The newer "...-int8-2025-09-09" repo is a Cantonese
    // fine-tune (ASLP-lab WSYue-ASR) without punctuation; on the emulator it turned Korean speech into Chinese.
    const val SENSE_VOICE_REPO = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"
    const val QWEN3_DIR = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"

    val vad = ModelPackage(
        key = "vad", dirName = "vad",
        files = listOf(RemoteFile("$GH/asr-models/silero_vad.onnx", "silero_vad.onnx", 643_854)),
        archive = null,
    )

    // pyannote segmentation 3.0 (archive holds model.onnx + model.int8.onnx; we keep the float one)
    // and the 3D-Speaker CAM++ zh/en embedding model (28 MB; picked by the PC benchmark over ERes2Net).
    const val EMBEDDING_FILE = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
    val diarization = ModelPackage(
        key = "diarization", dirName = "diarization",
        files = listOf(RemoteFile("$GH/speaker-recongition-models/$EMBEDDING_FILE", EMBEDDING_FILE, 28_281_164)),
        archive = RemoteArchive(
            url = "$GH/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
            archiveBytes = 6_958_444,
            extractedBytes = 6_000_000,
            keep = listOf("model.onnx"),
            required = mapOf("model.onnx" to 1_000_000L),
        ),
    )

    fun of(model: SttModel): ModelPackage = when (model) {
        SttModel.SENSE_VOICE -> ModelPackage(
            model.id, model.id,
            listOf(hf(SENSE_VOICE_REPO, "model.int8.onnx", 239_233_841), hf(SENSE_VOICE_REPO, "tokens.txt", 315_894)),
            null,
        )
        SttModel.WHISPER_TURBO -> ModelPackage(
            model.id, model.id,
            listOf(
                hf("csukuangfj/sherpa-onnx-whisper-turbo", "turbo-encoder.int8.onnx", 674_716_297),
                hf("csukuangfj/sherpa-onnx-whisper-turbo", "turbo-decoder.int8.onnx", 361_080_764),
                hf("csukuangfj/sherpa-onnx-whisper-turbo", "turbo-tokens.txt", 816_730),
            ),
            null,
        )
        SttModel.WHISPER_SMALL -> ModelPackage(
            model.id, model.id,
            listOf(
                hf("csukuangfj/sherpa-onnx-whisper-small", "small-encoder.int8.onnx", 112_442_483),
                hf("csukuangfj/sherpa-onnx-whisper-small", "small-decoder.int8.onnx", 262_226_114),
                hf("csukuangfj/sherpa-onnx-whisper-small", "small-tokens.txt", 816_730),
            ),
            null,
        )
        // No per-file mirror exists for Qwen3-ASR, so the release archive is downloaded (resumable) and then unpacked.
        SttModel.QWEN3_ASR -> ModelPackage(
            model.id, model.id, emptyList(),
            RemoteArchive(
                url = "$GH/asr-models/$QWEN3_DIR.tar.bz2",
                archiveBytes = 878_702_423,
                extractedBytes = 987_000_000,
                keep = listOf("conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx", "tokenizer/"),
                required = mapOf(
                    "conv_frontend.onnx" to 40_000_000L,
                    "encoder.int8.onnx" to 150_000_000L,
                    "decoder.int8.onnx" to 700_000_000L,
                    "tokenizer/vocab.json" to 1_000_000L,
                    "tokenizer/merges.txt" to 1_000_000L,
                    "tokenizer/tokenizer_config.json" to 1_000L,
                ),
            ),
        )
    }
}
