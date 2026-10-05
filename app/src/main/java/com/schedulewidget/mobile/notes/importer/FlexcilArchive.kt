package com.schedulewidget.mobile.notes.importer

import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.Inflater
import java.util.zip.ZipFile

/**
 * Reads Flexcil exports: a document (.flx) or a backup (.flex), both ZIP containers. Pure JVM (unit-tested).
 *
 * What is known (a real .flx, filext.com's .flex entry listing, and the viewers github.com/c0lbarator/FWebViewer and
 * github.com/janptn/flexcil-backup-viewer):
 *  - .flx: `info` (JSON: name, attachments), `pages.index` (JSON array: key, frame, rotate, attachmentPage{file,index}),
 *    `attachment/PDF/<uuid>` (the PDF backgrounds, no extension), `objects/<pageKey>.drawings` (ink, see [FlexcilInk]),
 *    `.shapes`, `.images`, `thumbnail*`, `.itemInfo`, `template.info`.
 *  - .flex backup / Flexcil sync folder: a tree under `flexcilbackup/` with `Documents/<folders>/<name>.flx` (either
 *    nested ZIP files or the same entries expanded into a directory), `Recordings/<name>.fab`, and `documents.list`
 *    (8-byte header + deflate-compressed JSON tree of {name, document: <uuid>, children}) mapping ids to titles/folders.
 *
 * Nothing here relies on exact paths: a "document root" is any directory holding `attachment/PDF/...`, nested
 * .flx/.flex/.zip entries are opened recursively, and loose `*.pdf` entries are imported as they are. Every entry
 * name and size is written to [Result.report] so an unknown layout can be diagnosed from a user's report.
 */
object FlexcilArchive {
    /** One Flexcil page: page [pdfIndex] of the book's PDF, with its ink. [rotated] pages get no ink (unverified). */
    class Page(val key: String?, val pdfIndex: Int, val rotated: Boolean, val strokes: List<FlexcilInk.NormStroke>)

    /**
     * A notebook to create: [pdf] (a temporary file, deleted after the sink returns) with [pages] in Flexcil's order,
     * or null = every page of the PDF in order, no ink. [folder] is the Flexcil folder path ("수학/1학기") or null.
     */
    class Book(val title: String, val folder: String?, val pdf: File, val pages: List<Page>?)

    class Result(
        /** Books handed to the sink. */
        val books: Int,
        /** True when the file looked like a Flexcil export at all (document roots, nested .flx, documents.list). */
        val flexcil: Boolean,
        /** Strokes converted. */
        val strokes: Int,
        /** Ink that exists but could not be read (unknown format, undecodable strokes, shapes, rotated pages). */
        val inkLost: Int,
        /** Entry listing (names and sizes only, no content) and a summary, for "진단 정보 공유". */
        val report: String,
    ) {
        internal var failedDocs: Int = 0
        internal var passwordFailures: Int = 0
        internal var imagePagesLost: Int = 0
    }

    internal const val MAX_DEPTH = 4
    private const val MAX_ENTRY = 1L shl 30
    internal const val MAX_JSON = 64L shl 20

    internal val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** True for a name Flexcil gives its exports. */
    fun isFlexcilName(name: String?): Boolean {
        val ext = name?.substringAfterLast('.', "")?.lowercase()
        return ext == "flex" || ext == "flx"
    }

    /** Cheap check of a ZIP's entry names (no extraction): does it look like a Flexcil export? */
    fun looksLikeFlexcil(file: File): Boolean = runCatching {
        openZip(file).use { z -> z.entries.any { e -> isFlexcilEntry(e.name) } }
    }.getOrDefault(false)

    internal fun isFlexcilEntry(name: String): Boolean {
        val n = name.lowercase()
        return n == "pages.index" || n.endsWith("/pages.index") || n.contains("attachment/pdf/") || n.endsWith(".flx") ||
            n.endsWith("documents.list") || n.startsWith("flexcilbackup/") || n.contains("/flexcilbackup/")
    }

