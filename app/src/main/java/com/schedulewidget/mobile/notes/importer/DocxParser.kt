package com.schedulewidget.mobile.notes.importer

import org.w3c.dom.Element

// Offline DOCX reader: the document body as a flat list of blocks (paragraphs with styled runs, images, tables, page
// breaks) with styles, numbering and the page setup resolved. Pure JVM; the renderer paginates it onto PDF pages.

/** Reads word/document.xml (+ styles, numbering, relationships) into a [DocxDoc]. */
class DocxReader(private val src: PartSource) {
    private class Style(val name: String, val basedOn: String?, val r: RProps, val p: PProps)

    private val styles = HashMap<String, Style>()
    private var defaultParagraphStyle: String? = null
    private var docR = RProps()
    private var docP = PProps()
    /** The main part (normally word/document.xml; the package's root relationship says for sure). */
    private val doc: String = Ooxml.rels(src, "").values.firstOrNull { it.type.endsWith("/officeDocument") && !it.external }?.target ?: DOC
    private val rels: Map<String, Ooxml.Rel> = Ooxml.rels(src, doc)
    private val numbering = DocxNumbering(Ooxml.parsePart(src, partOf("/numbering", "word/numbering.xml")))

    private fun partOf(typeSuffix: String, fallback: String) =
        rels.values.firstOrNull { it.type.endsWith(typeSuffix) && !it.external }?.target ?: fallback

    init {
        Ooxml.parsePart(src, partOf("/styles", "word/styles.xml"))?.let { root ->
            root.child("docDefaults")?.let { d ->
                docR = DocxProps.run(d.path("rPrDefault", "rPr"))
                docP = DocxProps.paragraph(d.path("pPrDefault", "pPr"))
            }
            for (s in root.children("style")) {
                val id = s.attr("styleId") ?: continue
                styles[id] = Style(
                    s.child("name")?.attr("val") ?: id, s.child("basedOn")?.attr("val"),
                    DocxProps.run(s.child("rPr")), DocxProps.paragraph(s.child("pPr")),
                )
                if (s.attr("type") == "paragraph" && parseOnOff(s.attr("default"), false)) defaultParagraphStyle = id
            }
        }
    }

    fun read(): DocxDoc {
        val root = Ooxml.parsePart(src, doc) ?: error("document.xml 없음")
        val body = root.child("body") ?: return DocxDoc(DocPage.A4, emptyList())
        val page = DocxProps.page(body.child("sectPr"))
        return DocxDoc(page, blocks(body, page.contentWidth))
    }

    /** Block-level content of a body / table cell / text box. */
    fun blocks(container: Element, width: Float): List<DBlock> {
        val out = ArrayList<DBlock>()
        for (e in container.kids()) {
            when (e.local) {
                "p" -> paragraph(e, width, out)
                "tbl" -> out += table(e, width)
                "sdt" -> e.child("sdtContent")?.let { out += blocks(it, width) }
                "customXml", "ins" -> out += blocks(e, width)
                "AlternateContent" -> {
                    val holder = e.ownerDocument.createElement("x")
                    e.alternateContentBody().forEach { holder.appendChild(it.cloneNode(true)) }
                    out += blocks(holder, width)
                }
            }
        }
        return out
    }

