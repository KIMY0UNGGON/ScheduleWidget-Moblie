package com.schedulewidget.mobile.notes.importer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToLong

/** Private import data kept by the walker until its matching pages and strokes have been read. */
internal object FlexcilAudio {
    internal data class StrokeOffset(
        val documentKey: String,
        val pageKey: String,
        val strokeKey: String,
        val offsetMs: Long,
    )

    internal class Parsed(val recording: FlexcilRecording, val offsets: List<StrokeOffset>, val invalidOffsets: Int)

    private data class Info(
        val key: String, val title: String, val start: Double, val createdAt: Long, val duration: Double,
        val durationMs: Long, val documentKeys: Set<String>, val documentPages: Map<String, Set<String>>,
        val invalidDocumentRefs: Int,
    )

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.let {
        it.doubleOrNull ?: it.content.toDoubleOrNull()
    }

    private fun unique(entries: List<FlexcilArchive.Zip.Entry>, suffix: String, key: String): FlexcilArchive.Zip.Entry? {
        val found = entries.filter { !it.dir && it.name.substringAfterLast('/').endsWith(suffix, ignoreCase = true) }
        if (found.size > 1) throw IOException("녹음 백업 항목이 중복됐어요")
        val entry = found.singleOrNull() ?: return null
        val memberKey = entry.name.substringAfterLast('/').dropLast(suffix.length)
        if (!memberKey.equals(key, ignoreCase = true)) throw IOException("녹음 백업 항목의 식별자가 일치하지 않아요")
        return entry
    }

    fun read(
        source: FlexcilArchive.Zip, entry: FlexcilArchive.Zip.Entry, outerKey: String, work: File,
        checkActive: () -> Unit, budget: FlexcilArchive.ExpandedBudget,
    ): Parsed {
        val expectedKey = outerKey.takeIf(FlexcilArchive::isUuid)?.uppercase(Locale.ROOT)
            ?: throw IOException("녹음 식별자를 읽을 수 없어요")
        val fab = File.createTempFile("fab_", ".zip", work)
        var audio: File? = null
        var keepAudio = false
        try {
            FlexcilArchive.extract(source, entry, fab, checkActive, budget)
            FlexcilArchive.openZip(fab).use { zip ->
                val entries = zip.entries
                val audioEntry = unique(entries, ".flxa", expectedKey) ?: throw IOException("녹음 오디오가 없어요")
                val currentInfo = unique(entries, ".rinfo", expectedKey)
                val infoEntry = currentInfo ?: unique(entries, ".rinfo_back", expectedKey) ?: throw IOException("녹음 정보가 없어요")
                val infoText = String(zip.readBytes(infoEntry, FlexcilArchive.MAX_JSON, checkActive, budget), Charsets.UTF_8)
                if (!hasSafeJsonStructure(infoText)) throw IOException("녹음 정보가 올바르지 않아요")
                val info = readInfo(FlexcilArchive.json.parseToJsonElement(infoText) as? JsonObject
                    ?: throw IOException("녹음 정보가 올바르지 않아요"), expectedKey, checkActive)

                val currentSync = unique(entries, ".rsync", expectedKey)
                val syncEntry = currentSync ?: unique(entries, ".rsync_back", expectedKey)
                val offsets = ArrayList<StrokeOffset>()
                var invalidOffsets = info.invalidDocumentRefs
                if (syncEntry != null) {
                    val syncBytes = zip.readBytes(syncEntry, FlexcilArchive.MAX_JSON, checkActive, budget)
                    val syncText = String(syncBytes, Charsets.UTF_8)
                    if (!hasSafeJsonStructure(syncText)) {
                        invalidOffsets++
                    } else {
                        val items = try {
                            (FlexcilArchive.json.parseToJsonElement(syncText) as? JsonObject)?.get("items") as? JsonArray
                        } catch (_: Exception) {
                            null
                        }
                        if (items == null) invalidOffsets++ else for (item in items) {
                            checkActive()
                            val o = item as? JsonObject
                            val doc = o?.string("dockey")?.uuidKey()
                            val page = o?.string("pagekey")?.uuidKey()
                            val stroke = o?.string("obj")?.uuidKey()
                            val time = o?.number("addtime")
                            val delta = time?.minus(info.start)
                            val offset = delta?.takeIf { it.isFinite() && it >= 0.0 && it <= info.duration }
                                ?.times(1000.0)?.roundToLong()
                            if (doc == null || page == null || stroke == null || doc !in info.documentKeys ||
                                page !in info.documentPages[doc].orEmpty() || offset == null || offset !in 0L..info.durationMs
                            ) {
                                invalidOffsets++
                            } else {
                                offsets += StrokeOffset(doc, page, stroke, offset)
                            }
                        }
                    }
                }

                val audioFile = File.createTempFile("audio_", ".m4a", work)
                audio = audioFile
                FlexcilArchive.extract(zip, audioEntry, audioFile, checkActive, budget)
                if (!isM4a(audioFile)) throw IOException("녹음 오디오 형식을 읽을 수 없어요")
                val parsed = Parsed(FlexcilRecording(info.key, info.title, info.createdAt, info.durationMs,
                    info.documentKeys, audioFile), offsets, invalidOffsets)
                keepAudio = true
                return parsed
            }
        } finally {
            if (!keepAudio) audio?.delete()
            fab.delete()
        }
    }

