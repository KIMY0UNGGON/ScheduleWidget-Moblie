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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
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
    /** Notebook opened when this recording started; null for ordinary recordings. */
    val noteId: String? = null,
)

data class RecordingItem(val meta: RecordingMeta, val audio: File, val sizeBytes: Long) {
    val id: String get() = meta.id
}

internal fun recordingMetaFile(directory: File, id: String): File? =
    if (isSafeRecordingId(id)) File(directory, "$id.json") else null

internal fun RecordingMeta.matchesAudio(audio: File): Boolean =
    isSafeRecordingId(id) && id == audio.nameWithoutExtension && file == audio.name &&
        (noteId == null || isSafeRecordingId(noteId))

private fun isSafeRecordingId(id: String): Boolean =
    id.isNotBlank() && id != "." && id != ".." && id.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' }

/** Lecture recordings on disk: app-specific external storage (no permission needed), or internal storage without it. */
object Recordings {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val MAX_META_BYTES = 64 * 1024L
    private val _changes = MutableStateFlow(0)
    /** Bumped after every add / rename / delete so lists reload. */
    val changes: StateFlow<Int> = _changes

    fun notifyChanged() { _changes.value++ }

    fun dir(context: Context): File =
        (context.getExternalFilesDir("recordings") ?: File(context.filesDir, "recordings")).apply {
            if (Files.isSymbolicLink(toPath())) throw IOException("녹음 저장 경로가 올바르지 않아요")
            mkdirs()
            if (!isDirectory || Files.isSymbolicLink(toPath())) throw IOException("녹음 저장 경로가 올바르지 않아요")
        }

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

    @Synchronized
    fun save(context: Context, meta: RecordingMeta) {
        val directory = runCatching { dir(context) }.getOrNull() ?: return
        val f = recordingMetaFile(directory, meta.id) ?: return
        runCatching {
            val tmp = Files.createTempFile(directory.toPath(), "${f.name}.", ".tmp")
            try {
                FileOutputStream(tmp.toFile()).use { out ->
                    out.write(json.encodeToString(RecordingMeta.serializer(), meta).toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                Files.move(tmp, f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
    }

    private fun readMeta(audio: File): RecordingMeta? {
        val sidecar = metaFile(audio)
        if (Files.isSymbolicLink(sidecar.toPath()) || sidecar.length() !in 1L..MAX_META_BYTES) return null
        return runCatching {
            val bytes = Files.newInputStream(sidecar.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_META_BYTES) throw IOException("녹음 정보가 너무 커요")
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            json.decodeFromString(RecordingMeta.serializer(), String(bytes, Charsets.UTF_8)).takeIf { it.matchesAudio(audio) }
        }.getOrNull()
    }

    /** All recordings, newest first. Files without a sidecar (or with an unknown length) get one made up. Slow: IO. */
    fun list(context: Context, skipId: String? = null): List<RecordingItem> =
        dir(context).listFiles { f -> !Files.isSymbolicLink(f.toPath()) && f.isFile && f.extension.equals("m4a", ignoreCase = true) }.orEmpty()
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
