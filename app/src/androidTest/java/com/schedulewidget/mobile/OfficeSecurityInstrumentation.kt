package com.schedulewidget.mobile

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.schedulewidget.mobile.notes.importer.DocxRenderer
import com.schedulewidget.mobile.notes.importer.DocxReader
import com.schedulewidget.mobile.notes.importer.Ooxml
import com.schedulewidget.mobile.notes.importer.PictureEl
import com.schedulewidget.mobile.notes.importer.PptxReader
import com.schedulewidget.mobile.notes.importer.PptxRenderer
import com.schedulewidget.mobile.notes.importer.ZipPartSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Runs the OOXML security checks with Android's real SAX/DOM and PdfDocument implementations. */
class OfficeSecurityInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val results = mutableListOf<String>()
        var failures = 0
        fun test(name: String, body: () -> Unit) {
            try {
                body()
                results += "PASS: $name"
            } catch (e: Throwable) {
                failures++
                results += "FAIL: $name: ${e.stackTraceToString()}"
            }
        }

        test("Android OOXML parser accepts normal XML") {
            check(Ooxml.parse("<root><value>ok</value></root>".toByteArray()).textContent == "ok")
        }

        test("Android OOXML parser rejects UTF-8 and UTF-16 DTDs") {
            val inputs = listOf(
                "<!DOCTYPE x [<!ENTITY e 'internal'>]><x>&e;</x>".toByteArray(),
                "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///dev/null'>]><x>&e;</x>".toByteArray(),
                byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<!DOCTYPE x [<!ENTITY e 'internal'>]><x>&e;</x>".toByteArray(StandardCharsets.UTF_16LE),
                byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///dev/null'>]><x>&e;</x>".toByteArray(StandardCharsets.UTF_16LE),
            )
            inputs.forEachIndexed { i, bytes ->
                val failure = try { Ooxml.parse(bytes); null } catch (e: Throwable) { e }
                check(failure != null) { "DTD fixture $i was accepted" }
                check(generateSequence(failure) { it.cause }.any { it.message?.contains("DOCTYPE", true) == true }) {
                    "DTD fixture $i failed for an unexpected reason: $failure"
                }
            }
        }

        test("Android OOXML parser bounds nesting, nodes and bytes") {
            val deep = "<x>".repeat(Ooxml.MAX_XML_DEPTH + 1) + "</x>".repeat(Ooxml.MAX_XML_DEPTH + 1)
            check(runCatching { Ooxml.parse(deep.toByteArray()) }.isFailure) { "deep XML was accepted" }
            val flat = "<x>" + "<y/>".repeat(Ooxml.MAX_XML_NODES) + "</x>"
            check(runCatching { Ooxml.parse(flat.toByteArray()) }.isFailure) { "flat XML node bomb was accepted" }
            check(runCatching { Ooxml.parse(ByteArray(Ooxml.MAX_XML_BYTES + 1)) }.isFailure) { "oversized XML was accepted" }
        }

        test("small DOCX and PPTX fixtures render offline") {
            val docx = fixture("docx", listOf(
                "word/document.xml" to """
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>Offline DOCX</w:t></w:r></w:p></w:body>
                    </w:document>
                """.trimIndent(),
            ))
            val pptx = fixture("pptx", listOf(
                "ppt/presentation.xml" to """
                    <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                        xmlns:r="${Ooxml.NS_R}">
                      <p:sldSz cx="9144000" cy="6858000"/>
                      <p:sldIdLst><p:sldId id="1" r:id="rId1"/></p:sldIdLst>
                    </p:presentation>
                """.trimIndent(),
                "ppt/_rels/presentation.xml.rels" to """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
                    </Relationships>
                """.trimIndent(),
                "ppt/slides/slide1.xml" to """
                    <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                        xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                        xmlns:r="${Ooxml.NS_R}">
                      <p:cSld><p:spTree>
                        <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
                        <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
                        <p:pic>
                          <p:nvPicPr><p:cNvPr id="2" name="external"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
                          <p:blipFill><a:blip r:embed="rIdExternal"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>
                          <p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="1000000" cy="1000000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>
                        </p:pic>
                      </p:spTree></p:cSld>
                    </p:sld>
                """.trimIndent(),
                "ppt/slides/_rels/slide1.xml.rels" to """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rIdExternal" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
                          Target="http://127.0.0.1:9/should-not-fetch.png" TargetMode="External"/>
                    </Relationships>
                """.trimIndent(),
            ))
            val docxPdf = File(targetContext.cacheDir, "office-security-${System.nanoTime()}-docx.pdf")
            val pptxPdf = File(targetContext.cacheDir, "office-security-${System.nanoTime()}-pptx.pdf")
            try {
                ZipPartSource(docx).use { source ->
                    check(DocxReader(source).read().blocks.isNotEmpty())
                    check(DocxRenderer(source) {}.render(docxPdf) == 1)
                }
                ZipPartSource(pptx).use { source ->
                    val picture = PptxReader(source).slide(0).elements.filterIsInstance<PictureEl>().single()
                    check(picture.media == null) { "external image relationship was retained" }
                    check(PptxRenderer.render(source, pptxPdf) { _, _ -> } == 1)
                }
                check(docxPdf.length() > 0 && pptxPdf.length() > 0) { "offline render did not produce PDFs" }
            } finally {
                docx.delete()
                pptx.delete()
                docxPdf.delete()
                pptxPdf.delete()
            }
        }

        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", "\n${results.joinToString("\n")}\nFailures: $failures\n")
        })
    }

    private fun fixture(suffix: String, entries: List<Pair<String, String>>): File {
        val file = File(targetContext.cacheDir, "office-security-${System.nanoTime()}-$suffix.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((path, contents) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(contents.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }
}