    private fun readInfo(o: JsonObject, expectedKey: String, checkActive: () -> Unit): Info {
        val key = o.string("key")?.uuidKey() ?: throw IOException("녹음 식별자를 읽을 수 없어요")
        if (!key.equals(expectedKey, ignoreCase = true)) throw IOException("녹음 식별자가 일치하지 않아요")
        val start = o.number("start")?.takeIf { it.isFinite() && it > 0.0 } ?: throw IOException("녹음 시작 시간을 읽을 수 없어요")
        val duration = o.number("duration")?.takeIf { it.isFinite() && it > 0.0 } ?: throw IOException("녹음 길이를 읽을 수 없어요")
        val startMs = secondsToMs(start) ?: throw IOException("녹음 시작 시간이 올바르지 않아요")
        val durationMs = secondsToMs(duration) ?: throw IOException("녹음 길이가 올바르지 않아요")
        val startDoc = when (val value = o["startdoc"]) {
            null, JsonNull -> null
            is JsonPrimitive -> {
                if (!value.isString) throw IOException("녹음 문서 식별자가 올바르지 않아요")
                value.content.takeIf { it.isNotBlank() }?.let {
                    it.uuidKey() ?: throw IOException("녹음 문서 식별자가 올바르지 않아요")
                }
            }
            else -> throw IOException("녹음 문서 식별자가 올바르지 않아요")
        }
        val documents = linkedSetOf<String>()
        startDoc?.let(documents::add)
        val documentPages = linkedMapOf<String, MutableSet<String>>()
        var invalidDocumentRefs = 0
        val included = o["includeDocuments"] as? JsonArray ?: throw IOException("녹음 문서 목록이 올바르지 않아요")
        for (item in included) {
            checkActive()
            val objectValue = item as? JsonObject
            val doc = objectValue?.string("dockey")?.uuidKey()
            val pages = objectValue?.get("pages") as? JsonArray
            if (doc == null || pages == null) {
                invalidDocumentRefs++
                continue
            }
            documents += doc
            val pageKeys = documentPages.getOrPut(doc) { linkedSetOf() }
            for (pageValue in pages) {
                checkActive()
                val page = (pageValue as? JsonPrimitive)?.takeIf { it.isString }?.content?.uuidKey()
                if (page == null) invalidDocumentRefs++ else pageKeys += page
            }
        }
        val title = o.string("name")?.takeIf { it.isNotBlank() } ?: "녹음"
        return Info(key, title, start, startMs, duration, durationMs, documents,
            documentPages.mapValues { it.value.toSet() }, invalidDocumentRefs)
    }

    private fun secondsToMs(seconds: Double): Long? {
        val ms = seconds * 1000.0
        return ms.takeIf { it.isFinite() && it > 0.0 && it < Long.MAX_VALUE.toDouble() }?.roundToLong()
    }

    private fun isM4a(file: File): Boolean = file.inputStream().use { input ->
        val header = ByteArray(12)
        input.read(header) == header.size && String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
    }

    private fun String.uuidKey(): String? = takeIf(FlexcilArchive::isUuid)?.uppercase(Locale.ROOT)
}
