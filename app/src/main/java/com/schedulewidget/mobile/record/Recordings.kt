package com.schedulewidget.mobile.record

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaFormat
import android.net.Uri
import androidx.core.content.FileProvider
import com.schedulewidget.mobile.notes.NoteStore
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
import java.util.UUID

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
    /** Flexcil backup notebooks linked to an imported recording; old sidecars default to no extra links. */
    val noteIds: List<String> = emptyList(),
)

data class RecordingItem(val meta: RecordingMeta, val audio: File, val sizeBytes: Long) {
    val id: String get() = meta.id
}

internal fun recordingMetaFile(directory: File, id: String): File? =
    if (isSafeRecordingId(id)) File(directory, "$id.json") else null

internal fun RecordingMeta.matchesAudio(audio: File): Boolean =
    isSafeRecordingId(id) && id == audio.nameWithoutExtension && file == audio.name &&
        (noteId == null || isSafeRecordingId(noteId)) && noteIds.all(::isSafeRecordingId)

private fun isSafeRecordingId(id: String): Boolean =
    id.isNotBlank() && id != "." && id != ".." && id.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' }

/** Lecture recordings on disk: app-specific external storage (no permission needed), or internal storage without it. */
object Recordings {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val MAX_META_BYTES = 64 * 1024L
    private const val MAX_IMPORTED_AUDIO_BYTES = 1_073_741_824L
    private const val IMPORT_FREE_RESERVE_BYTES = 50L * 1024 * 1024
    private const val MAX_IMPORTED_TITLE_CHARS = 180
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

