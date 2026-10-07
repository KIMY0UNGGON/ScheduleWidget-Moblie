package com.schedulewidget.mobile.notes.importer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FlexcilNativeRestoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val pdf = "%PDF-1.4\n%%EOF".toByteArray()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { archive -> entries.forEach { (name, bytes) ->
            archive.putNextEntry(ZipEntry(name)); archive.write(bytes); archive.closeEntry()
        } }
        output.toByteArray()
    }

    private fun drawing(type: Int = 1): String {
        val points = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).putInt(2)
            .putFloat(0f).putFloat(0f).putFloat(0.002f).putFloat(0.2f).putFloat(0.1f).putFloat(0.002f).array()
        return """[{"type":$type,"figure":0,"points":"${Base64.getEncoder().encodeToString(points)}","start":{"x":0.2,"y":0.3},"strokeColor":4284626687}]"""
    }

    @Test fun mixedPdfAndBlankPagesRestoreAsOneEditableNotebookInOriginalOrder() {
        val file = tmp.newFile("native.flx")
        file.writeBytes(zip(
            "info" to """{"name":"원본","createDate":1000,"modifiedDate":2000}""".toByteArray(),
            "pages.index" to """[
              {"key":"first","frame":{"width":600,"height":800},"attachmentPage":{"file":"B","index":2}},
              {"key":"blank","frame":{"width":720,"height":540}},
              {"key":"last","attachmentPage":{"file":"A","index":0}}
            ]""".toByteArray(),
            "attachment/PDF/A" to pdf, "attachment/PDF/B" to pdf,
            "objects/blank.drawings" to drawing().toByteArray(),
            "objects/last.drawings" to drawing(2).toByteArray(),
        ))
        val work = tmp.newFolder()
        var count = 0
        val result = FlexcilArchive.restore(file, "native.flx", work) { doc ->
            count++
            assertEquals("원본", doc.title)
            assertEquals(setOf("A", "B"), doc.pdfs.keys)
            assertEquals(listOf("B", null, "A"), doc.pages!!.map { it.pdfKey })
            assertEquals(listOf(2, -1, 0), doc.pages.map { it.pdfIndex })
            assertEquals(1, doc.pages[1].strokes.size)
            assertEquals(1, doc.pages[2].strokes.size)
            assertEquals(720f, doc.pages[1].width!!, 0f)
            assertEquals(1_000_000L, doc.createdAt)
            assertEquals(2_000_000L, doc.updatedAt)
            assertEquals(file, doc.original)
            assertTrue(doc.pdfs.values.all { it.isFile })
        }
        assertEquals(1, count)
        assertEquals(1, result.books)
        assertEquals(2, result.strokes)
        assertEquals(0, result.inkLost)
        assertTrue(work.listFiles()!!.isEmpty())
    }

    @Test fun loosePdfsBesideARootDocumentAreRestoredOnceWithoutDuplicatingAttachments() {
        val nested = zip(
            "info" to """{"name":"nested"}""".toByteArray(),
            "pages.index" to """[{"key":"n1","attachmentPage":{"file":"N","index":0}}]""".toByteArray(),
            "attachment/PDF/N" to pdf,
        )
        val file = tmp.newFile("loose.flex")
        file.writeBytes(zip(
            "info" to """{"name":"root"}""".toByteArray(),
            "pages.index" to """[
              {"key":"a","attachmentPage":{"file":"A","index":0}},
              {"key":"blank","frame":{"width":595,"height":842}},
              {"key":"c","attachmentPage":{"file":"C.pdf","index":0}}
            ]""".toByteArray(),
            "attachment/PDF/A" to pdf, "attachment/PDF/C.pdf" to pdf,
            "objects/a.drawings" to drawing().toByteArray(),
            "extra.pdf" to pdf,
            "flexcilbackup/Documents/과목/handout.PDF" to pdf,
            "flexcilbackup/Documents/과목/nested.flx" to nested,
        ))
        val work = tmp.newFolder()
        val docs = ArrayList<FlexcilDocument>()
        val result = FlexcilArchive.restore(file, file.name, work) { docs += it }

        assertEquals(listOf("root", "extra", "handout", "nested"), docs.map { it.title })
        val root = docs[0]
        assertEquals(setOf("A", "C"), root.pdfs.keys)
        assertEquals(listOf("A", null, "C"), root.pages!!.map { it.pdfKey })
        assertEquals(1, root.pages[0].strokes.size)
        assertNull(docs[1].folder)
        assertEquals("과목", docs[2].folder)
        assertEquals("과목", docs[3].folder)
        assertEquals("every PDF entry becomes exactly one background", 5, docs.sumOf { it.pdfs.size })
        assertEquals(4, result.books)
        assertEquals(0, result.failedDocs)
        assertTrue(work.listFiles()!!.isEmpty())
    }

    @Test fun allBlankNotebookDoesNotNeedAPdfAttachment() {
        val file = tmp.newFile("blank.flx")
        file.writeBytes(zip("info" to """{"name":"빈 노트"}""".toByteArray(),
            "pages.index" to """[{"key":"page","frame":{"width":595,"height":842}}]""".toByteArray()))
        val result = FlexcilArchive.restore(file, "blank.flx", tmp.newFolder()) { doc ->
            assertTrue(doc.pdfs.isEmpty())
            assertNull(doc.pages!!.single().pdfKey)
        }
        assertEquals(1, result.books)
    }

    @Test fun emptyPageIndexFallsBackToAttachedPdfPages() {
        val file = tmp.newFile("empty-index.flx")
        file.writeBytes(zip(
            "info" to """{"name":"PDF 노트"}""".toByteArray(),
            "pages.index" to "[]".toByteArray(),
            "attachment/PDF/A" to pdf,
        ))
        val result = FlexcilArchive.restore(file, file.name, tmp.newFolder()) { doc ->
            assertNull(doc.pages)
            assertEquals(setOf("A"), doc.pdfs.keys)
        }
        assertEquals(1, result.books)
    }

    @Test fun oneBrokenNotebookDoesNotBlockLaterNotebooks() {
        fun document(title: String, rotation: Int) = zip(
            "info" to """{"name":"$title"}""".toByteArray(),
            "pages.index" to """[{"key":"page","frame":{"width":595,"height":842},"attachmentPage":{"file":"A","index":0},"rotate":$rotation}]""".toByteArray(),
            "attachment/PDF/A" to pdf,
        )
        val file = tmp.newFile("mixed-backup.flex")
        file.writeBytes(zip(
            "Documents/one.flx" to document("one", 0),
            "Documents/broken.flx" to document("broken", 45),
            "Documents/password.flx" to document("password", 0),
            "Documents/last.flx" to document("last", 0),
        ))
        val work = tmp.newFolder()
        val titles = ArrayList<String>()
        val result = FlexcilArchive.restore(file, file.name, work) {
            if (it.title == "password") throw FlexcilArchive.PasswordProtectedPdf(SecurityException())
            titles += it.title
        }

        assertEquals(listOf("one", "last"), titles)
        assertEquals(2, result.books)
        assertEquals(2, result.failedDocs)
        assertEquals(1, result.passwordFailures)
        assertTrue(result.report.contains("broken.flx"))
        assertTrue(work.listFiles()!!.isEmpty())
    }

    @Test fun imageDataIsReportedWithoutIncludingItsContents() {
        val file = tmp.newFile("image.flx")
        file.writeBytes(zip(
            "info" to """{"name":"image"}""".toByteArray(),
            "pages.index" to """[{"key":"page","frame":{"width":595,"height":842},"attachmentPage":{"file":"A","index":0}}]""".toByteArray(),
            "attachment/PDF/A" to pdf,
            "objects/page.images" to "[{}]".toByteArray(),
        ))
        val result = FlexcilArchive.restore(file, file.name, tmp.newFolder()) { }

        assertEquals(1, result.imagePagesLost)
        assertTrue(result.report.contains("objects/page.images"))
        assertFalse(result.report.contains("[{}]"))
    }

    @Test fun realBackupRestoresNativeNotebookStructureWhenFixtureIsProvided() {
        val path = System.getenv("SCHEDULEWIDGET_FLEX_FIXTURE")
        assumeTrue("Optional real Flexcil backup", path != null)
        val file = File(path!!)
        val head = file.inputStream().use { input -> ByteArray(1024).let { bytes -> bytes.copyOf(input.read(bytes)) } }
        assertEquals("A backup's embedded PDF header must not select the PDF import path", FileKind.FLEXCIL,
            FileKind.detect(file.name, "application/pdf", head) { false })
        var books = 0; var pages = 0; var blank = 0; var mixed = 0; var strokes = 0
        var recordings = 0; var documentLinks = 0; var linkedStrokes = 0; var timedStrokes = 0
        val durations = HashMap<String, Long>()
        val work = tmp.newFolder()
        val result = FlexcilArchive.restoreCancellable(file, file.name, work, {}, {}, recordingSink = { recording ->
            recordings++
            documentLinks += recording.documentKeys.size
            val id = "test-recording-$recordings"
            durations[id] = recording.durationMs
            assertTrue(recording.audio.isFile)
            id
        }) { doc ->
                books++
                val restored = doc.pages!!
                pages += restored.size
                blank += restored.count { it.pdfKey == null }
                if (doc.pdfs.size > 1) mixed++
                strokes += restored.sumOf { page ->
                    page.strokes.forEach { stroke ->
                        stroke.recordingId?.let { id ->
                            val duration = durations[id]
                            assertNotNull(duration)
                            val offset = stroke.recordingOffsetMs
                            assertNotNull(offset)
                            assertTrue(offset!! in 0L..duration!!)
                            linkedStrokes++
                            timedStrokes++
                        }
                    }
                    page.strokes.size
                }
                assertNotNull(doc.original)
            }
        assertEquals(45, books)
        assertEquals(1626, pages)
        assertEquals(3, blank)
        assertEquals(2, mixed)
        assertEquals(6867, strokes)
        assertEquals(47, recordings)
        assertEquals(52, documentLinks)
        assertEquals(6807, linkedStrokes)
        assertEquals(linkedStrokes, timedStrokes)
        assertEquals(315, result.unmatchedAudioRefs)
        assertEquals(0, result.audioFailures)
        assertEquals(0, result.inkLost)
        assertTrue(work.listFiles()!!.isEmpty())
    }
}