    /**
     * Walks [file] (named [name]) and calls [sink] for every notebook found, in archive order. Temporary files go to
     * [work]. [progress] gets short Korean status lines. Throws IOException when [file] is not a readable ZIP.
     */
    fun read(file: File, name: String?, work: File, progress: (String) -> Unit = {}, sink: (Book) -> Unit): Result {
        val walker = FlexcilArchiveWalker(work, name, progress, sink, checkActive = {})
        walker.report.appendLine("file\t${name ?: "?"}\t${file.length()}")
        try {
            walker.walk(file, emptyList(), 0)
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            walker.report.appendLine("error\t${e.javaClass.simpleName}")
            walker.summary()
            throw FlexcilError(e.message ?: e.javaClass.simpleName, walker.report.toString(), e)
        }
        walker.summary()
        return Result(walker.books, walker.flexcil || isFlexcilName(name), walker.strokes, walker.inkLost, walker.report.toString()).also {
            it.failedDocs = walker.failedDocs
            it.passwordFailures = walker.passwordFailures
            it.imagePagesLost = walker.imagePagesLost
        }
    }

    /** Restores each original notebook once, keeping mixed PDF sources and blank pages in their original order. */
    fun restore(file: File, name: String?, work: File, progress: (String) -> Unit = {}, sink: (FlexcilDocument) -> Unit): Result =
        restoreInternal(file, name, work, progress, {}, sink)

    /** Same restore with cooperative cancellation checks during archive reads and extraction. */
    internal fun restoreCancellable(
        file: File, name: String?, work: File, progress: (String) -> Unit, checkActive: () -> Unit,
        sink: (FlexcilDocument) -> Unit,
    ): Result = restoreInternal(file, name, work, progress, checkActive, sink)

    private fun restoreInternal(
        file: File, name: String?, work: File, progress: (String) -> Unit, checkActive: () -> Unit,
        sink: (FlexcilDocument) -> Unit,
    ): Result {
        val walker = FlexcilArchiveWalker(work, name, progress, {}, sink, checkActive)
        walker.report.appendLine("file\t${name ?: "?"}\t${file.length()}")
        try {
            walker.walk(file, emptyList(), 0)
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            walker.report.appendLine("error\t${e.javaClass.simpleName}")
            walker.summary()
            throw FlexcilError(e.message ?: e.javaClass.simpleName, walker.report.toString(), e)
        }
        walker.summary()
        return Result(walker.books, walker.flexcil || isFlexcilName(name), walker.strokes, walker.inkLost, walker.report.toString()).also {
            it.failedDocs = walker.failedDocs
            it.passwordFailures = walker.passwordFailures
            it.imagePagesLost = walker.imagePagesLost
        }
    }

    /** A failure with the diagnostic report gathered so far. */
    class FlexcilError(message: String, val report: String, cause: Throwable?) : IOException(message, cause)

    /** A PDF background rejected by PdfRenderer because it is password-protected. */
    internal class PasswordProtectedPdf(cause: SecurityException) : IOException("암호가 걸린 PDF 배경이에요", cause)

    // ---- helpers ----

    /** Folder path from archive segments: what follows "Documents" (or the whole path), without backup wrappers. */
    internal fun folderOf(segments: List<String>): String? {
        val docs = segments.indexOfLast { it.equals("documents", ignoreCase = true) }
        val rest = (if (docs >= 0) segments.drop(docs + 1) else segments).filterNot {
            it.equals("flexcilbackup", ignoreCase = true) || it.startsWith("Flexcil Backup", ignoreCase = true) ||
                it.lowercase().let { l -> l.endsWith(".flex") || l.endsWith(".zip") || l.endsWith(".flx") }
        }
        return rest.joinToString("/").takeIf { it.isNotBlank() }
    }

    internal fun stripExt(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name.trim()
        return when (name.substring(dot + 1).lowercase()) {
            "flx", "flex", "pdf", "zip" -> name.substring(0, dot).trim()
            else -> name.trim()
        }
    }

    internal fun docId(name: String) = stripExt(name.substringAfterLast('/')).uppercase()

    private val UUID_RE = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
    internal fun isUuid(s: String) = UUID_RE.matches(s)

    internal fun isPdf(f: File): Boolean = f.inputStream().use { s ->
        val head = ByteArray(1024)
        val n = s.read(head)
        n > 4 && String(head, 0, n, Charsets.ISO_8859_1).contains("%PDF-")
    }

