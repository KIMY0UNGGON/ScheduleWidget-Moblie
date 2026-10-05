package com.schedulewidget.mobile.notes.importer

import com.schedulewidget.mobile.notes.ink.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FlexcilArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val pdf = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n".toByteArray()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Flexcil's point encoding: uint32 count, then x, y, width float32 triples (little endian). */
    private fun points(vararg xyw: Float): String {
        val buf = ByteBuffer.allocate(4 + xyw.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(xyw.size / 3)
        xyw.forEach { buf.putFloat(it) }
        return Base64.getEncoder().encodeToString(buf.array())
    }

    private fun drawing(sx: Float, sy: Float, color: Long, vararg xyw: Float) =
        """{"figure":0,"points":"${points(*xyw)}","start":{"x":$sx,"y":$sy},"mode":5,"dashtype":0,"fillColor":0,""" +
            """"strokeColor":$color,"key":"K","type":1,"scale":{"x":1.0,"y":1.0},"rotate":0.0}"""

    private fun page(key: String, file: String, index: Int, rotate: Double = 0.0) =
        """{"frame":{"x":0.0,"y":0.0,"width":768.0,"height":1024.0},"rotate":$rotate,""" +
            """"attachmentPage":{"file":"$file","index":$index},"key":"$key","version":"0.0.5"}"""

    /** A .flx document with one PDF attachment. */
    private fun flx(name: String?, pages: List<String>, drawings: Map<String, String> = emptyMap(), attachments: List<String> = listOf("A1")): ByteArray {
        val entries = ArrayList<Pair<String, ByteArray>>()
        if (name != null) entries += "info" to """{"name":"$name","type":4,"version":"0.0.5"}""".toByteArray()
        entries += "pages.index" to "[${pages.joinToString(",")}]".toByteArray()
        attachments.forEach { entries += "attachment/PDF/$it" to pdf }
        drawings.forEach { (key, json) -> entries += "objects/$key.drawings" to json.toByteArray() }
        entries += "thumbnail" to byteArrayOf(1, 2, 3)
        return zip(*entries.toTypedArray())
    }

    private class Got(val title: String, val folder: String?, val pdfSize: Long, val pages: List<FlexcilArchive.Page>?)

    private fun read(bytes: ByteArray, name: String): Pair<FlexcilArchive.Result, List<Got>> {
        val file = tmp.newFile()
        file.writeBytes(bytes)
        val work = tmp.newFolder()
        val got = ArrayList<Got>()
        val result = FlexcilArchive.read(file, name, work) { b -> got += Got(b.title, b.folder, b.pdf.length(), b.pages) }
        assertEquals("temporary files are cleaned up", 0, work.listFiles()!!.size)
        return result to got
    }

    @Test fun flxDocumentWithInk() {
        val ink = "[" + drawing(0.5f, 0.25f, 4284626687L, 0f, 0f, 0.002f, 0.1f, 0.05f, 0.004f) + "]"
        val bytes = flx("수학 노트", listOf(page("P1", "A1", 1), page("P2", "A1", 0)), mapOf("P1" to ink))
        val (result, books) = read(bytes, "수학 노트.flx")
        assertTrue(result.flexcil)
        assertEquals(1, books.size)
        val b = books[0]
        assertEquals("수학 노트", b.title)
        assertNull(b.folder)
        assertEquals(pdf.size.toLong(), b.pdfSize)
        assertEquals(listOf(1, 0), b.pages!!.map { it.pdfIndex })
        assertEquals(1, b.pages[0].strokes.size)
        assertTrue(b.pages[1].strokes.isEmpty())
        val s = b.pages[0].strokes[0]
        assertEquals(2, s.count)
        // Offsets are added to the start point (top-left of the stroke's box).
        assertEquals(0.5f, s.pts[0], 1e-6f); assertEquals(0.25f, s.pts[1], 1e-6f)
        assertEquals(0.6f, s.pts[3], 1e-6f); assertEquals(0.30f, s.pts[4], 1e-6f)
        assertEquals(0xFF6236FF.toInt(), s.color)
        assertFalse(s.highlighter)
        assertEquals(1, result.strokes)
        assertEquals(0, result.inkLost)
    }

    @Test fun inkToPagePoints() {
        val s = FlexcilInk.parseDrawings("[" + drawing(0.5f, 0.25f, 0xFF000000L, 0f, 0f, 0.002f, 0.1f, 0.05f, 0.004f) + "]").strokes
        val ink = FlexcilInk.toPageInk(s, 600f, 800f, 7L)
        val st = ink.strokes.single()
        assertEquals(7L, st.id)
        assertEquals(Tool.PEN, st.tool)
        assertEquals(0.004f * 600f, st.width, 1e-4f)
        assertEquals(300f, st.x(0), 1e-3f); assertEquals(200f, st.y(0), 1e-3f)
        assertEquals(360f, st.x(1), 1e-3f); assertEquals(240f, st.y(1), 1e-3f)
        // The widest point is full pressure; the half-width point maps back to half the width.
        assertEquals(1f, st.p(1), 1e-5f)
        val half = com.schedulewidget.mobile.notes.ink.InkGeometry.pressureWidth(st.width, st.p(0))
        assertEquals(0.002f * 600f, half, 1e-3f)
    }

    @Test fun translucentColourIsHighlighter() {
        val s = FlexcilInk.parseDrawings("[" + drawing(0.1f, 0.1f, 0x80FFEB3BL, 0f, 0f, 0.01f, 0.2f, 0f, 0.01f) + "]").strokes.single()
        assertTrue(s.highlighter)
        assertEquals(0xFFFFEB3B.toInt(), s.color)
        assertEquals(Tool.HIGHLIGHTER, FlexcilInk.toPageInk(listOf(s), 100f, 100f, 1L).strokes.single().tool)
    }

    @Test fun realSamplePoints() {
        // A ruler line from a real Flexcil 2026 .flx ("figure":1): two points, constant width.
        val d = FlexcilInk.decodePoints("AgAAAAAAAAAAAAAAaOQ9OwAAAABq5Os9aOQ9Ow\\u003d\\u003d")!!
        assertEquals(6, d.size)
        assertEquals(0f, d[0], 0f); assertEquals(0f, d[1], 0f)
        assertEquals(0.115f, d[4], 1e-3f)
        assertEquals(0.0029f, d[2], 1e-4f)
        assertNull(FlexcilInk.decodePoints("not base64 !!"))
        assertNull(FlexcilInk.decodePoints(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4, 5))))
    }

    @Test fun unreadableInkStillImportsPdf() {
        val bad = """[{"type":1,"points":"AAAA","start":{"x":0.1,"y":0.1},"strokeColor":0},""" +
            drawing(5f, 5f, 0xFF000000L, 0f, 0f, 0.002f, 0.1f, 0.1f, 0.002f) + "]"
        val (result, books) = read(flx("X", listOf(page("P1", "A1", 0)), mapOf("P1" to bad)), "x.flx")
        assertEquals(1, books.size)
        assertTrue(books[0].pages!![0].strokes.isEmpty())
        assertEquals("a short points blob and an off-page stroke", 2, result.inkLost)
    }

    @Test fun rotatedPagesDropInk() {
        val ink = "[" + drawing(0.1f, 0.1f, 0xFF000000L, 0f, 0f, 0.002f, 0.1f, 0.1f, 0.002f) + "]"
        val (result, books) = read(flx("R", listOf(page("P1", "A1", 0, rotate = 90.0)), mapOf("P1" to ink)), "r.flx")
        assertTrue(books[0].pages!![0].rotated)
        assertTrue(books[0].pages!![0].strokes.isEmpty())
        assertEquals(1, result.inkLost)
    }

    @Test fun eachAttachmentBecomesANotebook() {
        val bytes = flx("Mixed", listOf(page("P1", "A1", 0), page("P2", "B2", 0), page("P3", "A1", 1)), attachments = listOf("A1", "B2", "C3"))
        val (_, books) = read(bytes, "m.flx")
        assertEquals(listOf("Mixed", "Mixed (2)", "Mixed (3)"), books.map { it.title })
        assertEquals(listOf(0, 1), books[0].pages!!.map { it.pdfIndex })
        assertEquals(listOf(0), books[1].pages!!.map { it.pdfIndex })
        assertNull("an attachment no page uses is imported whole", books[2].pages)
    }

    @Test fun backupWithNestedAndExpandedDocuments() {
        val nested = flx(null, listOf(page("P1", "A1", 0)))
        val bytes = zip(
            "flexcilbackup/Documents/과목/물리/Ch1.flx" to nested,
            "flexcilbackup/Documents/영어/Vocab.flx/info" to """{"name":"단어장"}""".toByteArray(),
            "flexcilbackup/Documents/영어/Vocab.flx/pages.index" to "[${page("Q1", "Z9", 0)}]".toByteArray(),
            "flexcilbackup/Documents/영어/Vocab.flx/attachment/PDF/Z9" to pdf,
            "flexcilbackup/Recordings/rec.fab" to byteArrayOf(9, 9),
            "flexcilbackup/.itemInfo" to byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
        )
        val (result, books) = read(bytes, "Flexcil Backup 2026.flex")
        assertTrue(result.flexcil)
        val byTitle = books.associateBy { it.title }
        assertEquals(setOf("Ch1", "단어장"), byTitle.keys)
        assertEquals("과목/물리", byTitle["Ch1"]!!.folder)
        assertEquals("영어", byTitle["단어장"]!!.folder)
        // The listing names every entry of every level with its size, never the content.
        assertTrue(result.report.contains("flexcilbackup/Documents/과목/물리/Ch1.flx\t"))
        assertTrue(result.report.contains("attachment/PDF/A1\t${pdf.size}"))
        assertFalse(result.report.contains("%PDF"))
    }

    @Test fun documentsListGivesTitlesAndFolders() {
        val id = "3F2504E0-4F89-11D3-9A0C-0305E82C3301"
        val list = """[{"type":1,"name":"전공","children":[{"type":0,"name":"알고리즘 필기","document":"$id"}]}]"""
        val deflater = Deflater()
        deflater.setInput(list.toByteArray())
        deflater.finish()
        val buf = ByteArray(4096)
        val n = deflater.deflate(buf)
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(list.length.toLong()).array()
        val bytes = zip(
            "documents.list" to header + buf.copyOf(n),
            "$id.flx" to flx("ignored", listOf(page("P1", "A1", 0))),
        )
        val (_, books) = read(bytes, "sync.zip")
        assertEquals("알고리즘 필기", books.single().title)
        assertEquals("전공", books.single().folder)
    }

    @Test fun uuidNamesFallBackToInfoName() {
        val bytes = zip("Documents/0A1B2C3D-0000-1111-2222-333344445555.flx" to flx("강의 노트", listOf(page("P1", "A1", 0))))
        val (_, books) = read(bytes, "backup.flex")
        assertEquals("강의 노트", books.single().title)
    }

    @Test fun plainZipWithPdfs() {
        val (result, books) = read(zip("handouts/week1.pdf" to pdf, "readme.txt" to "hi".toByteArray()), "stuff.zip")
        assertFalse(result.flexcil)
        assertEquals("week1", books.single().title)
        assertEquals("handouts", books.single().folder)
        assertNull(books.single().pages)
    }

    @Test fun notAZipFails() {
        val file = tmp.newFile()
        file.writeText("hello")
        try {
            FlexcilArchive.read(file, "x.flex", tmp.newFolder()) { fail("no books") }
            fail("expected an error")
        } catch (e: IOException) {
            assertTrue(e is FlexcilArchive.FlexcilError)
            assertTrue((e as FlexcilArchive.FlexcilError).report.contains("x.flex"))
        }
    }

    @Test fun looksLikeFlexcil() {
        val f = tmp.newFile()
        f.writeBytes(flx("a", listOf(page("P1", "A1", 0))))
        assertTrue(FlexcilArchive.looksLikeFlexcil(f))
        val g = tmp.newFile()
        g.writeBytes(zip("word/document.xml" to "<w/>".toByteArray()))
        assertFalse(FlexcilArchive.looksLikeFlexcil(g))
        assertEquals(FileKind.FLEXCIL, FileKind.detect("a.flex", null, byteArrayOf(0x50, 0x4B, 3, 4)) { false })
    }

    @Test fun folderPaths() {
        assertEquals("a/b", FlexcilArchive.folderOf(listOf("flexcilbackup", "Documents", "a", "b")))
        assertNull(FlexcilArchive.folderOf(listOf("flexcilbackup", "Documents")))
        assertEquals("x", FlexcilArchive.folderOf(listOf("x")))
    }

    @Test fun pressureInverse() {
        for (r in listOf(0.4f, 0.6f, 0.8f, 1f)) {
            val p = FlexcilInk.pressureFor(r)
            assertEquals(r, com.schedulewidget.mobile.notes.ink.InkGeometry.pressureWidth(1f, p), 1e-4f)
        }
        assertEquals(0f, FlexcilInk.pressureFor(0.1f), 0f)
    }
}
