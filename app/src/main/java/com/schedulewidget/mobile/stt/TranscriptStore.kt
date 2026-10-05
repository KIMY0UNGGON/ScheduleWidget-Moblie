package com.schedulewidget.mobile.stt

import android.content.Context
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Transcripts on disk: filesDir/transcripts/<ascii-safe id>-<8 hex of SHA-1(id)>.json. */
internal object TranscriptStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Version 1 fields plus optional user-edit fields; older files without them still load.
    @Serializable
    private data class SegmentDto(
        @SerialName("Start") val startMs: Long,
        @SerialName("End") val endMs: Long,
        @SerialName("Speaker") val speaker: Int? = null,
        @SerialName("Text") val text: String,
        @SerialName("Edited") val edited: Boolean = false,
    )

    @Serializable
    private data class TranscriptDto(
        @SerialName("Version") val version: Int = 1,
        @SerialName("RecordingId") val recordingId: String,
        @SerialName("Model") val model: String,
        @SerialName("CreatedAt") val createdAt: Long,
        @SerialName("Segments") val segments: List<SegmentDto>,
        @SerialName("SpeakerNames") val speakerNames: Map<Int, String> = emptyMap(),
        @SerialName("EditedAt") val editedAt: Long = 0,
    )

    // Recording ids come from the recorder and may hold Korean or path characters: keep an ASCII-safe
    // readable part plus a hash of the full id so "수업_..." and "회의_..." never share a file.
    private fun safe(recordingId: String) = recordingId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun file(context: Context, recordingId: String): File {
        val sha = java.security.MessageDigest.getInstance("SHA-1").digest(recordingId.toByteArray(Charsets.UTF_8))
        val hash = sha.take(4).joinToString("") { "%02x".format(it) }
        return File(SttPaths.transcripts(context), "${safe(recordingId).take(80)}-$hash.json")
    }

    /** Name used before the hash suffix; still read so earlier transcripts stay visible. */
    private fun legacyFile(context: Context, recordingId: String): File =
        File(SttPaths.transcripts(context), safe(recordingId) + ".json")

    // The engine and the editor may both write; one writer at a time keeps the .tmp file private.
    @Synchronized
    fun save(context: Context, t: Transcript) {
        val dto = TranscriptDto(
            recordingId = t.recordingId, model = t.model, createdAt = t.createdAt,
            segments = t.segments.map { SegmentDto(it.startMs, it.endMs, it.speaker, it.text, it.edited) },
            speakerNames = t.speakerNames, editedAt = t.editedAt,
        )
        val target = file(context, t.recordingId)
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.writeText(json.encodeToString(TranscriptDto.serializer(), dto))
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("could not write ${target.name}")
        }
    }

    fun load(context: Context, recordingId: String): Transcript? {
        val f = file(context, recordingId).takeIf { it.isFile } ?: legacyFile(context, recordingId).takeIf { it.isFile } ?: return null
        return try {
            val dto = json.decodeFromString(TranscriptDto.serializer(), f.readText())
            // A legacy name may have been shared by several ids; only accept the one written for this id.
            if (dto.recordingId != recordingId) return null
            Transcript(
                dto.recordingId, dto.model, dto.createdAt,
                dto.segments.map { TranscriptSegment(it.startMs, it.endMs, it.speaker, it.text, it.edited) },
                dto.speakerNames, dto.editedAt,
            )
        } catch (e: Exception) {
            Log.w("SttTranscripts", "unreadable transcript ${f.name}", e)
            null
        }
    }

    fun delete(context: Context, recordingId: String) {
        file(context, recordingId).delete()
        val legacy = legacyFile(context, recordingId)
        if (legacy.isFile && load(context, recordingId) != null && !file(context, recordingId).isFile) legacy.delete()
    }
}
