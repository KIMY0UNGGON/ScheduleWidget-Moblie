package com.schedulewidget.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.schedulewidget.mobile.notes.NoteImport
import com.schedulewidget.mobile.notes.NoteMeta
import com.schedulewidget.mobile.notes.NoteStore
import com.schedulewidget.mobile.notes.library.NoteFolders
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Synthetic .flex/.flx imports through NoteImport: backups land in their own folder, single documents do not. */
class FlexFolderInstrumentation : Instrumentation() {
    private var exportDemo = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        exportDemo = arguments?.getString("exportDemo") == "true"
        start()
    }

    override fun onStart() {
        val root = File(targetContext.cacheDir, "flex-folder-${System.nanoTime()}")
        val files = File(root, "files").apply { mkdirs() }
        val cache = File(root, "cache").apply { mkdirs() }
        val inputs = File(root, "inputs").apply { mkdirs() }
        val prefsPrefix = "flex_folder_test_${System.nanoTime()}_"
        val prefsUsed = HashSet<String>()
        val scratch = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
            override fun getCacheDir() = cache
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                prefsUsed += prefsPrefix + name
                return targetContext.getSharedPreferences(prefsPrefix + name, mode)
            }
        }
        val realFolders = targetContext.getSharedPreferences("notes_library", Context.MODE_PRIVATE)
            .getStringSet("folders", emptySet()).orEmpty().toSet()
        val results = ArrayList<String>()
        var failures = 0
        try {
            runBlocking {
                fun input(name: String, bytes: ByteArray) =
                    Uri.fromFile(File(inputs, name).apply { parentFile!!.mkdirs(); writeBytes(bytes) })
                fun added(before: List<NoteMeta>) = NoteStore.list(scratch).filter { n -> before.none { it.id == n.id } }
                suspend fun startAndWait(uri: Uri): NoteImport.Done {
                    NoteImport.consumeDone()
                    // The previous background job may still be unwinding after publishing its result.
                    withTimeout(10_000) { while (!NoteImport.start(scratch, uri, NoteImport.Convert.AUTO, null, open = true)) delay(50) }
                    return withTimeout(60_000) { NoteImport.done.first { it != null } }!!.also { NoteImport.consumeDone() }
                }

                // 1. Backup with a mixed-source notebook and a loose PDF, imported into a selected parent folder.
                val backup = zip(
                    "flexcilbackup/Documents/과목/물리/Mixed.flx" to mixedFlx(),
                    "flexcilbackup/Documents/영어/handout.pdf" to pdf(1),
                )
                if (exportDemo) File(targetContext.cacheDir, "folder-ui-demo.flex").writeBytes(backup)
                val first = NoteImport.import(scratch, input("Lecture Backup.flex", backup), folder = "부모")
                check(first.isSuccess) { "Backup import failed: ${first.exceptionOrNull()}" }
                val firstNotes = NoteStore.list(scratch)
                check(firstNotes.size == 2) { "Expected 2 notebooks, got ${firstNotes.size}" }
                val mixed = firstNotes.single { it.title == "Mixed" }
                val handout = firstNotes.single { it.title == "handout" }
                check(mixed.folder == "부모/Lecture Backup/과목/물리") { "Mixed folder: ${mixed.folder}" }
                check(handout.folder == "부모/Lecture Backup/영어") { "Loose PDF folder: ${handout.folder}" }
                check("부모/Lecture Backup" in NoteFolders.all(scratch, firstNotes)) { "Backup folder is not listed" }
                checkMixed(scratch, files, mixed)
                results += "PASS: backup -> 부모/Lecture Backup -> original subfolders; mixed PDF/blank/ink notebook reloads"

                // 2. Same backup name again: a new sibling folder, nothing merged into the first one.
                val second = NoteImport.import(scratch, input("again/Lecture Backup.flex", backup), folder = "부모")
                check(second.isSuccess) { "Repeated import failed: ${second.exceptionOrNull()}" }
                val secondNotes = added(firstNotes)
                check(secondNotes.size == 2 && secondNotes.all { NoteFolders.isWithin(it.folder, "부모/Lecture Backup (2)") }) {
                    "Repeated backup folders: ${secondNotes.map { it.folder }}"
                }
                check(NoteStore.list(scratch).count { NoteFolders.isWithin(it.folder, "부모/Lecture Backup") } == 2) {
                    "Repeated import changed the first backup folder"
                }
                results += "PASS: repeated backup name gets 부모/Lecture Backup (2)"

                // 3. A standalone .flx keeps its existing behaviour: no wrapper folder.
                val beforeSolo = NoteStore.list(scratch)
                val solo = NoteImport.import(scratch, input("Solo.flx", mixedFlx("Solo")), folder = "부모")
                check(solo.isSuccess) { "Standalone import failed: ${solo.exceptionOrNull()}" }
                val soloNote = added(beforeSolo).single()
                check(soloNote.folder == "부모") { "Standalone .flx was wrapped: ${soloNote.folder}" }
                checkMixed(scratch, files, soloNote)
                results += "PASS: standalone .flx stays in the selected folder"

                // Storage operations must remap descendants and saved empty folders without touching siblings.
                NoteFolders.add(scratch, "부모/Lecture Backup/빈 폴더/하위")
                NoteFolders.rename(scratch, "부모/Lecture Backup", "부모/Renamed Backup")
                val renamed = NoteStore.list(scratch)
                check(firstNotes.all { original -> renamed.single { it.id == original.id }.folder ==
                    NoteFolders.remap(original.folder, "부모/Lecture Backup", "부모/Renamed Backup") })
                check(secondNotes.all { original -> renamed.single { it.id == original.id }.folder == original.folder })
                check("부모/Renamed Backup/빈 폴더/하위" in NoteFolders.all(scratch, renamed))
                NoteFolders.remove(scratch, "부모/Renamed Backup")
                val promoted = NoteStore.list(scratch)
                check(promoted.single { it.id == mixed.id }.folder == "부모/과목/물리")
                check(promoted.single { it.id == handout.id }.folder == "부모/영어")
                check("부모/빈 폴더/하위" in NoteFolders.all(scratch, promoted))
                checkMixed(scratch, files, promoted.single { it.id == mixed.id })
                results += "PASS: rename/remove remap descendants and empty folders; sources, ink and siblings preserved"

                // 4. Async path: a one-document backup still opens its folder, not the notebook.
                val done = startAndWait(input("Single.flex", zip("Documents/Only.flx" to mixedFlx("Only"))))
                check(!done.open && done.count == 1) { "Backup Done open=${done.open} count=${done.count}" }
                check(done.importedFolder == "Single") { "Backup Done folder=${done.importedFolder}" }
                check(done.note.folder == "Single") { "Single backup note folder=${done.note.folder}" }
                val alone = startAndWait(input("Alone.flx", mixedFlx("Alone")))
                check(alone.open && alone.importedFolder == null && alone.note.folder == null) {
                    "Standalone Done open=${alone.open} folder=${alone.importedFolder}/${alone.note.folder}"
                }
                results += "PASS: async backup Done -> importedFolder, open=false; standalone .flx opens directly"

                // Bare, uncompressed PDF entries expose their PDF magic near the ZIP start. This uses the copy/
                // file-classification route because the archive has no native document marker for the fast path.
                val stored = storedZip("Math/one.pdf" to pdf(2), "English/two.pdf" to pdf(1))
                check(String(stored.take(1024).toByteArray(), Charsets.ISO_8859_1).contains("%PDF-"))
                val storedDone = startAndWait(input("Stored PDFs.flex", stored))
                check(storedDone.importedFolder == "Stored PDFs" && !storedDone.open && storedDone.count == 2)
                val storedNotes = NoteStore.list(scratch).filter { NoteFolders.isWithin(it.folder, "Stored PDFs") }
                check(storedNotes.size == 2 && storedNotes.all { it.source == "flexcil" })
                check(storedNotes.map { it.folder }.toSet() == setOf("Stored PDFs/Math", "Stored PDFs/English"))
                check(storedNotes.map { it.pageCount }.sorted() == listOf(1, 2))
                storedNotes.forEach { meta ->
                    val note = checkNotNull(NoteStore.load(scratch, meta.id))
                    note.pages.forEach { page -> check(pageCount(NoteStore.sourceFile(scratch, meta.id, page)) > page.pdf) }
                }
                results += "PASS: stored PDF headers inside .flex select backup/folder import and link both PDFs"

                // Extensionless download (file Uri: no MIME, no .flex name) of a native backup whose first entry is a
                // stored PDF: only the content can classify it, and it must still become a backup folder, not a PDF.
                val bare = storedZip("flexcilbackup/Documents/Math/one.pdf" to pdf(2), "flexcilbackup/Documents/Art/two.pdf" to pdf(1))
                check(String(bare.take(1024).toByteArray(), Charsets.ISO_8859_1).contains("%PDF-"))
                val bareDone = startAndWait(input("Downloaded backup", bare))
                check(bareDone.importedFolder == "Downloaded backup" && !bareDone.open && bareDone.count == 2) {
                    "Extensionless Done folder=${bareDone.importedFolder} open=${bareDone.open} count=${bareDone.count}"
                }
                val bareNotes = NoteStore.list(scratch).filter { NoteFolders.isWithin(it.folder, "Downloaded backup") }
                check(bareNotes.map { it.folder }.toSet() == setOf("Downloaded backup/Math", "Downloaded backup/Art")) {
                    "Extensionless folders: ${bareNotes.map { it.folder }}"
                }
                check(bareNotes.all { it.source == "flexcil" } && bareNotes.map { it.pageCount }.sorted() == listOf(1, 2))
                results += "PASS: extensionless native backup with a leading stored PDF imports as a folder, not a PDF"
            }
            val after = targetContext.getSharedPreferences("notes_library", Context.MODE_PRIVATE)
                .getStringSet("folders", emptySet()).orEmpty().toSet()
            check(after == realFolders) { "The device's real folder list changed" }
            results += "PASS: real notes and folder list untouched"
        } catch (e: Throwable) {
            failures++
            results += "FAIL: ${e.stackTraceToString()}"
        } finally {
            runBlocking { NoteStore.writeMutex.lock(); try { root.deleteRecursively() } finally { NoteStore.writeMutex.unlock() } }
            prefsUsed.forEach { targetContext.deleteSharedPreferences(it) }
        }
        val text = results.joinToString("\n") + "\nFailures: $failures\n"
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\n$text")
        })
    }

    /** Pages: A[1] with ink, blank with ink, B[0]; both sources kept, ink editable after reload. */
    private fun checkMixed(context: Context, files: File, meta: NoteMeta) {
        val note = checkNotNull(NoteStore.load(context, meta.id)) { "${meta.title} does not reload" }
        check(note.source == "flexcil") { "${meta.title} source=${note.source}" }
        check(note.pages.map { it.isPdf } == listOf(true, false, true)) { "${meta.title} page kinds changed" }
        check(note.pages.map { it.pdf }.let { it[0] == 1 && it[2] == 0 }) { "${meta.title} PDF page indices changed" }
        val sources = note.pages.filter { it.isPdf }.map { NoteStore.sourceFile(context, meta.id, it) }
        check(sources.distinct().size == 2) { "${meta.title} sources were merged or split: $sources" }
        check(sources.map(::pageCount) == listOf(2, 1)) { "${meta.title} source PDFs do not reopen" }
        val strokes = note.pages.map { NoteStore.loadInk(context, meta.id, it.uid).strokes.size }
        check(strokes == listOf(1, 1, 0)) { "${meta.title} editable ink: $strokes" }
        check(File(files, "notes/${meta.id}/original.flx").isFile) { "${meta.title} original .flx not kept" }
    }

    private fun pageCount(file: File): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }

    private fun mixedFlx(title: String = "Mixed"): ByteArray = zip(
        "info" to """{"name":"$title"}""".toByteArray(),
        "pages.index" to """[
          {"key":"pa","attachmentPage":{"file":"A","index":1}},
          {"key":"pb","frame":{"width":400,"height":300}},
          {"key":"pc","attachmentPage":{"file":"B","index":0}}
        ]""".toByteArray(),
        "attachment/PDF/A" to pdf(2), "attachment/PDF/B" to pdf(1),
        "objects/pa.drawings" to drawing(), "objects/pb.drawings" to drawing(),
    )

    private fun pdf(pages: Int): ByteArray {
        val doc = PdfDocument()
        try {
            repeat(pages) { i ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(300, 400, i + 1).create())
                page.canvas.drawText("page ${i + 1}", 20f, 40f, Paint())
                doc.finishPage(page)
            }
            return ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
        } finally {
            doc.close()
        }
    }

    private fun drawing(): ByteArray {
        val points = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).putInt(2)
            .putFloat(0f).putFloat(0f).putFloat(0.002f).putFloat(0.2f).putFloat(0.1f).putFloat(0.002f).array()
        return ("""[{"type":1,"figure":0,"points":"${Base64.getEncoder().encodeToString(points)}",""" +
            """"start":{"x":0.2,"y":0.3},"strokeColor":4284626687}]""").toByteArray()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { archive -> entries.forEach { (name, bytes) ->
            archive.putNextEntry(ZipEntry(name)); archive.write(bytes); archive.closeEntry()
        } }
        output.toByteArray()
    }

    private fun storedZip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { archive -> entries.forEach { (name, bytes) ->
            val entry = ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(bytes) }.value
            }
            archive.putNextEntry(entry)
            archive.write(bytes)
            archive.closeEntry()
        } }
        output.toByteArray()
    }
}
