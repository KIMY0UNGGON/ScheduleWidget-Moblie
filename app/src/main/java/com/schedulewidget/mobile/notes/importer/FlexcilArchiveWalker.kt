package com.schedulewidget.mobile.notes.importer

import com.schedulewidget.mobile.notes.importer.FlexcilArchive.Book
import com.schedulewidget.mobile.notes.importer.FlexcilArchive.Page
import com.schedulewidget.mobile.notes.importer.FlexcilArchive.Zip
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.io.IOException
import java.util.Locale
internal class FlexcilArchiveWalker(
    val work: File, val outerName: String?, val progress: (String) -> Unit, val sink: (Book) -> Unit,
    val restoreSink: ((FlexcilDocument) -> Unit)? = null, private val checkActive: () -> Unit = {},
    private val recordingSink: ((FlexcilRecording) -> String)? = null,
) {

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    val report = StringBuilder()
    var books = 0
    var strokes = 0
    var inkLost = 0
    var failedDocs = 0
    var passwordFailures = 0
    var imagePagesLost = 0
    var recordingsImported = 0
    var audioFailures = 0
    var unmatchedAudioRefs = 0
    var flexcil = false
    private var docs = 0
    private var tmpSeq = 0
    private var recordingAttempts = 0
    private var documentAttempts = 0
    private var nestedArchiveAttempts = 0
    private var totalPages = 0
    private val countedImages = HashSet<String>()
    private val expandedBudget = FlexcilArchive.ExpandedBudget()
    private val sourceDocuments = HashSet<String>()
    private val sourceRecordings = HashSet<String>()
    private val audioLinks = HashMap<StrokeAudioKey, FlexcilInk.RecordingLink>()
    private val ambiguousAudioLinks = HashSet<StrokeAudioKey>()
    private val matchedAudioLinks = HashSet<StrokeAudioKey>()
    private var trackedAudioRefs = 0
    private var audioRefsFinalized = false
    /** documents.list: document id (upper case, no extension) → (title, folder path). */
    private val listed = HashMap<String, Pair<String?, List<String>>>()
    private val duplicateListed = HashSet<String>()
    private var reportTruncated = false

    fun summary() {
        reportLine("summary\tbooks=$books\tdocs=$docs\tfailed=$failedDocs\tstrokes=$strokes\tinkLost=$inkLost\timagePagesLost=$imagePagesLost\trecordings=$recordingsImported\taudioFailed=$audioFailures\taudioUnmatched=$unmatchedAudioRefs\tlisted=${listed.size}")
    }

    private data class StrokeAudioKey(val document: String, val page: String, val stroke: String)
    private class InkBudget(var strokes: Int = 0, var points: Int = 0)

    private fun attemptRecording() {
        if (recordingAttempts >= FlexcilArchive.MAX_RECORDING_ATTEMPTS) throw FlexcilArchive.ResourceLimitExceeded("녹음이 너무 많아요")
        recordingAttempts++
    }

    private fun attemptDocument() {
        if (documentAttempts >= FlexcilArchive.MAX_DOCUMENT_ATTEMPTS) throw FlexcilArchive.ResourceLimitExceeded("가져올 파일이 너무 많아요")
        documentAttempts++
    }

    private fun attemptNestedArchive() {
        if (nestedArchiveAttempts >= FlexcilArchive.MAX_NESTED_ARCHIVE_ATTEMPTS) throw FlexcilArchive.ResourceLimitExceeded("압축 파일이 너무 많아요")
        nestedArchiveAttempts++
    }

    private fun uuidKey(value: String?): String? = value?.takeIf(FlexcilArchive::isUuid)?.uppercase(Locale.ROOT)

    fun finishAudioRefs() {
        if (audioRefsFinalized) return
        unmatchedAudioRefs += audioLinks.keys.count { it !in matchedAudioLinks }
        unmatchedAudioRefs += ambiguousAudioLinks.size
        audioRefsFinalized = true
    }

    internal fun reportLine(line: String) {
        if (reportTruncated) return
        if (report.length + line.length + 1 <= MAX_REPORT_CHARS) {
            report.appendLine(line)
        } else {
            val marker = "... report truncated ...\n"
            val room = MAX_REPORT_CHARS - report.length
            if (room >= marker.length) report.append(line.take(room - marker.length)).append(marker)
            else report.append(line.take(room))
            reportTruncated = true
        }
    }

    private fun recordFailure(path: String, e: Exception) {
        if (e is java.util.concurrent.CancellationException || e is FlexcilArchive.ResourceLimitExceeded) throw e
        failedDocs++
        if (e is FlexcilArchive.PasswordProtectedPdf) passwordFailures++
        reportLine("  ! $path\t${e.javaClass.simpleName}")
    }

    private fun tmp(suffix: String) = File(work, "flx_${tmpSeq++}$suffix")

    private fun restoreRecording(zip: Zip, entry: Zip.Entry, sink: (FlexcilRecording) -> String) {
        var parsed: FlexcilAudio.Parsed? = null
        try {
            val sourceKey = entry.name.substringAfterLast('/').substringBeforeLast('.').uppercase(Locale.ROOT)
            parsed = FlexcilAudio.read(zip, entry, sourceKey, work, checkActive, expandedBudget)
            val audio = parsed.recording
            checkActive()
            if (!sourceRecordings.add(audio.sourceKey)) {
                audioFailures++
                unmatchedAudioRefs += parsed.invalidOffsets + parsed.offsets.size
                return
            }
            val localId = sink(audio)
            if (localId.isBlank()) {
                audioFailures++
                unmatchedAudioRefs += parsed.invalidOffsets + parsed.offsets.size
                return
            }
            recordingsImported++
            unmatchedAudioRefs += parsed.invalidOffsets
            for (offset in parsed.offsets) {
                val key = StrokeAudioKey(offset.documentKey, offset.pageKey, offset.strokeKey)
                if (key in ambiguousAudioLinks) continue
                val link = FlexcilInk.RecordingLink(localId, offset.offsetMs)
                val previous = audioLinks[key]
                if (previous == null && trackedAudioRefs < MAX_AUDIO_LINKS) {
                    trackedAudioRefs++
                    audioLinks[key] = link
                } else if (previous == null) unmatchedAudioRefs++
                else if (previous != link) {
                    audioLinks.remove(key)
                    ambiguousAudioLinks += key
                }
            }
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException || e is FlexcilArchive.ResourceLimitExceeded) throw e
            audioFailures++
            reportLine("  ! recording ${entry.name}\t${e.javaClass.simpleName}")
        } finally {
            parsed?.recording?.audio?.delete()
        }
    }

    private fun audioLink(documentKey: String?, pageKey: String?, strokeKey: String): FlexcilInk.RecordingLink? {
        val key = StrokeAudioKey(uuidKey(documentKey) ?: return null, uuidKey(pageKey) ?: return null,
            uuidKey(strokeKey) ?: return null)
        return audioLinks[key]
    }

    /** [ctx] = path segments of this archive inside its parents (for folders and titles). */
    fun walk(file: File, ctx: List<String>, depth: Int) {
        checkActive()
        FlexcilArchive.openZip(file).use { zip ->
            val entries = zip.entries.filter { !it.dir }
            checkActive()
            val label = if (ctx.isEmpty()) "/" else ctx.joinToString("/")
            reportLine("zip\t$label\t${entries.size} entries\t${zip.kind}")
            entries.forEach { checkActive(); reportLine("  ${it.name}\t${it.size}\t${it.csize}") }
            if (entries.any { FlexcilArchive.isFlexcilEntry(it.name) }) flexcil = true
            val byLower = entries.associateBy { it.name.lowercase() }

            // Import audio IDs before this archive's documents so drawings can link to the new local recording IDs.
            recordingSink?.let { record ->
                val recordings = entries.filter { it.name.endsWith(".fab", true) }
                recordings.forEachIndexed { i, e ->
                    checkActive()
                    attemptRecording()
                    progress("녹음 복원 중 (${i + 1}/${recordings.size})")
                    restoreRecording(zip, e, record)
                }
            }

            // documents.list first, so documents later in this archive (or nested ones) get their titles.
            entries.filter { it.name.lowercase().endsWith("documents.list") }.forEach { e ->
                checkActive()
                runCatching { readList(zip, e) }.onFailure {
                    if (it is java.util.concurrent.CancellationException || it is FlexcilArchive.ResourceLimitExceeded) throw it
                    reportLine("  ! documents.list: ${it.javaClass.simpleName}")
                }
            }

            // Document roots: directories holding attachment/PDF/<id>.
            val pdfRe = Regex("^(.*/)?attachment/pdf/[^/]+$", RegexOption.IGNORE_CASE)
            val roots = (entries.mapNotNull { e -> pdfRe.find(e.name)?.groupValues?.get(1) } +
                if (restoreSink != null) entries.filter { it.name.endsWith("pages.index", true) }
                    .map { it.name.substringBeforeLast('/', "").let { root -> if (root.isEmpty()) "" else "$root/" } }
                else emptyList()).distinct()
            val claimed = HashSet<String>()
            for (root in roots) {
                checkActive()
                attemptDocument()
                val rootLower = root.lowercase()
                // A root "" spans the whole archive: only its attachment/ PDFs are its own, other PDFs stay loose.
                entries.filter {
                    val lower = it.name.lowercase()
                    lower.startsWith(rootLower) && !isNestedArchive(lower) &&
                        (!lower.endsWith(".pdf") || lower.startsWith(rootLower + "attachment/pdf/"))
                }.forEach { claimed += it.name }
                try {
                    readDocument(zip, entries, byLower, root, ctx, file)
                } catch (e: Exception) {
                    recordFailure("document ${root.ifEmpty { "/" }}", e)
                }
            }

            for (e in entries) {
                checkActive()
                if (e.name in claimed) continue
                val lower = e.name.lowercase()
                when {
                    lower.endsWith(".pdf") -> { attemptDocument(); loosePdf(zip, e, ctx) }
                    isNestedArchive(e.name) && depth < FlexcilArchive.MAX_DEPTH -> {
                        attemptNestedArchive()
                        val nested = tmp(".zip")
                        try {
                            FlexcilArchive.extract(zip, e, nested, checkActive, expandedBudget)
                            if (FlexcilArchive.isZip(nested)) {
                                progress("백업 안 파일 여는 중 ·${e.name.substringAfterLast('/')}")
                                walk(nested, ctx + e.name.split('/').filter { it.isNotEmpty() }, depth + 1)
                            }
                        } catch (error: Exception) {
                            recordFailure(e.name, error)
                        } finally {
                            nested.delete()
                        }
                    }
                }
            }
        }
    }

    private fun isNestedArchive(path: String): Boolean {
        val l = path.lowercase()
        return l.endsWith(".flx") || l.endsWith(".flex") || l.endsWith(".zip")
    }

    private fun readList(zip: Zip, e: Zip.Entry) {
        val text = FlexcilArchive.inflateWithHeader(zip.readBytes(e, FlexcilArchive.MAX_JSON, checkActive, expandedBudget), checkActive, expandedBudget) ?: return
        if (!hasSafeJsonStructure(text)) throw IOException("문서 목록 구조가 너무 복잡해요")
        checkActive()
        val root = FlexcilArchive.json.parseToJsonElement(text)
        fun visit(node: JsonElement, path: List<String>) {
            checkActive()
            when (node) {
                is JsonArray -> node.forEach { visit(it, path) }
                is JsonObject -> {
                    val name = node.str("name")?.trim()?.takeIf { it.isNotEmpty() }
                    val doc = node.str("document")?.trim()?.takeIf { it.isNotEmpty() }
                    if (doc != null) {
                        val id = FlexcilArchive.docId(doc)
                        if (id in duplicateListed) Unit
                        else if (listed.containsKey(id)) { listed.remove(id); duplicateListed += id }
                        else listed[id] = name to path
                    }
                    node["children"]?.let { visit(it, if (name != null && doc == null) path + name else path) }
                }
                else -> Unit
            }
        }
        visit(root, emptyList())
        reportLine("  documents.list\t${listed.size} documents")
    }

    private fun readDocument(zip: Zip, entries: List<Zip.Entry>, byLower: Map<String, Zip.Entry>, root: String, ctx: List<String>, source: File) {
        docs++
        fun entry(rel: String) = byLower[(root + rel).lowercase()]
        fun jsonOf(rel: String): JsonElement? = entry(rel)?.let { e ->
            checkActive()
            try {
                val text = String(zip.readBytes(e, FlexcilArchive.MAX_JSON, checkActive, expandedBudget), Charsets.UTF_8)
                if (!hasSafeJsonStructure(text)) throw IOException("JSON 구조가 너무 복잡해요")
                FlexcilArchive.json.parseToJsonElement(text)
            } catch (error: Exception) {
                if (error is java.util.concurrent.CancellationException || error is FlexcilArchive.ResourceLimitExceeded) throw error
                null
            }
        }
        val info = jsonOf("info") as? JsonObject
        val sourceKey = uuidKey(info?.str("key"))
        if (restoreSink != null && sourceKey != null && !sourceDocuments.add(sourceKey)) {
            throw IOException("원본 문서 식별자가 중복됐어요")
        }
        val indexEntry = entry("pages.index")
        val index = jsonOf("pages.index") as? JsonArray
        if (restoreSink != null && indexEntry != null && index == null) throw IOException("페이지 정보를 읽을 수 없어요")
        if (index != null) {
            if (index.size > FileKind.MAX_IMPORT_PAGES) throw FlexcilArchive.ResourceLimitExceeded("문서 페이지가 너무 많아요")
            if (index.size > FlexcilArchive.MAX_TOTAL_PAGES - totalPages) throw FlexcilArchive.ResourceLimitExceeded("전체 페이지가 너무 많아요")
            totalPages += index.size
        }
        val inkBudget = InkBudget()
        val indexedKeys = index?.mapNotNull {
            checkActive()
            (it as? JsonObject)?.str("key")?.lowercase()
        }?.toSet().orEmpty()

        // Title and folder: documents.list > info.name > the document's own path name > the outer file name.
        val segments = ctx + root.split('/').filter { it.isNotEmpty() }
        val docName = segments.lastOrNull()?.let { FlexcilArchive.stripExt(it) }
        val listedAs = docName?.let { listed[FlexcilArchive.docId(it)] }
        val title = listedAs?.first
            ?: info?.str("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: docName?.takeIf { !FlexcilArchive.isUuid(it) }
            ?: outerName?.let { FlexcilArchive.stripExt(it) }?.takeIf { it.isNotBlank() }
            ?: "가져온 노트"
        val folder = listedAs?.second?.takeIf { it.isNotEmpty() }?.joinToString("/") ?: FlexcilArchive.folderOf(segments.dropLast(1))

        val pdfPrefix = (root + "attachment/pdf/").lowercase()
        val pdfs = entries.filter { it.name.lowercase().startsWith(pdfPrefix) && it.name.length > pdfPrefix.length }
        if (pdfs.size > FlexcilArchive.MAX_PDFS_PER_DOCUMENT) {
            throw FlexcilArchive.ResourceLimitExceeded("문서 PDF가 너무 많아요")
        }
        val pdfByKey = pdfs.associateBy { it.name.substringAfterLast('/').substringBeforeLast('.').uppercase() }

        restoreSink?.let { restore ->
            progress("노트 복원 중 · $title")
            val files = linkedMapOf<String, File>()
            try {
                pdfByKey.forEach { (key, e) ->
                    checkActive()
                    val target = tmp(".pdf")
                    files[key] = target
                    FlexcilArchive.extract(zip, e, target, checkActive, expandedBudget)
                    if (!FlexcilArchive.isPdf(target)) throw IOException("PDF 배경을 읽을 수 없어요: ${e.name}")
                }
                val nativePages = index?.takeIf { it.isNotEmpty() }?.map { el ->
                    checkActive()
                    val page = el as? JsonObject ?: throw IOException("페이지 정보를 읽을 수 없어요")
                    FlexcilDocument.readPage(page) { key ->
                        pageInk(zip, entry("objects/$key.drawings"), entry("objects/$key.shapes"), entry("objects/$key.images"), sourceKey, key, inkBudget)
                    }
                }
                countUnreferencedImages(entries, root, indexedKeys)
                if (nativePages.isNullOrEmpty() && files.isEmpty()) throw IOException("노트에 복원할 페이지가 없어요")
                val original = source.takeIf { root.isEmpty() &&
                    (ctx.lastOrNull()?.endsWith(".flx", true) == true || outerName?.endsWith(".flx", true) == true) }
                restore(FlexcilDocument(title, folder, files, nativePages, original,
                    FlexcilDocument.timestamp(info, "createDate"), FlexcilDocument.timestamp(info, "modifiedDate"), sourceKey))
                strokes += nativePages.orEmpty().sumOf { it.strokes.size }
                books++
            } finally {
                files.values.forEach { it.delete() }
            }
            return
        }

        // Pages per attachment, in Flexcil's page order.
        val groups = LinkedHashMap<String, MutableList<Page>>()
        var pagesWithoutPdf = 0
        index?.forEach { el ->
            checkActive()
            val p = el as? JsonObject ?: return@forEach
            val att = p["attachmentPage"] as? JsonObject
            val fileKey = att?.str("file")?.substringBeforeLast('.')?.uppercase()
            val pdfIndex = (att?.get("index") as? JsonPrimitive)?.intOrNull
            if (fileKey == null || pdfIndex == null || pdfIndex < 0 || fileKey !in pdfByKey) { pagesWithoutPdf++; return@forEach }
            val key = p.str("key")
            val rotate = (p["rotate"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
            val rotated = rotate % 360.0 != 0.0
            val ink = key?.let {
                pageInk(zip, entry("objects/$it.drawings"), entry("objects/$it.shapes"), entry("objects/$it.images"), uuidKey(info?.str("key")), it, inkBudget)
            }.orEmpty()
            if (rotated && ink.isNotEmpty()) { inkLost += ink.size }
            groups.getOrPut(fileKey) { ArrayList() } += Page(key, pdfIndex, rotated, if (rotated) emptyList() else ink)
        }
        if (pagesWithoutPdf > 0) reportLine("  ! $root: $pagesWithoutPdf pages without a PDF background")
        // Ink pages that exist but no page refers to (pages.index missing / unknown): ink cannot be placed.
        if (index.isNullOrEmpty()) {
            val inkFiles = entries.count { it.name.lowercase().startsWith((root + "objects/").lowercase()) && it.name.lowercase().endsWith(".drawings") }
            if (inkFiles > 0) { inkLost += inkFiles; reportLine("  ! $root: pages.index empty or unreadable, $inkFiles ink files skipped") }
        }
        countUnreferencedImages(entries, root, indexedKeys)

        // Attachments no page refers to (or no pages.index at all) are imported whole.
        val order = groups.keys.toList() + pdfByKey.keys.filter { it !in groups }
        var n = 0
        for (key in order) {
            checkActive()
            val e = pdfByKey[key] ?: continue
            val pdf = tmp(".pdf")
            try {
                FlexcilArchive.extract(zip, e, pdf, checkActive, expandedBudget)
                if (!FlexcilArchive.isPdf(pdf)) {
                    recordFailure(e.name, IOException("not a PDF"))
                    continue
                }
                n++
                val bookTitle = if (n == 1) title else "$title ($n)"
                progress("노트 만드는 중 · $bookTitle")
                val pages = groups[key]
                pages?.forEach { p -> checkActive(); strokes += p.strokes.size }
                sink(Book(bookTitle, folder, pdf, pages))
                books++
            } finally {
                pdf.delete()
            }
        }
    }

    /** Strokes of one page; unreadable ones and shapes are counted in [inkLost]. */
    private fun pageInk(
        zip: Zip, drawings: Zip.Entry?, shapes: Zip.Entry?, images: Zip.Entry?, documentKey: String?, pageKey: String?, inkBudget: InkBudget,
    ): List<FlexcilInk.NormStroke> {
        checkActive()
        images?.let(::countImage)
        if (shapes != null && shapes.size > 2) {
            val count = try {
                val text = String(zip.readBytes(shapes, FlexcilArchive.MAX_JSON, checkActive, expandedBudget), Charsets.UTF_8)
                if (!hasSafeJsonStructure(text)) throw IOException("JSON 구조가 너무 복잡해요")
                (FlexcilArchive.json.parseToJsonElement(text) as? JsonArray)?.size
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException || e is FlexcilArchive.ResourceLimitExceeded) throw e
                null
            } ?: 1
            inkLost += count
        }
        drawings ?: return emptyList()
        if (drawings.size in 0L..2L) return emptyList()
        val parsed = runCatching {
            FlexcilInk.parseDrawings(
                String(zip.readBytes(drawings, FlexcilArchive.MAX_JSON, checkActive, expandedBudget), Charsets.UTF_8),
                checkActive,
                { strokeKey -> audioLink(documentKey, pageKey, strokeKey) },
                maxStrokes = FlexcilArchive.MAX_DOCUMENT_STROKES - inkBudget.strokes,
                maxPoints = FlexcilArchive.MAX_DOCUMENT_STROKE_POINTS - inkBudget.points,
            )
        }.getOrElse {
            if (it is java.util.concurrent.CancellationException || it is FlexcilArchive.ResourceLimitExceeded) throw it
            FlexcilInk.Parsed(emptyList(), 1)
        }
        inkBudget.strokes += parsed.strokes.size
        inkBudget.points += parsed.strokes.sumOf { it.count }
        val docKey = uuidKey(documentKey)
        val page = uuidKey(pageKey)
        if (docKey != null && page != null) parsed.strokes.forEach { stroke ->
            stroke.key?.let { uuidKey(it) }?.let { matchedAudioLinks += StrokeAudioKey(docKey, page, it) }
        }
        if (parsed.unreadable > 0) reportLine("  ! ${drawings.name}: ${parsed.unreadable} strokes unreadable")
        inkLost += parsed.unreadable
        return parsed.strokes
    }

    private fun countUnreferencedImages(entries: List<Zip.Entry>, root: String, indexedKeys: Set<String>) {
        val prefix = (root + "objects/").lowercase()
        entries.forEach { entry ->
            checkActive()
            if (entry.name.lowercase().startsWith(prefix) && entry.name.lowercase().endsWith(".images") &&
                entry.name.substringAfterLast('/').removeSuffix(".images").lowercase() !in indexedKeys
            ) countImage(entry)
        }
    }

    private fun countImage(entry: Zip.Entry) {
        if (entry.size in 0L..2L || !countedImages.add(entry.name.lowercase())) return
        imagePagesLost++
        reportLine("  ! ${entry.name}: image data not restored")
    }

    private fun loosePdf(zip: Zip, e: Zip.Entry, ctx: List<String>) {
        val pdf = tmp(".pdf")
        try {
            FlexcilArchive.extract(zip, e, pdf, checkActive, expandedBudget)
            if (!FlexcilArchive.isPdf(pdf)) {
                recordFailure(e.name, IOException("not a PDF"))
                return
            }
            val segments = ctx + e.name.split('/').filter { it.isNotEmpty() }
            val title = FlexcilArchive.stripExt(segments.last()).ifBlank { "PDF" }
            progress("PDF 가져오는 중 · $title")
            if (restoreSink != null) restoreSink.invoke(FlexcilDocument(title, FlexcilArchive.folderOf(segments.dropLast(1)), mapOf("PDF" to pdf), null))
            else sink(Book(title, FlexcilArchive.folderOf(segments.dropLast(1)), pdf, null))
            books++
        } catch (error: Exception) {
            recordFailure(e.name, error)
        } finally {
            pdf.delete()
        }
    }

    internal companion object {
        const val MAX_REPORT_CHARS = 1 shl 20
        private const val MAX_AUDIO_LINKS = 25_000
    }
}
