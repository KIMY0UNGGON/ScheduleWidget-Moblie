package com.schedulewidget.mobile.record

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import com.schedulewidget.mobile.stt.Transcriber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Sidecar "<name>.json" next to each "<name>.m4a". [id] is the file name without extension (also the transcript key). */
@Serializable
data class RecordingMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long = 0,
    /** Audio file name in the recordings folder. */
    val file: String,
)

data class RecordingItem(val meta: RecordingMeta, val audio: File, val sizeBytes: Long) {
    val id: String get() = meta.id
}

/** Lecture recordings on disk: app-specific external storage (no permission needed), or internal storage without it. */
object Recordings {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _changes = MutableStateFlow(0)
    /** Bumped after every add / rename / delete so lists reload. */
    val changes: StateFlow<Int> = _changes

    fun notifyChanged() { _changes.value++ }

    fun dir(context: Context): File =
        (context.getExternalFilesDir("recordings") ?: File(context.filesDir, "recordings")).apply { mkdirs() }

    /** "수업_2026-10-01_14-30.m4a", with "_2", "_3"... when a recording already started in the same minute. */
    fun newFile(context: Context, now: Long): File {
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm").format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))
        val dir = dir(context)
        var n = 1
        while (true) {
            val name = if (n == 1) "수업_$stamp" else "수업_${stamp}_$n"
            val f = File(dir, "$name.m4a")
            if (!f.exists() && !metaFile(f).exists()) return f
            n++
        }
    }

    fun defaultTitle(createdAt: Long): String =
        "수업 " + DateTimeFormatter.ofPattern("M월 d일 HH:mm").format(Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()))

    private fun metaFile(audio: File) = File(audio.parentFile, audio.nameWithoutExtension + ".json")

    fun save(context: Context, meta: RecordingMeta) {
        val f = File(dir(context), meta.id + ".json")
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(RecordingMeta.serializer(), meta))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    private fun readMeta(audio: File): RecordingMeta? = runCatching {
        json.decodeFromString(RecordingMeta.serializer(), metaFile(audio).readText())
    }.getOrNull()

    /** All recordings, newest first. Files without a sidecar (or with an unknown length) get one made up. Slow: IO. */
    fun list(context: Context, skipId: String? = null): List<RecordingItem> =
        dir(context).listFiles { f -> f.isFile && f.extension.equals("m4a", ignoreCase = true) }.orEmpty()
            .filter { it.nameWithoutExtension != skipId }
            .map { audio ->
                var meta = readMeta(audio) ?: RecordingMeta(
                    id = audio.nameWithoutExtension, title = defaultTitle(audio.lastModified()),
                    createdAt = audio.lastModified(), file = audio.name,
                )
                if (meta.durationMs <= 0) {
                    // Interrupted recording (process killed) or a file copied in: read the length from the file.
                    val d = durationOf(audio)
                    if (d > 0) meta = meta.copy(durationMs = d).also { save(context, it) }
                }
                RecordingItem(meta, audio, audio.length())
            }
            .sortedByDescending { it.meta.createdAt }

    fun find(context: Context, id: String): RecordingItem? = list(context).firstOrNull { it.id == id }

    fun durationOf(audio: File): Long = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(audio.absolutePath)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally { release() }
        }
    }.getOrDefault(0L)

    fun rename(context: Context, item: RecordingItem, title: String) {
        save(context, item.meta.copy(title = title.trim().ifEmpty { item.meta.title }))
        notifyChanged()
    }

    fun delete(context: Context, item: RecordingItem) {
        item.audio.delete()
        metaFile(item.audio).delete()
        runCatching { Transcriber.delete(context, item.id) }
        notifyChanged()
    }

    fun uri(context: Context, item: RecordingItem): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", item.audio)

    fun shareIntent(context: Context, item: RecordingItem): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri(context, item))
            .putExtra(Intent.EXTRA_SUBJECT, item.meta.title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "녹음 공유")
    }
}

/** 12:34, or 1:02:03 from an hour on. */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1fGB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1fMB".format(bytes / (1024.0 * 1024))
    else -> "%dKB".format((bytes / 1024).coerceAtLeast(1))
}