    internal fun isZip(f: File): Boolean = f.inputStream().use { s ->
        val h = ByteArray(4)
        s.read(h) == 4 && h[0] == 0x50.toByte() && h[1] == 0x4B.toByte() && h[2] == 0x03.toByte() && h[3] == 0x04.toByte()
    }

    internal fun extract(zip: Zip, e: Zip.Entry, out: File) = extract(zip, e, out) {}

    internal fun extract(zip: Zip, e: Zip.Entry, out: File, checkActive: () -> Unit) {
        if (e.size > MAX_ENTRY) throw IOException("항목이 너무 커요: ${e.name}")
        zip.open(e).use { input -> out.outputStream().use { copyLimited(input, it, MAX_ENTRY, e.name, checkActive) } }
    }

    private fun copyLimited(input: InputStream, out: java.io.OutputStream, limit: Long, name: String, checkActive: () -> Unit = {}) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkActive()
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > limit) throw IOException("항목이 너무 커요: $name")
            out.write(buf, 0, n)
        }
    }

    /** Flexcil's .itemInfo / documents.list: 8-byte header, then zlib or raw deflate (or plain JSON). */
    internal fun inflateWithHeader(bytes: ByteArray): String? = inflateWithHeader(bytes) {}

    internal fun inflateWithHeader(bytes: ByteArray, checkActive: () -> Unit): String? {
        checkActive()
        val plain = String(bytes, Charsets.UTF_8).trimStart()
        if (plain.startsWith("[") || plain.startsWith("{")) return plain
        if (bytes.size <= 8) return null
        for (nowrap in listOf(false, true)) {
            val inf = Inflater(nowrap)
            try {
                inf.setInput(bytes, 8, bytes.size - 8)
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (!inf.finished()) {
                    checkActive()
                    val n = inf.inflate(buf)
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_JSON) break
                }
                if (out.size() > 0) return out.toString("UTF-8")
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                // try the other wrapping
            } finally {
                inf.end()
            }
        }
        return null
    }

    // ---- ZIP access: java.util.zip, with commons-compress for archives it rejects (odd name encodings etc.) ----

    internal interface Zip : Closeable {
        class Entry(val name: String, val raw: Any, val size: Long, val csize: Long, val dir: Boolean)

        val kind: String
        val entries: List<Entry>
        fun open(e: Entry): InputStream
        fun readBytes(e: Entry, limit: Long): ByteArray {
            return readBytes(e, limit) {}
        }
        fun readBytes(e: Entry, limit: Long, checkActive: () -> Unit): ByteArray {
            val out = ByteArrayOutputStream()
            open(e).use { copyLimited(it, out, limit, e.name, checkActive) }
            return out.toByteArray()
        }
    }

    private fun normalize(name: String) = name.replace('\\', '/').trimStart('/')

    internal fun openZip(file: File): Zip = try {
        JavaZip(file)
    } catch (e: Exception) {
        if (e is IOException && !isZip(file)) throw e
        CommonsZip(file)
    }

    private class JavaZip(file: File) : Zip {
        private val zip = ZipFile(file)
        override val kind = "java"
        override val entries: List<Zip.Entry> = try {
            zip.entries().asSequence().map { Zip.Entry(normalize(it.name), it, it.size, it.compressedSize, it.isDirectory) }.toList()
        } catch (e: Exception) {
            zip.close()
            throw e
        }
        override fun open(e: Zip.Entry): InputStream = zip.getInputStream(e.raw as java.util.zip.ZipEntry)
        override fun close() = zip.close()
    }

    private class CommonsZip(file: File) : Zip {
        private val zip = org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(file).get()
        override val kind = "commons"
        override val entries: List<Zip.Entry> = zip.entries.asSequence()
            .map { Zip.Entry(normalize(it.name), it, it.size, it.compressedSize, it.isDirectory) }.toList()
        override fun open(e: Zip.Entry): InputStream =
            zip.getInputStream(e.raw as org.apache.commons.compress.archivers.zip.ZipArchiveEntry)
        override fun close() = zip.close()
    }
}
