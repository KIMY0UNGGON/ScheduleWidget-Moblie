package com.schedulewidget.mobile.stt

import android.content.Context
import java.io.File

// Where every on-device speech model comes from and where it lives on disk.
// Layout under filesDir/stt/:
//   <model-id>/...           one recognizer (SttModel.id)
//   vad/silero_vad.onnx      shared voice-activity detector
//   diarization/...          speaker segmentation + speaker embedding

/** A file fetched as-is. [bytes] is the published size (used for progress and sanity checks). */
internal data class RemoteFile(val url: String, val name: String, val bytes: Long, val sha256: String)

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
    val sha256: String,
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

    private fun hf(repo: String, file: String, bytes: Long, sha256: String) = RemoteFile("$HF/$repo/resolve/main/$file", file, bytes, sha256)

    // The general SenseVoice-Small (punctuation + ITN). The newer "...-int8-2025-09-09" repo is a Cantonese
    // fine-tune (ASLP-lab WSYue-ASR) without punctuation; on the emulator it turned Korean speech into Chinese.
    const val SENSE_VOICE_REPO = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"
    const val QWEN3_DIR = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"

    val vad = ModelPackage(
        key = "vad", dirName = "vad",
        files = listOf(RemoteFile("$GH/asr-models/silero_vad.onnx", "silero_vad.onnx", 643_854,
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6")),
        archive = null,
    )

    // pyannote segmentation 3.0 (archive holds model.onnx + model.int8.onnx; we keep the float one)
    // and the 3D-Speaker CAM++ zh/en embedding model (28 MB; picked by the PC benchmark over ERes2Net).
    const val EMBEDDING_FILE = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"
    val diarization = ModelPackage(
        key = "diarization", dirName = "diarization",
        files = listOf(RemoteFile("$GH/speaker-recongition-models/$EMBEDDING_FILE", EMBEDDING_FILE, 28_281_164,
            "aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2")),
        archive = RemoteArchive(
            url = "$GH/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
            archiveBytes = 6_958_444,
            extractedBytes = 6_000_000,
            keep = listOf("model.onnx"),
            required = mapOf("model.onnx" to 1_000_000L),
            sha256 = "24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488",
        ),
    )

    fun of(model: SttModel): ModelPackage = when (model) {
        SttModel.SENSE_VOICE -> ModelPackage(
            model.id, model.id,
            listOf(
                hf(SENSE_VOICE_REPO, "model.int8.onnx", 239_233_841, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
                hf(SENSE_VOICE_REPO, "tokens.txt", 315_894, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
            ),
            null,
        )
        SttModel.WHISPER_TURBO -> ModelPackage(
            model.id, model.id,
            listOf(
                hf("csukuangfj/sherpa-onnx-whisper-turbo", "turbo-encoder.int8.onnx", 674_716_297, "b02dcdf54f348741e93fe732b67d933c8dcb6735655f710640143081db38878b"),
                hf("csukuangfj/sherpa-onnx-whisper-turbo", "turbo-decoder.int8.onnx", 361_080_764, "20accd02388482eb3a46bd615631adfdc85e1eb2c7db9ea3f02a40ffe6b81547"),
                hf("csukuangfj/sherpa-onnx-whisper-turbo", "turbo-tokens.txt", 816_730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
            ),
            null,
        )
        SttModel.WHISPER_SMALL -> ModelPackage(
            model.id, model.id,
            listOf(
                hf("csukuangfj/sherpa-onnx-whisper-small", "small-encoder.int8.onnx", 112_442_483, "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9"),
                hf("csukuangfj/sherpa-onnx-whisper-small", "small-decoder.int8.onnx", 262_226_114, "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee"),
                hf("csukuangfj/sherpa-onnx-whisper-small", "small-tokens.txt", 816_730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
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
                sha256 = "393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96",
            ),
        )
    }
}