    private fun writeMetaAtomicChecked(directory: File, meta: RecordingMeta, replaceExisting: Boolean = true) {
        val target = recordingMetaFile(directory, meta.id)?.toPath() ?: throw IOException("녹음 정보가 올바르지 않아요")
        val audio = File(directory, meta.file)
        if (meta.file != audio.name || !audio.extension.equals("m4a", ignoreCase = true) || !meta.matchesAudio(audio)) {
            throw IOException("녹음 정보가 올바르지 않아요")
        }
        if (Files.isSymbolicLink(target)) throw IOException("녹음 정보 저장 경로가 올바르지 않아요")
        if (!replaceExisting && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw IOException("녹음 정보가 이미 있어요")

        val bytes = json.encodeToString(RecordingMeta.serializer(), meta).toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() !in 1L..MAX_META_BYTES) throw IOException("녹음 정보가 너무 커요")
        val tmp = Files.createTempFile(directory.toPath(), "${target.fileName}.", ".tmp")
        try {
            FileOutputStream(tmp.toFile()).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (Files.isSymbolicLink(target)) throw IOException("녹음 정보 저장 경로가 올바르지 않아요")
            if (replaceExisting) {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw IOException("녹음 정보가 이미 있어요")
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            }
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    private fun newImportedId(directory: File): String {
        repeat(8) {
            val id = "flex_${UUID.randomUUID()}"
            val audio = File(directory, "$id.m4a").toPath()
            val sidecar = checkNotNull(recordingMetaFile(directory, id)).toPath()
            if (!Files.exists(audio, LinkOption.NOFOLLOW_LINKS) && !Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) return id
        }
        throw IOException("새 녹음 이름을 만들지 못했어요")
    }

    private fun requireAudioContainer(audio: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(audio.absolutePath)
            val hasAudio = (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            if (!hasAudio) throw IOException("지원하는 오디오 트랙이 없어요")
        } finally {
            extractor.release()
        }
    }

    private fun ensureNotRecording(recordingId: String) {
        val state = Recorder.state.value
        if (state.isRecording && state.id == recordingId) throw IOException("녹음 중에는 노트를 연결할 수 없어요")
    }

    @Synchronized
    fun save(context: Context, meta: RecordingMeta) {
        val directory = runCatching { dir(context) }.getOrNull() ?: return
        runCatching { writeMetaAtomicChecked(directory, meta) }
    }

    /** Copies one already-extracted Flexcil audio file byte-for-byte into app-owned storage. */
    @Synchronized
    internal fun importAudio(
        context: Context,
        audio: File,
        title: String,
        createdAt: Long,
        durationMs: Long,
        checkActive: () -> Unit = {},
    ): RecordingItem {
        val source = audio.toPath()
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
            throw IOException("가져온 녹음 파일을 읽을 수 없어요")
        }
        val length = Files.size(source)
        if (length !in 1L..MAX_IMPORTED_AUDIO_BYTES) throw IOException("녹음 파일 크기가 올바르지 않아요")

        val directory = dir(context)
        if (directory.usableSpace < length + IMPORT_FREE_RESERVE_BYTES) throw IOException("저장 공간이 부족해요")
        val id = newImportedId(directory)
        val audioTarget = File(directory, "$id.m4a")
        val sidecar = recordingMetaFile(directory, id) ?: throw IOException("녹음 정보를 저장할 수 없어요")
        val audioTemp = Files.createTempFile(directory.toPath(), "$id.", ".tmp")
        var installedAudio = false
        var installedMeta = false
        try {
            Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS).use { input ->
                FileOutputStream(audioTemp.toFile()).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > MAX_IMPORTED_AUDIO_BYTES || copied > length) throw IOException("녹음 파일이 바뀌었어요")
                        output.write(buffer, 0, count)
                    }
                    if (copied != length) throw IOException("녹음 파일이 바뀌었어요")
                    output.fd.sync()
                }
            }
            checkActive()
            requireAudioContainer(audioTemp.toFile())
            checkActive()
            if (Files.exists(audioTarget.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                Files.exists(sidecar.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("같은 이름의 녹음이 이미 있어요")
            }

            val safeTitle = title.filterNot { Character.isISOControl(it) }.trim()
                .take(MAX_IMPORTED_TITLE_CHARS).ifBlank { "Flexcil 녹음" }
            val meta = RecordingMeta(
                id = id, title = safeTitle, createdAt = createdAt, durationMs = durationMs.coerceAtLeast(0),
                file = audioTarget.name,
            )
            checkActive()
            writeMetaAtomicChecked(directory, meta, replaceExisting = false)
            installedMeta = true
            checkActive()
            Files.move(audioTemp, audioTarget.toPath(), StandardCopyOption.ATOMIC_MOVE)
            installedAudio = true
            notifyChanged()
            return RecordingItem(meta, audioTarget, length)
        } catch (e: Throwable) {
            if (installedMeta) runCatching { Files.deleteIfExists(sidecar.toPath()) }
            if (installedAudio) runCatching { Files.deleteIfExists(audioTarget.toPath()) }
            throw e
        } finally {
            runCatching { Files.deleteIfExists(audioTemp) }
        }
    }

    /** Adds backup-level links without replacing the imported recording's title or timing metadata. */
    @Synchronized
    internal fun setNoteLinks(
        context: Context, recordingId: String, noteIds: Collection<String>,
        /** Live note IDs checked once by the bounded backup restore; ordinary calls check the store below. */
        restoredNoteIds: Set<String>? = null,
    ): RecordingItem {
        if (!isSafeRecordingId(recordingId)) throw IOException("녹음 정보를 찾을 수 없어요")
        val directory = dir(context)
        val audio = File(directory, "$recordingId.m4a")
        if (!Files.isRegularFile(audio.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(audio.toPath())) {
            throw IOException("녹음 파일을 찾을 수 없어요")
        }
        ensureNotRecording(recordingId)
        val current = readMeta(audio) ?: throw IOException("녹음 정보를 읽을 수 없어요")
        val links = noteIds.toList().distinct()
        if (links.any { !isSafeRecordingId(it) }) throw IOException("노트 연결 정보가 올바르지 않아요")
        for (noteId in links) {
            val exists = if (restoredNoteIds == null) NoteStore.get(context, noteId) != null else noteId in restoredNoteIds
            if (!exists) throw IOException("연결할 노트를 찾을 수 없어요")
        }
        ensureNotRecording(recordingId)
        val updated = current.copy(noteIds = links)
        writeMetaAtomicChecked(directory, updated)
        notifyChanged()
        return RecordingItem(updated, audio, audio.length())
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

    @Synchronized
    fun rename(context: Context, item: RecordingItem, title: String) {
        val directory = runCatching { dir(context) }.getOrNull() ?: return
        val current = readMeta(item.audio) ?: item.meta
        val updated = current.copy(title = title.trim().ifEmpty { current.title })
        if (runCatching { writeMetaAtomicChecked(directory, updated) }.isSuccess) notifyChanged()
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
