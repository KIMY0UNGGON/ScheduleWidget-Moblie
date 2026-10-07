package com.schedulewidget.mobile.notes.importer

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class OfficeParsingTest {
    private val nsP = """xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships""""
    private val nsW = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships""""

    private fun parts(vararg p: Pair<String, String>) = MapPartSource(p.associate { it.first to it.second.toByteArray() })

    private fun rels(vararg r: Triple<String, String, String>) =
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            r.joinToString("") { (id, type, target) -> """<Relationship Id="$id" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/$type" Target="$target"/>""" } +
            "</Relationships>"

    @Test fun unitConversions() {
        assertEquals(1f, Units.emuToPt(12700), 1e-4f)
        assertEquals(72f, Units.emuToPt(914400), 1e-4f)
        assertEquals(960f, Units.emuToPt(12192000), 1e-3f) // 16:9 slide width
        assertEquals(72f, Units.twipToPt(1440), 1e-4f)
        assertEquals(12f, Units.halfPtToPt(24), 1e-4f)
        assertEquals(18f, Units.centiPtToPt(1800), 1e-4f)
    }

    @Test fun relationshipPaths() {
        assertEquals("ppt/slides/_rels/slide1.xml.rels", Ooxml.relsPathOf("ppt/slides/slide1.xml"))
        assertEquals("_rels/.rels", Ooxml.relsPathOf(""))
        assertEquals("ppt/media/image1.png", Ooxml.resolve("ppt/slides/slide1.xml", "../media/image1.png"))
        assertEquals("ppt/slides/slide2.xml", Ooxml.resolve("ppt/presentation.xml", "slides/slide2.xml"))
        assertEquals("word/media/a.png", Ooxml.resolve("word/document.xml", "/word/media/a.png"))
    }

    @Test fun slideSizeAndOrder() {
        val pres = Ooxml.parse(
            """<p:presentation $nsP><p:sldIdLst><p:sldId id="257" r:id="rId9"/><p:sldId id="256" r:id="rId2"/></p:sldIdLst>
               <p:sldSz cx="12192000" cy="6858000"/></p:presentation>""".toByteArray(),
        )
        val (w, h) = PptxReader.slideSize(pres)
        assertEquals(960f, w, 1e-3f)
        assertEquals(540f, h, 1e-3f)
        val src = parts("ppt/_rels/presentation.xml.rels" to rels(Triple("rId2", "slide", "slides/slide1.xml"), Triple("rId9", "slide", "slides/slide2.xml")))
        // sldIdLst order wins over file names.
        assertEquals(listOf("ppt/slides/slide2.xml", "ppt/slides/slide1.xml"), PptxReader.slideOrder(pres, Ooxml.rels(src, "ppt/presentation.xml")))
    }

    @Test fun missingSlidePartFailsInsteadOfReturningABlankSlide() {
        val src = parts(
            "ppt/presentation.xml" to """<p:presentation $nsP><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to rels(Triple("rId1", "slide", "slides/slide1.xml")),
        )
        var failed = false
        try {
            PptxReader(src).slide(0)
        } catch (_: IOException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test fun slideShapesTextPictureGroupAndTable() {
        val slide = """<p:sld $nsP><p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="112233"/></a:solidFill></p:bgPr></p:bg><p:spTree>
            <p:sp><p:nvSpPr><p:cNvPr id="2" name="t"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
              <p:spPr><a:xfrm><a:off x="127000" y="254000"/><a:ext cx="1270000" cy="635000"/></a:xfrm><a:prstGeom prst="ellipse"/>
                <a:solidFill><a:srgbClr val="FF0000"/></a:solidFill></p:spPr>
              <p:txBody><a:bodyPr anchor="ctr"/><a:p><a:pPr algn="ctr"><a:buChar char="§"/></a:pPr>
                <a:r><a:rPr sz="2400" b="1"><a:solidFill><a:srgbClr val="00FF00"/></a:solidFill></a:rPr><a:t>안녕</a:t></a:r>
                <a:r><a:rPr i="1"/><a:t> world</a:t></a:r></a:p>
                <a:p><a:pPr><a:buAutoNum type="arabicPeriod"/></a:pPr><a:r><a:t>one</a:t></a:r></a:p>
                <a:p><a:pPr><a:buAutoNum type="arabicPeriod"/></a:pPr><a:r><a:t>two</a:t></a:r></a:p></p:txBody></p:sp>
            <p:pic><p:nvPicPr><p:cNvPr id="3" name="p"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
              <p:blipFill><a:blip r:embed="rId5"/><a:srcRect l="10000"/></p:blipFill>
              <p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="12700" cy="25400"/></a:xfrm></p:spPr></p:pic>
            <p:grpSp><p:nvGrpSpPr><p:cNvPr id="4" name="g"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
              <p:grpSpPr><a:xfrm><a:off x="1270000" y="0"/><a:ext cx="254000" cy="254000"/><a:chOff x="0" y="0"/><a:chExt cx="127000" cy="127000"/></a:xfrm></p:grpSpPr>
              <p:sp><p:nvSpPr><p:cNvPr id="5" name="c"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
                <p:spPr><a:xfrm><a:off x="12700" y="12700"/><a:ext cx="127000" cy="127000"/></a:xfrm><a:solidFill><a:schemeClr val="accent1"/></a:solidFill></p:spPr></p:sp></p:grpSp>
            <p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="6" name="tb"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>
              <p:xfrm><a:off x="0" y="1270000"/><a:ext cx="2540000" cy="508000"/></p:xfrm>
              <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table"><a:tbl><a:tblGrid><a:gridCol w="1270000"/><a:gridCol w="1270000"/></a:tblGrid>
                <a:tr h="254000"><a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>A</a:t></a:r></a:p></a:txBody></a:tc><a:tc><a:txBody><a:bodyPr/><a:p><a:r><a:t>B</a:t></a:r></a:p></a:txBody></a:tc></a:tr>
              </a:tbl></a:graphicData></a:graphic></p:graphicFrame>
            <p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="7" name="ch"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>
              <p:xfrm><a:off x="0" y="0"/><a:ext cx="127000" cy="127000"/></p:xfrm>
              <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart"/></a:graphic></p:graphicFrame>
            </p:spTree></p:cSld></p:sld>"""
        val src = parts(
            "ppt/presentation.xml" to """<p:presentation $nsP><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst><p:sldSz cx="9144000" cy="6858000"/></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to rels(Triple("rId1", "slide", "slides/slide1.xml")),
            "ppt/slides/slide1.xml" to slide,
            "ppt/slides/_rels/slide1.xml.rels" to rels(Triple("rId5", "image", "../media/image1.png")),
        )
        val reader = PptxReader(src)
        assertEquals(720f, reader.widthPt, 1e-3f)
        val s = reader.slide(0)
        assertEquals(Colors.hex("112233"), s.background)
        assertEquals(5, s.elements.size)

        val shape = s.elements[0] as ShapeEl
        assertEquals(Box(10f, 20f, 100f, 50f), shape.box)
        assertEquals("ellipse", shape.geom)
        assertEquals(Colors.hex("FF0000"), shape.fill)
        val body = shape.text!!
        assertEquals('c', body.anchor)
        val p0 = body.paras[0]
        assertEquals(TAlign.CENTER, p0.align)
        assertEquals("•", p0.bullet) // Wingdings "§" becomes a plain bullet
        assertEquals("안녕", p0.runs[0].text)
        assertEquals(24f, p0.runs[0].size, 1e-4f)
        assertTrue(p0.runs[0].bold)
        assertEquals(Colors.hex("00FF00"), p0.runs[0].color)
        assertTrue(p0.runs[1].italic)
        assertEquals(18f, p0.runs[1].size, 1e-4f) // default size
        assertEquals("1.", body.paras[1].bullet)
        assertEquals("2.", body.paras[2].bullet)

        val pic = s.elements[1] as PictureEl
        assertEquals("ppt/media/image1.png", pic.media)
        assertEquals(Box(0f, 0f, 1f, 2f), pic.box)
        assertEquals(0.1f, pic.crop[0], 1e-4f)

        // Group: child (1pt, 1pt, 10x10) in a 10x10 child space mapped to 20x20 at x=100pt -> scale 2.
        val child = s.elements[2] as ShapeEl
        assertEquals(Box(102f, 2f, 20f, 20f), child.box)
        assertEquals(Theme.DEFAULT.scheme("accent1"), child.fill)

        val table = s.elements[3] as TableEl
        assertEquals(listOf(100f, 100f), table.cols)
        assertEquals("B", table.rows[0].cells[1].text.paras[0].runs[0].text)

        assertEquals("(차트)", (s.elements[4] as PlaceholderEl).label)
    }

    @Test fun placeholderInheritsLayoutPositionAndMasterSizes() {
        val src = parts(
            "ppt/presentation.xml" to """<p:presentation $nsP><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to rels(Triple("rId1", "slide", "slides/slide1.xml")),
            "ppt/slides/slide1.xml" to """<p:sld $nsP><p:cSld><p:spTree>
                <p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr/>
                <p:txBody><a:bodyPr/><a:p><a:r><a:t>제목</a:t></a:r></a:p></p:txBody></p:sp>
                <p:sp><p:nvSpPr><p:cNvPr id="3" name="Empty"/><p:cNvSpPr/><p:nvPr><p:ph idx="1"/></p:nvPr></p:nvSpPr><p:spPr/>
                <p:txBody><a:bodyPr/><a:p><a:endParaRPr/></a:p></p:txBody></p:sp>
                </p:spTree></p:cSld></p:sld>""",
            "ppt/slides/_rels/slide1.xml.rels" to rels(Triple("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml")),
            "ppt/slideLayouts/slideLayout1.xml" to """<p:sldLayout $nsP><p:cSld><p:spTree>
                <p:sp><p:nvSpPr><p:cNvPr id="2" name="T"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
                <p:spPr><a:xfrm><a:off x="127000" y="127000"/><a:ext cx="2540000" cy="1270000"/></a:xfrm></p:spPr></p:sp>
                </p:spTree></p:cSld></p:sldLayout>""",
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to rels(Triple("rId1", "slideMaster", "../slideMasters/slideMaster1.xml")),
            "ppt/slideMasters/slideMaster1.xml" to """<p:sldMaster $nsP><p:cSld><p:spTree/></p:cSld>
                <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
                <p:txStyles><p:titleStyle><a:lvl1pPr algn="ctr"><a:defRPr sz="4400"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill></a:defRPr></a:lvl1pPr></p:titleStyle></p:txStyles></p:sldMaster>""",
            "ppt/slideMasters/_rels/slideMaster1.xml.rels" to rels(Triple("rId1", "theme", "../theme/theme1.xml")),
            "ppt/theme/theme1.xml" to """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:themeElements><a:clrScheme name="x">
                <a:dk1><a:srgbClr val="202020"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1></a:clrScheme></a:themeElements></a:theme>""",
        )
        val s = PptxReader(src).slide(0)
        // The empty body placeholder is not drawn.
        assertEquals(1, s.elements.size)
        val title = s.elements[0] as ShapeEl
        assertEquals(Box(10f, 10f, 200f, 100f), title.box)
        val run = title.text!!.paras[0].runs[0]
        assertEquals(44f, run.size, 1e-4f)
        assertEquals(Colors.hex("202020"), run.color)
        assertEquals(TAlign.CENTER, title.text!!.paras[0].align)
    }

    @Test fun docxRunsParagraphsNumberingAndPage() {
        val doc = """<w:document $nsW><w:body>
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>제목 하나</w:t></w:r></w:p>
            <w:p><w:pPr><w:jc w:val="center"/><w:spacing w:before="240" w:after="120" w:line="360" w:lineRule="auto"/></w:pPr>
              <w:r><w:rPr><w:b/><w:i/><w:u w:val="single"/><w:sz w:val="28"/><w:color w:val="FF0000"/></w:rPr><w:t xml:space="preserve">굵게 </w:t></w:r>
              <w:r><w:rPr><w:b w:val="0"/></w:rPr><w:t>보통</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>first</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>second</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="2"/></w:numPr></w:pPr><w:r><w:t>bullet</w:t></w:r></w:p>
            <w:p><w:r><w:t>before</w:t></w:r><w:r><w:br w:type="page"/></w:r><w:r><w:t>after</w:t></w:r></w:p>
            <w:tbl><w:tblGrid><w:gridCol w:w="2880"/><w:gridCol w:w="2880"/></w:tblGrid>
              <w:tr><w:tc><w:tcPr><w:shd w:fill="DDDDDD"/></w:tcPr><w:p><w:r><w:t>A1</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B1</w:t></w:r></w:p></w:tc></w:tr>
            </w:tbl>
            <w:sectPr><w:pgSz w:w="16838" w:h="11906" w:orient="landscape"/><w:pgMar w:top="720" w:right="720" w:bottom="720" w:left="720"/></w:sectPr>
            </w:body></w:document>"""
        val styles = """<w:styles $nsW><w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults>
            <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/></w:style></w:styles>"""
        val numbering = """<w:numbering $nsW>
            <w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl></w:abstractNum>
            <w:abstractNum w:abstractNumId="1"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val=""/></w:lvl></w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num><w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num></w:numbering>"""
        val d = DocxReader(parts("word/document.xml" to doc, "word/styles.xml" to styles, "word/numbering.xml" to numbering)).read()

        assertEquals(841.9f, d.page.width, 0.01f)
        assertEquals(595.3f, d.page.height, 0.01f)
        assertEquals(36f, d.page.left, 1e-4f)

        val blocks = d.blocks
        val heading = blocks[0] as DPara
        assertEquals(1, heading.heading)
        assertTrue(heading.runs[0].bold)
        assertEquals(20f, heading.runs[0].size, 1e-4f) // no size in styles.xml -> heading fallback

        val p = blocks[1] as DPara
        assertEquals(TAlign.CENTER, p.align)
        assertEquals(12f, p.spaceBefore, 1e-4f)
        assertEquals(6f, p.spaceAfter, 1e-4f)
        assertEquals(1.5f, p.lineSpacing, 1e-4f)
        val r0 = p.runs[0]
        assertEquals("굵게 ", r0.text)
        assertTrue(r0.bold && r0.italic && r0.underline)
        assertEquals(14f, r0.size, 1e-4f)
        assertEquals(Colors.hex("FF0000"), r0.color)
        assertEquals(false, p.runs[1].bold)
        assertEquals(11f, p.runs[1].size, 1e-4f) // docDefaults

        assertEquals("1.", (blocks[2] as DPara).prefix)
        assertEquals("2.", (blocks[3] as DPara).prefix)
        assertEquals("•", (blocks[4] as DPara).prefix)

        assertEquals("before", (blocks[5] as DPara).runs[0].text)
        assertEquals(DPageBreak, blocks[6])
        assertEquals("after", (blocks[7] as DPara).runs[0].text)

        val table = blocks[8] as DTable
        assertEquals(listOf(144f, 144f), table.cols)
        assertEquals(Colors.hex("DDDDDD"), table.rows[0].cells[0].fill)
        assertEquals("B1", ((table.rows[0].cells[1].blocks[0]) as DPara).runs[0].text)
    }

    @Test fun docxDefaultsWithoutSectPr() {
        val d = DocxReader(parts("word/document.xml" to """<w:document $nsW><w:body><w:p/></w:body></w:document>""")).read()
        assertEquals(DocPage.A4, d.page)
        assertEquals(1, d.blocks.size)
        assertTrue((d.blocks[0] as DPara).runs.isEmpty())
    }

    @Test fun numberFormats() {
        assertEquals("c", DocxNumbering.format(3, "lowerLetter"))
        assertEquals("AA", DocxNumbering.format(27, "upperLetter"))
        assertEquals("iv", DocxNumbering.format(4, "lowerRoman"))
        assertEquals("다", DocxNumbering.format(3, "ganada"))
        assertEquals("XIV", PptxReader.roman(14))
        assertEquals("(b)", PptxReader.autoNumber("alphaLcParenBoth", 2))
        assertEquals("3)", PptxReader.autoNumber("arabicParenR", 3))
    }

    @Test fun colorTransforms() {
        val doc = Ooxml.parse("""<a:x xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:srgbClr val="4472C4"><a:lumMod val="50000"/></a:srgbClr></a:x>""".toByteArray())
        val c = drawingColor(doc, Theme.DEFAULT)!!
        // Half the lightness: darker, same hue order (blue > green > red).
        assertTrue(Colors.blue(c) < 0xC4 && Colors.blue(c) > Colors.green(c) && Colors.green(c) > Colors.red(c))
        assertNull(Colors.hex("auto"))
        assertEquals(0xFF0A0B0C.toInt(), Colors.hex("0A0B0C"))
    }

    @Test fun fileKindDetection() {
        val pdf = "%PDF-1.7\n".toByteArray()
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0)
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0, 0)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
        assertEquals(FileKind.PDF, FileKind.detect("a.docx", null, pdf) { false })
        assertEquals(FileKind.PPTX, FileKind.detect("a.bin", null, zip) { it == "ppt/presentation.xml" })
        assertEquals(FileKind.DOCX, FileKind.detect(null, "application/octet-stream", zip) { it == "word/document.xml" })
        assertEquals(FileKind.UNKNOWN, FileKind.detect("a.zip", null, zip) { false })
        assertEquals(FileKind.PPT, FileKind.detect("old.ppt", null, ole) { false })
        assertEquals(FileKind.DOC, FileKind.detect(null, "application/msword", ole) { false })
        assertEquals(FileKind.IMAGE, FileKind.detect("x", null, png) { false })
        assertEquals(FileKind.UNKNOWN, FileKind.detect("notes.txt", "text/plain", "hello".toByteArray()) { false })
    }

    @Test fun zipPartReadLimitUsesActualBytes() {
        val bytes = byteArrayOf(1, 2, 3)
        assertArrayEquals(bytes, readLimited(ByteArrayInputStream(bytes), bytes.size.toLong()))
        assertNull(readLimited(ByteArrayInputStream(bytes), (bytes.size - 1).toLong()))
    }

    @Test fun ooxmlRejectsDtdDepthNodeAndByteBombs() {
        assertThrows(org.xml.sax.SAXException::class.java) {
            Ooxml.parse("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>".toByteArray())
        }
        assertEquals("<!DOCTYPE x>", Ooxml.parse("<x><!-- <!DOCTYPE y> --><![CDATA[<!DOCTYPE x>]]></x>".toByteArray()).textContent)
        val deep = "<x>".repeat(Ooxml.MAX_XML_DEPTH + 1) + "</x>".repeat(Ooxml.MAX_XML_DEPTH + 1)
        assertThrows(org.xml.sax.SAXException::class.java) { Ooxml.parse(deep.toByteArray()) }
        val flat = "<x>" + "<y/>".repeat(Ooxml.MAX_XML_NODES) + "</x>"
        assertThrows(org.xml.sax.SAXException::class.java) { Ooxml.parse(flat.toByteArray()) }
        assertThrows(IOException::class.java) { Ooxml.parse(ByteArray(Ooxml.MAX_XML_BYTES + 1)) }
    }

    @Test fun zipPartSourceBoundsXmlBeforeDomParsing() {
        val file = Files.createTempFile("office-xml-limit", ".zip").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(ByteArray(Ooxml.MAX_XML_BYTES + 1))
                zip.closeEntry()
            }
            ZipPartSource(file).use { assertNull(it.read("word/document.xml")) }
        } finally {
            file.delete()
        }
    }

    @Test fun pptxRejectsTooManySlidesBeforeRendering() {
        val count = FileKind.MAX_IMPORT_PAGES + 1
        val slides = (1..count).joinToString("") { "<p:sldId id=\"$it\" r:id=\"r$it\"/>" }
        val relationships = (1..count).joinToString("") {
            "<Relationship Id=\"r$it\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/s$it.xml\"/>"
        }
        val src = parts(
            "ppt/presentation.xml" to "<p:presentation xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" xmlns:r=\"${Ooxml.NS_R}\"><p:sldIdLst>$slides</p:sldIdLst></p:presentation>",
            "ppt/_rels/presentation.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$relationships</Relationships>",
        )
        assertThrows(IOException::class.java) { PptxReader(src) }
    }

    @Test fun docxRejectsOversizedGridSpanBeforeAllocatingColumns() {
        val doc = """<w:document $nsW><w:body><w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val="2147483647"/></w:tcPr><w:p/></w:tc></w:tr></w:tbl></w:body></w:document>"""
        assertThrows(IOException::class.java) { DocxReader(parts("word/document.xml" to doc)).read() }
    }
}
