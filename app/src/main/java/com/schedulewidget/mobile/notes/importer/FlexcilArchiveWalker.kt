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
internal class FlexcilArchiveWalker(
    val work: File, val outerName: String?, val progress: (String) -> Unit, val sink: (Book) -> Unit,
    val restoreSink: ((FlexcilDocument) -> Unit)? = null, private val checkActive: () -> Unit = {},
) {

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    val report = StringBuilder()
    var books = 0
    var strokes = 0
    var inkLost = 0
    var failedDocs = 0
    var passwordFailures = 0
    var imagePagesLost = 0
    var flexcil = false
    private var docs = 0
    private var tmpSeq = 0
    private val countedImages = HashSet<String>()
    /** documents.list: document id (upper case, no extension) → (title, folder path). */
    private val listed = HashMap<String, Pair<String?, List<String>>>()

    fun summary() {
        report.appendLine("summary\tbooks=$books\tdocs=$docs\tfailed=$failedDocs\tstrokes=$strokes\tinkLost=$inkLost\timagePagesLost=$imagePagesLost\tlisted=${listed.size}")
    }

    private fun recordFailure(path: String, e: Exception) {
        if (e is java.util.concurrent.CancellationException) throw e
        failedDocs++
        if (e is FlexcilArchive.PasswordProtectedPdf) passwordFailures++
        report.appendLine("  ! $path\t${e.javaClass.simpleName}")
    }

    private fun tmp(suffix: String) = File(work, "flx_${tmpSeq++}$suffix")

    /** [ctx] = path segments of this archive inside its parents (for folders and titles). */
    fun walk(file: File, ctx: List<String>, depth: Int) {
        checkActive()
        FlexcilArchive.openZip(file).use { zip ->
            val entries = zip.entries.filter { !it.dir }
            checkActive()
            val label = if (ctx.isEmpty()) "/" else ctx.joinToString("/")
            report.appendLine("zip\t$label\t${entries.size} entries\t${zip.kind}")
            entries.forEach { checkActive(); report.appendLine("  ${it.name}\t${it.size}\t${it.csize}") }
            if (entries.any { FlexcilArchive.isFlexcilEntry(it.name) }) flexcil = true
            val byLower = entries.associateBy { it.name.lowercase() }

            // documents.list first, so documents later in this archive (or nested ones) get their titles.
            entries.filter { it.name.lowercase().endsWith("documents.list") }.forEach { e ->
                checkActive()
                runCatching { readList(zip, e) }.onFailure {
                    if (it is java.util.concurrent.CancellationException) throw it
                    report.appendLine("  ! documents.list: ${it.javaClass.simpleName}")
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
                val rootLower = root.lowercase()
                entries.filter { it.name.lowercase().startsWith(rootLower) && !isNestedArchive(it.name.substring(root.length)) }
                    .forEach { claimed += it.name }
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
                    lower.endsWith(".pdf") -> loosePdf(zip, e, ctx)
                    isNestedArchive(e.name) && depth < FlexcilArchive.MAX_DEPTH -> {
                        val nested = tmp(".zip")
                        try {
                            FlexcilArchive.extract(zip, e, nested, checkActive)
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
        val text = FlexcilArchive.inflateWithHeader(zip.readBytes(e, FlexcilArchive.MAX_JSON, checkActive), checkActive) ?: return
        checkActive()
        val root = FlexcilArchive.json.parseToJsonElement(text)
        fun visit(node: JsonElement, path: List<String>) {
            checkActive()
            when (node) {
                is JsonArray -> node.forEach { visit(it, path) }
                is JsonObject -> {
                    val name = node.str("name")?.trim()?.takeIf { it.isNotEmpty() }
                    val doc = node.str("document")?.trim()?.takeIf { it.isNotEmpty() }
                    if (doc != null) listed[FlexcilArchive.docId(doc)] = name to path
                    node["children"]?.let { visit(it, if (name != null && doc == null) path + name else path) }
                }
                else -> Unit
            }
        }
        visit(root, emptyList())
        report.appendLine("  documents.list\t${listed.size} documents")
    }

    private fun readDocument(zip: Zip, entries: List<Zip.Entry>, byLower: Map<String, Zip.Entry>, root: String, ctx: List<String>, source: File) {
        docs++
        fun entry(rel: String) = byLower[(root + rel).lowercase()]
        fun jsonOf(rel: String): JsonElement? = entry(rel)?.let { e ->
            checkActive()
            try {
                FlexcilArchive.json.parseToJsonElement(String(zip.readBytes(e, FlexcilArchive.MAX_JSON, checkActive), Charsets.UTF_8))
            } catch (error: Exception) {
                if (error is java.util.concurrent.CancellationException) throw error
                null
            }
        }
        val info = jsonOf("info") as? JsonObject
        val indexEntry = entry("pages.index")
        val index = jsonOf("pages.index") as? JsonArray
        if (restoreSink != null && indexEntry != null && index == null) throw IOException("페이지 정보를 읽을 수 없어요")
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
        val pdfByKey = pdfs.associateBy { it.name.substringAfterLast('/').substringBeforeLast('.').uppercase() }

        restoreSink?.let { restore ->
            progress("노트 복원 중 · $title")
            val files = linkedMapOf<String, File>()
            try {
                pdfByKey.forEach { (key, e) ->
                    checkActive()
                    val target = tmp(".pdf")
                    files[key] = target
                    FlexcilArchive.extract(zip, e, target, checkActive)
                    if (!FlexcilArchive.isPdf(target)) throw IOException("PDF 배경을 읽을 수 없어요: ${e.name}")
                }
                val nativePages = index?.takeIf { it.isNotEmpty() }?.map { el ->
                    checkActive()
                    val page = el as? JsonObject ?: throw IOException("페이지 정보를 읽을 수 없어요")
                    FlexcilDocument.readPage(page) { key ->
                        pageInk(zip, entry("objects/$key.drawings"), entry("objects/$key.shapes"), entry("objects/$key.images"))
                    }
                }
                countUnreferencedImages(entries, root, indexedKeys)
                if (nativePages.isNullOrEmpty() && files.isEmpty()) throw IOException("노트에 복원할 페이지가 없어요")
                val original = source.takeIf { root.isEmpty() &&
                    (ctx.lastOrNull()?.endsWith(".flx", true) == true || outerName?.endsWith(".flx", true) == true) }
                restore(FlexcilDocument(title, folder, files, nativePages, original,
                    FlexcilDocument.timestamp(info, "createDate"), FlexcilDocument.timestamp(info, "modifiedDate")))
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
                pageInk(zip, entry("objects/$it.drawings"), entry("objects/$it.shapes"), entry("objects/$it.images"))
            }.orEmpty()
            if (rotated && ink.isNotEmpty()) { inkLost += ink.size }
            groups.getOrPut(fileKey) { ArrayList() } += Page(key, pdfIndex, rotated, if (rotated) emptyList() else ink)
        }
        if (pagesWithoutPdf > 0) report.appendLine("  ! $root: $pagesWithoutPdf pages without a PDF background")
        // Ink pages that exist but no page refers to (pages.index missing / unknown): ink cannot be placed.
        if (index.isNullOrEmpty()) {
            val inkFiles = entries.count { it.name.lowercase().startsWith((root + "objects/").lowercase()) && it.name.lowercase().endsWith(".drawings") }
            if (inkFiles > 0) { inkLost += inkFiles; report.appendLine("  ! $root: pages.index empty or unreadable, $inkFiles ink files skipped") }
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
                FlexcilArchive.extract(zip, e, pdf, checkActive)
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
    private fun pageInk(zip: Zip, drawings: Zip.Entry?, shapes: Zip.Entry?, images: Zip.Entry?): List<FlexcilInk.NormStroke> {
        checkActive()
        images?.let(::countImage)
        if (shapes != null && shapes.size > 2) {
            val count = try {
                (FlexcilArchive.json.parseToJsonElement(String(zip.readBytes(shapes, FlexcilArchive.MAX_JSON, checkActive), Charsets.UTF_8)) as? JsonArray)?.size
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                null
            } ?: 1
            inkLost += count
        }
        drawings ?: return emptyList()
        if (drawings.size in 0L..2L) return emptyList()
        val parsed = runCatching {
            FlexcilInk.parseDrawings(String(zip.readBytes(drawings, FlexcilArchive.MAX_JSON, checkActive), Charsets.UTF_8), checkActive)
        }.getOrElse {
            if (it is java.util.concurrent.CancellationException) throw it
            FlexcilInk.Parsed(emptyList(), 1)
        }
        if (parsed.unreadable > 0) report.appendLine("  ! ${drawings.name}: ${parsed.unreadable} strokes unreadable")
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
        report.appendLine("  ! ${entry.name}: image data not restored")
    }

    private fun loosePdf(zip: Zip, e: Zip.Entry, ctx: List<String>) {
        val pdf = tmp(".pdf")
        try {
            FlexcilArchive.extract(zip, e, pdf, checkActive)
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
}