    private fun styleChain(id: String?): List<Style> {
        val out = ArrayList<Style>()
        var cur = id
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur)) {
            val s = styles[cur] ?: break
            out += s
            cur = s.basedOn
        }
        return out
    }

    /** Heading level from a style's name ("heading 2" / "Heading2" / "제목 2"); 0 if none. */
    private fun headingOf(id: String?): Int {
        val names = styleChain(id).map { it.name.lowercase().replace(" ", "") } + listOfNotNull(id?.lowercase())
        for (n in names) {
            Regex("^(heading|제목)(\\d)$").find(n)?.let { return it.groupValues[2].toInt() }
        }
        return 0
    }

    private fun paragraph(p: Element, width: Float, out: MutableList<DBlock>) {
        val pPr = p.child("pPr")
        val styleId = pPr?.child("pStyle")?.attr("val") ?: defaultParagraphStyle
        val chain = styleChain(styleId)
        var pp = DocxProps.paragraph(pPr)
        for (s in chain) pp = pp.over(s.p)
        pp = pp.over(docP)
        var baseR = RProps()
        for (s in chain) baseR = baseR.over(s.r)
        val heading = headingOf(styleId)
        if (heading > 0) {
            // Headings without sizes in styles.xml (or no styles part at all) still stand out.
            baseR = baseR.copy(
                size = baseR.size ?: when (heading) { 1 -> 20f; 2 -> 16f; 3 -> 14f; else -> 12f },
                bold = baseR.bold ?: true,
            )
        }
        baseR = baseR.over(docR)
        val markR = DocxProps.run(pPr?.child("rPr")).over(baseR)

        var prefix: String? = null
        var numProps: PProps? = null
        if (pp.numId != null) {
            val ilvl = (pp.ilvl ?: 0).coerceIn(0, 8)
            prefix = numbering.next(pp.numId, ilvl)
            numProps = numbering.level(pp.numId, ilvl)?.props
        }
        // Direct indents win over the list level's, which win over the style's.
        val direct = DocxProps.paragraph(pPr)
        val left = direct.left ?: numProps?.left ?: pp.left ?: 0f
        val firstLine = direct.firstLine ?: numProps?.firstLine ?: pp.firstLine ?: 0f

        val items = ArrayList<Any>() // TRun | DImage | DPageBreak
        val extra = ArrayList<DBlock>() // text-box contents, placed after the paragraph
        inline(p, baseR, items, extra, width)

        val size = baseR.size ?: 11f
        fun para(runs: List<TRun>, first: Boolean, last: Boolean) = DPara(
            runs, pp.align ?: TAlign.LEFT, if (first) prefix else null, left, pp.right ?: 0f,
            if (first) firstLine else 0f, if (first) pp.before ?: 0f else 0f, if (last) pp.after ?: 0f else 0f,
            pp.line ?: 1f, markR.size ?: size, heading,
        )
        if (pp.pageBreakBefore == true) out += DPageBreak
        // Split at images and page breaks so the renderer only deals with simple blocks.
        val segs = ArrayList<Any>()
        var runs = ArrayList<TRun>()
        for (it in items) {
            if (it is TRun) runs += it
            else { segs.add(runs); segs.add(it); runs = ArrayList() }
        }
        segs.add(runs)
        val nonEmpty = segs.filter { it !is List<*> || it.isNotEmpty() }
        if (nonEmpty.isEmpty()) {
            out += para(emptyList(), first = true, last = true)
        } else {
            var first = true
            nonEmpty.forEachIndexed { i, seg ->
                val last = i == nonEmpty.size - 1
                when (seg) {
                    is List<*> -> { @Suppress("UNCHECKED_CAST") out += para(seg as List<TRun>, first, last); first = false }
                    is DImage -> out += seg.copy(align = pp.align ?: TAlign.LEFT)
                    is DPageBreak -> out += DPageBreak
                }
            }
        }
        out += extra
    }

    private fun inline(parent: Element, base: RProps, items: MutableList<Any>, extra: MutableList<DBlock>, width: Float) {
        for (e in parent.kids()) {
            when (e.local) {
                "r" -> run(e, base, items, extra, width)
                "hyperlink", "ins", "smartTag", "fldSimple", "customXml", "moveTo", "dir", "bdo" -> inline(e, base, items, extra, width)
                "sdt" -> e.child("sdtContent")?.let { inline(it, base, items, extra, width) }
                "AlternateContent" -> e.alternateContentBody().forEach { k ->
                    val holder = e.ownerDocument.createElement("x").apply { appendChild(k.cloneNode(true)) }
                    inline(holder, base, items, extra, width)
                }
            }
        }
    }

    private fun run(r: Element, base: RProps, items: MutableList<Any>, extra: MutableList<DBlock>, width: Float) {
        val rPr = r.child("rPr")
        var props = DocxProps.run(rPr)
        rPr?.child("rStyle")?.attr("val")?.let { cs -> for (s in styleChain(cs)) props = props.over(s.r) }
        props = props.over(base)
        if (rPr?.child("vanish") != null && parseOnOff(rPr.child("vanish")?.attr("val"))) return
        fun add(text: String) {
            items += TRun(
                text, props.size ?: 11f, props.bold ?: false, props.italic ?: false, props.underline ?: false,
                props.strike ?: false, props.color ?: Colors.rgb(0, 0, 0),
            )
        }
        for (k in r.kids()) {
            when (k.local) {
                "t" -> add(k.textContent)
                "tab", "ptab" -> add("    ")
                "br" -> if (k.attr("type") == "page") items += DPageBreak else add("\n")
                "cr" -> add("\n")
                "noBreakHyphen" -> add("-")
                "drawing" -> drawing(k, items, extra, width)
                "pict", "object" -> vml(k, items)
                "AlternateContent" -> {
                    val holder = r.ownerDocument.createElement("x")
                    k.alternateContentBody().forEach { holder.appendChild(it.cloneNode(true)) }
                    for (c in holder.kids()) when (c.local) {
                        "drawing" -> drawing(c, items, extra, width)
                        "pict" -> vml(c, items)
                    }
                }
            }
        }
    }

    private fun drawing(d: Element, items: MutableList<Any>, extra: MutableList<DBlock>, width: Float) {
        val holder = d.kids().firstOrNull { it.local == "inline" || it.local == "anchor" } ?: return
        val ext = holder.child("extent")
        val w = Units.emuToPt(ext?.longAttr("cx") ?: 0)
        val h = Units.emuToPt(ext?.longAttr("cy") ?: 0)
        val blip = holder.descendants("blip").firstOrNull()
        if (blip != null) {
            val media = blip.rAttr("embed")?.let { rels[it] }?.takeUnless { it.external }?.target
            if (w > 0 && h > 0) items += DImage(media, w, h)
            return
        }
        // Text boxes / shapes with text: their paragraphs flow after this one.
        holder.descendants("txbxContent").forEach { extra += blocks(it, width) }
    }

    private fun vml(pict: Element, items: MutableList<Any>) {
        val data = pict.descendants("imagedata").firstOrNull() ?: return
        val media = data.rAttr("id")?.let { rels[it] }?.target ?: return
        val style = (data.parentNode as? Element)?.attr("style").orEmpty()
        fun dim(name: String) = Regex("$name:\\s*([0-9.]+)pt").find(style)?.groupValues?.get(1)?.toFloatOrNull()
        items += DImage(media, dim("width") ?: 200f, dim("height") ?: 150f)
    }

    private fun table(tbl: Element, width: Float): DTable {
        var cols = tbl.path("tblGrid")?.children("gridCol")?.map { Units.twipToPt(it.intAttr("w") ?: 0) }.orEmpty()
        val rows = tbl.children("tr")
        val maxCells = rows.maxOfOrNull { tr -> tr.children("tc").sumOf { it.path("tcPr", "gridSpan")?.intAttr("val") ?: 1 } } ?: 1
        if (cols.isEmpty() || cols.sum() <= 0f) cols = List(maxCells.coerceAtLeast(1)) { width / maxCells.coerceAtLeast(1) }
        // Wider than the text column (or pt-less grids): scale down to fit.
        val total = cols.sum()
        if (total > width) cols = cols.map { it * width / total }
        return DTable(
            cols,
            rows.map { tr ->
                val minH = tr.path("trPr", "trHeight")?.intAttr("val")?.let { Units.twipToPt(it) } ?: 0f
                var col = 0
                DRow(
                    minH,
                    tr.children("tc").map { tc ->
                        val tcPr = tc.child("tcPr")
                        val span = tcPr?.child("gridSpan")?.intAttr("val") ?: 1
                        val cellW = cols.drop(col).take(span).sum().takeIf { it > 0 } ?: (width / maxCells)
                        col += span
                        val vMerge = tcPr?.child("vMerge")
                        val cont = vMerge != null && vMerge.attr("val") != "restart"
                        val fill = tcPr?.child("shd")?.attr("fill")?.let(Colors::hex)
                        DCell(if (cont) emptyList() else blocks(tc, cellW - 10.8f), span, fill, cont)
                    },
                )
            },
        )
    }

    companion object { const val DOC = "word/document.xml" }
}
