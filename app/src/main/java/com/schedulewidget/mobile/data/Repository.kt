package com.schedulewidget.mobile.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Serializes a desktop import against late Google sync commits without holding a lock during network calls. */
internal class ImportEpoch {
    private var value = 0L

    @Synchronized
    fun current(): Long = value

    @Synchronized
    fun <T> capture(snapshot: () -> T): Pair<Long, T> = value to snapshot()

    @Synchronized
    fun advance(block: () -> Unit) {
        value++
        block()
    }

    @Synchronized
    fun runIfCurrent(expected: Long, update: () -> Unit): Boolean {
        if (expected != value) return false
        update()
        return true
    }
}

/**
 * Single source of truth for all app data. Process-wide singleton; call [Repository.get].
 * Every mutation goes through [update], which persists to files/schedules.json (with .bak) and
 * notifies [changeListeners] (used by the home-screen widgets to refresh). Keys the PC app writes that the phone doesn't
 * model are kept on save and export (see [JsonMerge]).
 */
class Repository private constructor(private val context: Context) {
    private val file = File(context.filesDir, FILE_NAME)
    private val tmp = File(context.filesDir, "$FILE_NAME.tmp")
    private val backup = File(context.filesDir, "$FILE_NAME.bak")
    private val importEpoch = ImportEpoch()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)

    private val _data: MutableStateFlow<AppData>
    private val _warning: MutableStateFlow<String?>

    /**
     * The JSON last loaded, imported or written. Saves and exports lay the model over it ([JsonMerge]) so keys only the
     * PC app knows survive a round-trip through the phone. Guarded by [rawLock].
     */
    private var raw: JsonObject?
    private val rawLock = Any()

    /**
     * schedules.json on disk is one this app can read (it parsed at start, or was written/restored since). Until then a
     * save doesn't copy it over the .bak: after a failed load that would replace the backup with a broken file.
     * Guarded by the save lock.
     */
    private var fileTrusted: Boolean

    /** A save failed and the user was told; not told again until a save succeeds. Guarded by the save lock. */
    private var saveFailing = false

    init {
        val loaded = load()
        _data = MutableStateFlow(loaded.data)
        _warning = MutableStateFlow(loaded.warning)
        raw = loaded.raw
        fileTrusted = loaded.fileTrusted
        scope.launch {
            for (request in saveRequests) {
                if (save()) changeListeners.forEach { runCatching { it(context) } }
            }
        }
    }

    val data: StateFlow<AppData> = _data.asStateFlow()

    /** A problem the user should know about (data file unreadable and restored/reset, a save that failed). */
    val warning: StateFlow<String?> = _warning.asStateFlow()
    fun clearWarning() { _warning.value = null }

    /** Called on a background thread after every saved change. Register once (e.g. in Application.onCreate). */
    // Copy-on-write: registered from the main thread, iterated from IO threads.
    val changeListeners: MutableList<(Context) -> Unit> = CopyOnWriteArrayList()

    /** Last state written to disk (guarded by the save lock). */
    private var lastSaved: AppData? = null

    fun update(transform: (AppData) -> AppData) {
        _data.update(transform)
        // One writer takes the newest state; bursts don't start one IO coroutine and JSON encode per input event.
        saveRequests.trySend(Unit)
    }

    /** State snapshot and epoch used to reject a Google sync commit after a desktop schedule import. */
    internal fun captureImportSnapshot(): Pair<Long, AppData> = importEpoch.capture { _data.value }

    internal fun currentImportEpoch(): Long = importEpoch.current()

    internal fun updateIfImportUnchanged(epoch: Long, transform: (AppData) -> AppData): Boolean =
        importEpoch.runIfCurrent(epoch) { update(transform) }

    /** Replaces schedules and playlists with a desktop schedules.json; keeps mobile-only settings. */
    fun importDesktopJson(text: String): Result<AppData> = runCatching {
        val importedRaw = json.parseToJsonElement(text.trimStart(BOM)) as? JsonObject ?: error("schedules.json is not an object")
        // Fails here, before anything changes, on a file that isn't a schedules.json.
        require(importedRaw[SCHEDULES] is JsonArray) { "schedules.json has no Schedules array" }
        json.decodeFromJsonElement(AppData.serializer(), importedRaw)
        importEpoch.advance {
            // The desktop file becomes the new base, so its PC-only keys are written back; mobile-only keys stay the phone's.
            synchronized(rawLock) {
                raw = JsonObject(importedRaw.filterKeys { !isMobileKey(it) } + (raw?.filterKeys { isMobileKey(it) } ?: emptyMap()))
            }
            update { cur ->
                val imported = withMobileSettings(importedRaw, cur)
                val character = mapDesktopCharacter(imported.characterManifest)
                clearGoogleCalendarLinks(
                    imported.copy(
                        characterManifest = character ?: cur.characterManifest,
                        miniExtraCharacters = imported.miniExtraCharacters.take(2).mapNotNull { slot ->
                            mapDesktopCharacter(slot.manifest)?.let { slot.copy(manifest = it) }
                        },
                        schedules = imported.schedules.map { it.copy(endPeriod = ScheduleItem.normalizeEndPeriod(it.period, it.endPeriod)) },
                    )
                )
            }
        }
        _data.value
    }

    /**
     * Decodes [importedRaw] with every "Mobile*" setting taken from [cur] (a desktop file doesn't have them). Driven by
     * the model's key names, so a newly added mobile setting is carried over without being listed here.
     * Per-schedule mobile fields (device-calendar link, memo, …) are kept for schedules on both sides (same Id) when the
     * file doesn't carry them.
     */
    private fun withMobileSettings(importedRaw: JsonObject, cur: AppData): AppData {
        val current = json.encodeToJsonElement(AppData.serializer(), cur) as JsonObject
        val descriptor = AppData.serializer().descriptor
        val merged = importedRaw.toMutableMap()
        for (i in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(i)
            if (!isMobileKey(name)) continue
            // Missing = null on the phone (explicitNulls = false): drop any value the file carries, too.
            val value = current[name]
            if (value != null) merged[name] = value else merged.remove(name)
        }
        val schedules = importedRaw[SCHEDULES] as? JsonArray
        val phoneSchedules = (current[SCHEDULES] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .associateBy { (it["Id"] as? JsonPrimitive)?.content?.lowercase() }
        if (schedules != null && phoneSchedules.isNotEmpty()) {
            val item = ScheduleItem.serializer().descriptor
            val mobileKeys = (0 until item.elementsCount).map { item.getElementName(it) }.filter { isMobileKey(it) }
            merged[SCHEDULES] = JsonArray(schedules.map { s ->
                val obj = s as? JsonObject ?: return@map s
                val phone = phoneSchedules[(obj["Id"] as? JsonPrimitive)?.content?.lowercase()] ?: return@map s
                val carried = mobileKeys.filter { it !in obj }.mapNotNull { k -> phone[k]?.let { k to it } }
                if (carried.isEmpty()) obj else JsonObject(obj + carried)
            })
        }
        return json.decodeFromJsonElement(AppData.serializer(), JsonObject(merged))
    }

    /**
     * Desktop manifests are paths: "Characters/<id>/pet.json" (built-in) or "...\\Pet\\<id>\\pet.json" (imported on
     * the PC). Built-ins map directly; an imported one maps only if that character was also imported on the phone.
     */
    private fun mapDesktopCharacter(path: String): String? {
        if (path.startsWith("builtin:") || path.startsWith("pet:") || path.startsWith("uri:")) return path
        val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() }
        val id = parts.getOrNull(parts.size - 2)?.lowercase() ?: return null
        val folder = parts.getOrNull(parts.size - 3)?.lowercase()
        return when {
            folder == "characters" || folder == "defaultpets" -> "builtin:$id"
            File(File(context.filesDir, "pets"), "$id/pet.json").exists() -> "pet:$id"
            else -> null
        }
    }

    /** The data as a schedules.json the PC app can open, with the PC-only keys last loaded or imported kept. */
    fun exportJson(): String = json.encodeToString(JsonObject.serializer(), encodeOverRaw(_data.value, synchronized(rawLock) { raw }))

    private fun encodeOverRaw(d: AppData, base: JsonObject?): JsonObject =
        JsonMerge.merge(json.encodeToJsonElement(AppData.serializer(), d), base, AppData.serializer().descriptor) as JsonObject

    private class Loaded(val data: AppData, val raw: JsonObject?, val warning: String?, val fileTrusted: Boolean)

    /**
     * Like the PC's DataManager.LoadData: a schedules.json that can't be read is moved aside (schedules.json.corrupt-<time>)
     * instead of being overwritten, then the tmp and .bak copies are tried. Only when nothing is readable does it start
     * empty, and every recovery is reported through [warning].
     */
    private fun load(): Loaded = try {
        loadFiles()
    } finally {
        pruneCorrupt()
    }

    private fun loadFiles(): Loaded {
        val primaryExisted = file.exists()
        val kept = mutableListOf<String>()
        if (primaryExisted) {
            val parsed = read(file)
            if (parsed != null) return Loaded(parsed.first, parsed.second, null, fileTrusted = true)
            quarantine(file)?.let { kept += it }
        }
        // tmp = a complete write whose rename failed after the old file was deleted (a partial one just fails to parse).
        if (tmp.exists()) {
            val parsed = read(tmp)
            if (parsed != null) {
                val restored = restorePrimary(tmp)
                return Loaded(parsed.first, parsed.second, if (primaryExisted) recoveredWarning(kept) else null, restored)
            }
            tmp.delete() // a write cut short: nothing worth keeping
        }
        if (backup.exists()) {
            val parsed = read(backup)
            if (parsed != null) {
                val restored = restorePrimary(backup)
                val warning = if (primaryExisted) recoveredWarning(kept) else "저장된 일정 파일이 없어 백업에서 복구했습니다."
                return Loaded(parsed.first, parsed.second, warning, restored)
            }
            quarantine(backup)?.let { kept += it }
        }
        // Nothing on disk at all: a first start.
        if (!primaryExisted && kept.isEmpty()) return Loaded(AppData(), null, null, fileTrusted = false)
        val warning = buildString {
            append("저장된 일정 파일을 읽지 못해 새로 시작했습니다.")
            if (kept.isNotEmpty()) append(" 손상된 파일은 보관해 두었어요: ").append(kept.joinToString(", "))
        }
        return Loaded(AppData(), null, warning, fileTrusted = false)
    }

    private fun recoveredWarning(kept: List<String>): String = buildString {
        append("저장된 일정 파일을 읽지 못해 백업에서 복구했습니다.")
        if (kept.isNotEmpty()) append(" 손상된 파일은 보관해 두었어요: ").append(kept.joinToString(", "))
    }

    /** The data and its raw JSON, or null when [f] can't be read or isn't a schedules.json. */
    private fun read(f: File): Pair<AppData, JsonObject>? = runCatching {
        val obj = json.parseToJsonElement(f.readText(Charsets.UTF_8).trimStart(BOM)) as JsonObject
        val decoded = json.decodeFromJsonElement(AppData.serializer(), obj)
        decoded.copy(schedules = decoded.schedules.map { item ->
            val end = ScheduleItem.normalizeEndPeriod(item.period, item.endPeriod)
            if (end == item.endPeriod) item else item.copy(endPeriod = end)
        }) to obj
    }.getOrNull()

    /** Moves an unreadable file aside (copying it if the move fails); returns the kept file's name, or null. */
    private fun quarantine(f: File): String? {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.US))
        var target = File(f.parentFile, "${f.name}$CORRUPT_MARK$stamp")
        var n = 1
        while (target.exists()) target = File(f.parentFile, "${f.name}$CORRUPT_MARK$stamp-${n++}")
        if (f.renameTo(target)) return target.name
        return runCatching {
            f.copyTo(target)
            f.delete()
            target.name
        }.getOrNull()
    }

    /** Of the files moved aside by [quarantine], only the newest [KEEP_CORRUPT] stay. */
    private fun pruneCorrupt() {
        runCatching {
            val corrupt = context.filesDir.listFiles { f -> f.name.startsWith(FILE_NAME) && f.name.contains(CORRUPT_MARK) }
                ?: return
            corrupt.sortedByDescending { it.name.substringAfter(CORRUPT_MARK) }.drop(KEEP_CORRUPT).forEach { it.delete() }
        }
    }

    /** Puts the readable copy [source] back as schedules.json (like the PC's TryRestorePrimaryFromBackup); true on success. */
    private fun restorePrimary(source: File): Boolean {
        val restoreTmp = File(context.filesDir, "$FILE_NAME.restore.tmp")
        return try {
            val from = if (source == tmp) tmp else {
                FileOutputStream(restoreTmp).use { out ->
                    source.inputStream().use { it.copyTo(out) }
                    out.fd.sync()
                }
                restoreTmp
            }
            from.renameTo(file) || (file.delete() && from.renameTo(file))
        } catch (e: Exception) {
            // The data is already in memory; the next save writes the file.
            false
        } finally {
            restoreTmp.delete()
        }
    }

    /** Writes the current state; false when exactly this state was already written (nothing to notify). */
    @Synchronized
    private fun save(): Boolean {
        val d = _data.value
        if (d === lastSaved) return false
        val base = synchronized(rawLock) { raw }
        try {
            val merged = encodeOverRaw(d, base)
            FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(JsonObject.serializer(), merged).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            // Keep the previous good file as backup before replacing it (never a file that failed to load).
            if (fileTrusted && file.exists()) runCatching { file.copyTo(backup, overwrite = true) }
            if (!tmp.renameTo(file)) {
                file.delete()
                check(tmp.renameTo(file)) { "schedules.json rename failed" }
            }
            lastSaved = d
            fileTrusted = true
            // An import may have replaced the base meanwhile; then that one stays.
            synchronized(rawLock) { if (raw === base) raw = merged }
            if (saveFailing) {
                saveFailing = false
                _warning.compareAndSet(SAVE_FAILED, null)
            }
        } catch (e: Exception) {
            if (!saveFailing) {
                saveFailing = true
                _warning.value = SAVE_FAILED
            }
            return false
        }
        return true
    }

    companion object {
        private const val FILE_NAME = "schedules.json"
        private const val CORRUPT_MARK = ".corrupt-"
        private const val KEEP_CORRUPT = 5
        private const val SCHEDULES = "Schedules"
        private const val SAVE_FAILED = "일정을 저장하지 못했습니다. 저장 공간을 확인해 주세요."

        private fun isMobileKey(name: String) = name.startsWith("Mobile")

        // Desktop (.NET) writes schedules.json with a UTF-8 byte-order mark.
        private const val BOM = 0xFEFF.toChar()

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
            coerceInputValues = true
            explicitNulls = false
        }

        @Volatile private var instance: Repository? = null
        fun get(context: Context): Repository =
            instance ?: synchronized(this) { instance ?: Repository(context.applicationContext).also { instance = it } }
    }
}

/** A desktop schedules import starts a fresh mobile Calendar connection; never retain delete ownership across files. */
internal fun clearGoogleCalendarLinks(data: AppData): AppData = data.copy(
    schedules = data.schedules.map { it.copy(
        googleEventId = null,
        googleSyncedHash = null,
        googleSyncedPeriod = null,
        googleEndMinutes = null,
        googleRefusedHash = null,
    ) },
    googleCalendar = GoogleCalendarLink(),
)
