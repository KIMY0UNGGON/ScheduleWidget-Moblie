package com.schedulewidget.mobile.notes.importer

import org.w3c.dom.Element
import java.io.IOException

// Offline PPTX reader: turns slides into a flat list of boxes in PDF points (shapes with text, pictures, tables,
// placeholders for charts/SmartArt). Layout/master inheritance is followed only roughly: placeholder positions, text
// sizes/bullets from the list styles, theme colours, and the master/layout's own decorative shapes.

private val PRESET_COLORS = mapOf(
    "black" to "000000", "white" to "FFFFFF", "red" to "FF0000", "green" to "008000", "blue" to "0000FF",
    "yellow" to "FFFF00", "gray" to "808080", "grey" to "808080", "orange" to "FFA500", "darkBlue" to "00008B",
    "darkRed" to "8B0000", "darkGreen" to "006400", "lightGray" to "D3D3D3", "navy" to "000080", "purple" to "800080",
)

/** A DrawingML colour choice (srgbClr, schemeClr, ...) inside [holder], with its transforms. [ph] = phClr (style refs). */
fun drawingColor(holder: Element?, theme: Theme, ph: Int? = null): Int? {
    holder ?: return null
    for (c in holder.kids()) {
        val base = when (c.local) {
            "srgbClr" -> Colors.hex(c.attr("val"))
            "schemeClr" -> if (c.attr("val") == "phClr") ph else c.attr("val")?.let(theme::scheme)
            "sysClr" -> Colors.hex(c.attr("lastClr")) ?: if (c.attr("val") == "window") Colors.rgb(255, 255, 255) else Colors.rgb(0, 0, 0)
            "prstClr" -> Colors.hex(PRESET_COLORS[c.attr("val")] ?: "000000")
            "scrgbClr" -> Colors.rgb(
                ((c.intAttr("r") ?: 0) * 255 / 100000), ((c.intAttr("g") ?: 0) * 255 / 100000), ((c.intAttr("b") ?: 0) * 255 / 100000),
            )
            else -> null
        } ?: continue
        return Colors.transform(base, c.kids())
    }
    return null
}

/** Reads a .pptx package. */
class PptxReader(private val src: PartSource) {
    val widthPt: Float
    val heightPt: Float
    /** Slide part paths in presentation order. */
    val slides: List<String>
    private val presDefaults: Array<Lvl?>
    private val layoutCache = HashMap<String, PartInfo>()
    private val masterCache = HashMap<String, MasterInfo>()

    init {
        val pres = Ooxml.parsePart(src, "ppt/presentation.xml") ?: error("presentation.xml 없음")
        val (w, h) = slideSize(pres)
        widthPt = w; heightPt = h
        slides = slideOrder(pres, Ooxml.rels(src, "ppt/presentation.xml"))
        presDefaults = parseLevels(pres.child("defaultTextStyle"), Theme.DEFAULT)
    }

    private class PhInfo(val type: String, val idx: String?, val xfrm: Element?, val body: BodyPr, val levels: Array<Lvl?>)

    private class PartInfo(
        val path: String, val phs: List<PhInfo>, val shapes: Element?, val bg: Element?, val showMasterSp: Boolean, val master: String?,
    )

    private class MasterInfo(val part: PartInfo, val theme: Theme, val title: Array<Lvl?>, val body: Array<Lvl?>, val other: Array<Lvl?>)

    fun slide(index: Int): Slide {
        val path = slides[index]
        val root = Ooxml.parsePart(src, path) ?: throw IOException("슬라이드 정보를 읽을 수 없어요")
        if (root.child("cSld")?.child("spTree") == null) throw IOException("슬라이드 도형 정보를 읽을 수 없어요")
        val rels = Ooxml.rels(src, path)
        val layoutPath = rels.values.firstOrNull { it.type.endsWith("/slideLayout") }?.target
        val layout = layoutPath?.let(::layout)
        val master = layout?.master?.let(::master)
        val theme = master?.theme ?: Theme.DEFAULT
        val elements = ArrayList<SlideElement>()

        // Background: slide, else layout, else master; default white.
        var bgColor: Int? = null
        var bgImage: String? = null
        for ((bgRoot, part) in listOf(root.path("cSld", "bg") to path, layout?.bg to layout?.path, master?.part?.bg to master?.part?.path)) {
            if (bgRoot == null || part == null) continue
            val (c, img) = background(bgRoot, theme, part)
            if (c != null || img != null) { bgColor = c; bgImage = img; break }
        }

        val ctx = Ctx(theme, master, layout)
        val showMaster = parseOnOff(root.attr("showMasterSp")) && (layout?.showMasterSp ?: true)
        if (master != null && showMaster) master.part.shapes?.let { shapeTree(it, Xf(), master.part.path, ctx, elements, decorOnly = true) }
        layout?.shapes?.let { shapeTree(it, Xf(), layout.path, ctx, elements, decorOnly = true) }
        root.path("cSld", "spTree")?.let { shapeTree(it, Xf(), path, ctx, elements, decorOnly = false) }
        return Slide(bgColor ?: Colors.rgb(255, 255, 255), bgImage, elements)
    }

    private class Ctx(val theme: Theme, val master: MasterInfo?, val layout: PartInfo?)

    private fun background(bg: Element, theme: Theme, part: String): Pair<Int?, String?> {
        bg.child("bgPr")?.let { pr ->
            pr.child("solidFill")?.let { return drawingColor(it, theme) to null }
            pr.child("gradFill")?.let { g -> return drawingColor(g.path("gsLst", "gs"), theme) to null }
            pr.child("blipFill")?.child("blip")?.rAttr("embed")?.let { id -> return null to Ooxml.rels(src, part)[id]?.target }
        }
        bg.child("bgRef")?.let { return drawingColor(it, theme) to null }
        return null to null
    }

    private fun layout(path: String): PartInfo = layoutCache.getOrPut(path) {
        val root = Ooxml.parsePart(src, path)
        val master = Ooxml.rels(src, path).values.firstOrNull { it.type.endsWith("/slideMaster") }?.target
        val theme = master?.let(::master)?.theme ?: Theme.DEFAULT
        partInfo(path, root, theme, master)
    }

    private fun master(path: String): MasterInfo = masterCache.getOrPut(path) {
        val root = Ooxml.parsePart(src, path)
        val themePath = Ooxml.rels(src, path).values.firstOrNull { it.type.endsWith("/theme") }?.target
        val theme = Theme.parse(themePath?.let { Ooxml.parsePart(src, it) }, root?.child("clrMap"))
        val styles = root?.child("txStyles")
        MasterInfo(
            partInfo(path, root, theme, null), theme,
            parseLevels(styles?.child("titleStyle"), theme), parseLevels(styles?.child("bodyStyle"), theme),
            parseLevels(styles?.child("otherStyle"), theme),
        )
    }

    private fun partInfo(path: String, root: Element?, theme: Theme, master: String?): PartInfo {
        val tree = root?.path("cSld", "spTree")
        val phs = ArrayList<PhInfo>()
        tree?.descendants("sp")?.forEach { sp ->
            val ph = sp.path("nvSpPr", "nvPr", "ph") ?: return@forEach
            phs += PhInfo(
                normalizeType(ph.attr("type")), ph.attr("idx"), sp.path("spPr", "xfrm"),
                bodyPr(sp.path("txBody", "bodyPr")), parseLevels(sp.path("txBody", "lstStyle"), theme),
            )
        }
        return PartInfo(path, phs, tree, root?.path("cSld", "bg"), parseOnOff(root?.attr("showMasterSp")), master)
    }

    // ---- shape tree ----

    private fun shapeTree(tree: Element, xf: Xf, part: String, ctx: Ctx, out: MutableList<SlideElement>, decorOnly: Boolean) {
        for (e in tree.kids()) {
            if (e.local == "AlternateContent") {
                val holder = e.ownerDocument.createElement("x")
                // Treat the chosen branch's children as if they were direct children of the tree.
                e.alternateContentBody().forEach { holder.appendChild(it.cloneNode(true)) }
                shapeTree(holder, xf, part, ctx, out, decorOnly)
                continue
            }
            val nv = e.kids().firstOrNull { it.local.startsWith("nv") }
            if (parseOnOff(nv?.child("cNvPr")?.attr("hidden"), absent = false)) continue
            val isPh = nv?.path("nvPr", "ph") != null
            if (decorOnly && isPh) continue
            runCatching {
                when (e.local) {
                    "sp" -> shape(e, xf, part, ctx)?.let(out::add)
                    "pic" -> picture(e, xf, part)?.let(out::add)
                    "cxnSp" -> connector(e, xf, ctx)?.let(out::add)
                    "grpSp" -> {
                        val x = e.path("grpSpPr", "xfrm")
                        val inner = if (x == null) xf else xf.group(pair(x.child("off"), "x", "y"), pair(x.child("ext"), "cx", "cy"),
                            pair(x.child("chOff"), "x", "y"), pair(x.child("chExt"), "cx", "cy"))
                        shapeTree(e, inner, part, ctx, out, decorOnly)
                    }
                    "graphicFrame" -> graphicFrame(e, xf, part, ctx)?.let(out::add)
                    else -> Unit
                }
            }
        }
    }

    private fun pair(e: Element?, a: String, b: String) = (e?.longAttr(a) ?: 0L) to (e?.longAttr(b) ?: 0L)

    private fun boxOf(xfrm: Element?, xf: Xf): Box? {
        xfrm ?: return null
        val off = xfrm.child("off") ?: return null
        val ext = xfrm.child("ext") ?: return null
        return xf.box(off.longAttr("x") ?: 0, off.longAttr("y") ?: 0, ext.longAttr("cx") ?: 0, ext.longAttr("cy") ?: 0)
    }

    private fun placeholderOf(sp: Element, ctx: Ctx): Pair<PhInfo?, PhInfo?>? {
        val ph = sp.path("nvSpPr", "nvPr", "ph") ?: return null
        val type = normalizeType(ph.attr("type"))
        val idx = ph.attr("idx")
        val lay = ctx.layout?.phs?.let { list ->
            idx?.let { i -> list.firstOrNull { it.idx == i } } ?: list.firstOrNull { it.type == type }
        }
        // Masters have no subtitle placeholder; a subtitle inherits from the body one.
        val masterType = (lay?.type ?: type).let { if (it == "subTitle") "body" else it }
        val mas = ctx.master?.part?.phs?.firstOrNull { it.type == masterType }
        return lay to mas
    }

    private fun shape(sp: Element, xf: Xf, part: String, ctx: Ctx): SlideElement? {
        val theme = ctx.theme
        val phType = sp.path("nvSpPr", "nvPr", "ph")?.let { normalizeType(it.attr("type")) }
        val (lay, mas) = placeholderOf(sp, ctx) ?: (null to null)
        val spPr = sp.child("spPr")
        val box = boxOf(spPr?.child("xfrm"), xf) ?: boxOf(lay?.xfrm, Xf()) ?: boxOf(mas?.xfrm, Xf()) ?: return null
        val style = sp.child("style")
        val geom = spPr?.child("prstGeom")?.attr("prst") ?: "rect"

        var fill: Int? = null
        var imageFill: String? = null
        when {
            spPr?.child("noFill") != null -> Unit
            spPr?.child("solidFill") != null -> fill = drawingColor(spPr.child("solidFill"), theme)
            spPr?.child("gradFill") != null -> fill = drawingColor(spPr.child("gradFill")?.path("gsLst", "gs"), theme)
            spPr?.child("blipFill") != null -> imageFill = spPr.child("blipFill")?.child("blip")?.rAttr("embed")?.let { Ooxml.rels(src, part)[it]?.target }
            style?.child("fillRef") != null && (style.child("fillRef")?.intAttr("idx") ?: 0) > 0 -> fill = drawingColor(style.child("fillRef"), theme)
        }
        val (line, lineWidth) = lineOf(spPr?.child("ln"), style, theme)

        val txBody = sp.child("txBody")
        val text = txBody?.let {
            val kind = when (phType) {
                null -> ctx.master?.other
                "title" -> ctx.master?.title
                "dt", "ftr", "sldNum" -> ctx.master?.other
                else -> ctx.master?.body
            }
            val chain = listOfNotNull(parseLevels(it.child("lstStyle"), theme), lay?.levels, mas?.levels, kind, presDefaults)
            val fontRefColor = drawingColor(style?.child("fontRef"), theme)
            val body = bodyPr(it.child("bodyPr")).over(lay?.body).over(mas?.body)
            textBody(it, chain, body, fontRefColor ?: theme.scheme("tx1") ?: Colors.rgb(0, 0, 0), theme, defaultSize = if (phType == "title") 32f else 18f)
        }
        // Empty placeholders show nothing in a slideshow ("Click to add title" is editor-only).
        if (phType != null && (text == null || text.isBlank) && fill == null && line == null && imageFill == null) return null
        return ShapeEl(box, geom, fill, line, lineWidth, text?.takeUnless { it.isBlank }, imageFill)
    }

    private fun lineOf(ln: Element?, style: Element?, theme: Theme): Pair<Int?, Float> {
        val width = ln?.longAttr("w")?.let { Units.emuToPt(it) } ?: 0.75f
        return when {
            ln?.child("noFill") != null -> null to 0f
            ln?.child("solidFill") != null -> drawingColor(ln.child("solidFill"), theme) to width
            style?.child("lnRef") != null && (style.child("lnRef")?.intAttr("idx") ?: 0) > 0 -> drawingColor(style.child("lnRef"), theme) to width
            else -> null to 0f
        }
    }

    private fun connector(e: Element, xf: Xf, ctx: Ctx): SlideElement? {
        val x = e.path("spPr", "xfrm") ?: return null
        val box = boxOf(x, xf) ?: return null
        val (color, width) = lineOf(e.path("spPr", "ln"), e.child("style"), ctx.theme)
        return LineEl(box, color ?: return null, width.coerceAtLeast(0.5f), parseOnOff(x.attr("flipH"), false), parseOnOff(x.attr("flipV"), false))
    }

    private fun picture(pic: Element, xf: Xf, part: String): SlideElement? {
        val box = boxOf(pic.path("spPr", "xfrm"), xf) ?: return null
        val blipFill = pic.child("blipFill")
        val id = blipFill?.child("blip")?.rAttr("embed")
        val media = id?.let { Ooxml.rels(src, part)[it] }?.takeUnless { it.external }?.target
        val srcRect = blipFill?.child("srcRect")
        val crop = listOf("l", "t", "r", "b").map { (srcRect?.intAttr(it) ?: 0) / 100000f }
        return PictureEl(box, media, crop)
    }

    private fun graphicFrame(e: Element, xf: Xf, part: String, ctx: Ctx): SlideElement? {
        val box = boxOf(e.child("xfrm"), xf) ?: return null
        val data = e.path("graphic", "graphicData") ?: return PlaceholderEl(box, "(개체)")
        data.child("tbl")?.let { return table(it, box, ctx) }
        val uri = data.attr("uri").orEmpty()
        // OLE objects usually carry a preview picture.
        data.descendants("pic").firstOrNull()?.let { p -> picture(p, xf, part)?.let { return PictureEl(box, (it as PictureEl).media, it.crop) } }
        return PlaceholderEl(
            box, when {
                uri.contains("chart") -> "(차트)"
                uri.contains("diagram") -> "(SmartArt)"
                else -> "(개체)"
            },
        )
    }

    private fun table(tbl: Element, box: Box, ctx: Ctx): SlideElement {
        val theme = ctx.theme
        val cols = tbl.path("tblGrid")?.children("gridCol")?.map { Units.emuToPt(it.longAttr("w") ?: 0) }.orEmpty()
        val tblPr = tbl.child("tblPr")
        val styled = tblPr?.child("tableStyleId") != null
        val firstRow = parseOnOff(tblPr?.attr("firstRow"), false)
        val bandRow = parseOnOff(tblPr?.attr("bandRow"), false)
        val accent = theme.scheme("accent1") ?: Colors.hex("4472C4")!!
        val light = theme.scheme("lt1") ?: Colors.rgb(255, 255, 255)
        val dark = theme.scheme("dk1") ?: Colors.rgb(0, 0, 0)
        val chain = listOfNotNull(ctx.master?.other, presDefaults)
        val rows = tbl.children("tr").mapIndexed { r, tr ->
            val header = styled && firstRow && r == 0
            val band = if (firstRow) r - 1 else r
            val defaultFill = when {
                !styled -> null
                header -> accent
                bandRow && band % 2 == 0 -> Colors.transform(accent, listOf(mod(tbl, "tint", 40000)))
                else -> Colors.transform(accent, listOf(mod(tbl, "tint", 20000)))
            }
            TableRow(
                Units.emuToPt(tr.longAttr("h") ?: 0),
                tr.children("tc").map { tc ->
                    val tcPr = tc.child("tcPr")
                    val insets = listOf(
                        tcPr?.longAttr("marL") ?: 91440L, tcPr?.longAttr("marT") ?: 45720L,
                        tcPr?.longAttr("marR") ?: 91440L, tcPr?.longAttr("marB") ?: 45720L,
                    ).map { Units.emuToPt(it) }
                    val anchor = when (tcPr?.attr("anchor")) { "ctr" -> 'c'; "b" -> 'b'; else -> 't' }
                    val headerLvl = if (header) Lvl(bold = true, color = light) else Lvl(color = if (styled) dark else null)
                    val body = tc.child("txBody")
                    val text = body?.let {
                        textBody(it, chain, BodyPr(insets, anchor, true),
                            dark, theme, 18f, levelOverride = headerLvl)
                    } ?: TBody(emptyList(), insets, anchor)
                    val fill = when {
                        tcPr?.child("noFill") != null -> null
                        tcPr?.child("solidFill") != null -> drawingColor(tcPr.child("solidFill"), theme)
                        else -> defaultFill
                    }
                    val covered = parseOnOff(tc.attr("hMerge"), false) || parseOnOff(tc.attr("vMerge"), false)
                    TableCell(text, fill, tc.intAttr("gridSpan") ?: 1, tc.intAttr("rowSpan") ?: 1, covered)
                },
            )
        }
        return TableEl(box, cols, rows, if (styled) light else Colors.rgb(128, 128, 128))
    }

    /** A synthetic colour-transform element (tint/shade value). */
    private fun mod(any: Element, name: String, value: Int): Element =
        any.ownerDocument.createElementNS("http://schemas.openxmlformats.org/drawingml/2006/main", "a:$name").apply { setAttribute("val", value.toString()) }

    // ---- text ----

    private fun textBody(
        txBody: Element, chain: List<Array<Lvl?>>, body: BodyPr, defaultColor: Int, theme: Theme, defaultSize: Float,
        levelOverride: Lvl? = null,
    ): TBody {
        val own = bodyPr(txBody.child("bodyPr")).over(body)
        val scale = own.fontScale ?: 1f
        val counters = IntArray(9)
        var lastLevel = 0
        val paras = txBody.children("p").map { p ->
            val pPr = p.child("pPr")
            val level = (pPr?.intAttr("lvl") ?: 0).coerceIn(0, 8)
            var lvl = pPr?.let { parsePPr(it, theme) } ?: Lvl()
            if (levelOverride != null) lvl = levelOverride.over(lvl)
            for (layer in chain) lvl = lvl.over(layer.getOrNull(level))
            val size = (lvl.size ?: defaultSize) * scale
            val color = lvl.color ?: defaultColor
            val runs = ArrayList<TRun>()
            for (k in p.kids()) {
                when (k.local) {
                    "r", "fld" -> {
                        val t = k.child("t")?.textContent ?: continue
                        val rPr = k.child("rPr")
                        val r = rPr?.let { parseRPr(it, theme) } ?: Lvl()
                        runs += TRun(
                            t.replace('\u000B', '\n'), (r.size ?: lvl.size ?: defaultSize) * scale,
                            r.bold ?: lvl.bold ?: false, r.italic ?: lvl.italic ?: false,
                            r.underline ?: lvl.underline ?: false, parseOnOff(rPr?.attr("strike")?.let { if (it == "noStrike") "0" else it }, false),
                            r.color ?: color,
                        )
                    }
                    "br" -> runs += TRun("\n", size, false, false, false, false, color)
                }
            }
            // Auto numbers count per level; a shallower paragraph restarts the deeper levels.
            if (level < lastLevel) for (i in level + 1 until 9) counters[i] = 0
            lastLevel = level
            val hasText = runs.any { it.text.isNotBlank() }
            val bulletText = when (val b = lvl.bullet) {
                is Bullet.Char -> if (hasText) bulletChar(b.text) else null
                is Bullet.AutoNum -> if (hasText) autoNumber(b.scheme, b.start + counters[level]++) else null
                else -> null
            }
            val endSize = p.child("endParaRPr")?.intAttr("sz")?.let { Units.centiPtToPt(it) * scale } ?: size
            TPara(runs, lvl.align ?: TAlign.LEFT, bulletText, lvl.marL ?: 0f, lvl.indent ?: 0f, endSize)
        }
        return TBody(paras, own.insets.mapIndexed { i, v -> v ?: if (i % 2 == 0) 7.2f else 3.6f }, own.anchor ?: 't', own.wrap ?: true)
    }

    private fun parsePPr(pPr: Element, theme: Theme): Lvl {
        val bullet = when {
            pPr.child("buNone") != null -> Bullet.None
            pPr.child("buChar") != null -> Bullet.Char(pPr.child("buChar")?.attr("char") ?: "•")
            pPr.child("buAutoNum") != null -> pPr.child("buAutoNum")!!.let { Bullet.AutoNum(it.attr("type") ?: "arabicPeriod", it.intAttr("startAt") ?: 1) }
            else -> null
        }
        val align = when (pPr.attr("algn")) {
            "ctr" -> TAlign.CENTER; "r" -> TAlign.RIGHT; "just", "dist" -> TAlign.JUSTIFY; "l" -> TAlign.LEFT; else -> null
        }
        val def = pPr.child("defRPr")?.let { parseRPr(it, theme) } ?: Lvl()
        return Lvl(
            def.size, def.bold, def.italic, def.underline, def.color, align, bullet,
            pPr.longAttr("marL")?.let { Units.emuToPt(it) }, pPr.longAttr("indent")?.let { Units.emuToPt(it) },
        )
    }

    private fun parseRPr(rPr: Element, theme: Theme): Lvl = Lvl(
        size = rPr.intAttr("sz")?.let { Units.centiPtToPt(it) },
        bold = rPr.attr("b")?.let { parseOnOff(it) },
        italic = rPr.attr("i")?.let { parseOnOff(it) },
        underline = rPr.attr("u")?.let { it != "none" },
        color = rPr.child("solidFill")?.let { drawingColor(it, theme) },
    )

    private fun parseLevels(holder: Element?, theme: Theme): Array<Lvl?> {
        val out = arrayOfNulls<Lvl>(9)
        holder ?: return out
        for (i in 0 until 9) holder.child("lvl${i + 1}pPr")?.let { out[i] = parsePPr(it, theme) }
        return out
    }

    private fun bodyPr(e: Element?): BodyPr {
        e ?: return BodyPr()
        val insets = listOf("lIns", "tIns", "rIns", "bIns").map { k -> e.longAttr(k)?.let { Units.emuToPt(it) } }
        val anchor = when (e.attr("anchor")) { "t" -> 't'; "ctr" -> 'c'; "b" -> 'b'; else -> null }
        val wrap = e.attr("wrap")?.let { it != "none" }
        val scale = e.child("normAutofit")?.intAttr("fontScale")?.let { it / 100000f }
        return BodyPr(insets, anchor, wrap, scale)
    }

    companion object {
        /** p:sldSz in points; the 4:3 default (10 x 7.5 in) when missing. */
        fun slideSize(presentation: Element): Pair<Float, Float> {
            val sz = presentation.child("sldSz")
            val cx = sz?.longAttr("cx") ?: 9144000L
            val cy = sz?.longAttr("cy") ?: 6858000L
            return Units.emuToPt(cx) to Units.emuToPt(cy)
        }

        /** Slide parts in p:sldIdLst order (that order, not the file names, is the presentation order). */
        fun slideOrder(presentation: Element, rels: Map<String, Ooxml.Rel>): List<String> =
            presentation.child("sldIdLst")?.children("sldId").orEmpty().mapNotNull { s ->
                s.rAttr("id")?.let { rels[it] }?.takeIf { it.type.endsWith("/slide") }?.target
            }

        fun normalizeType(type: String?): String = when (type) {
            null, "", "obj" -> "body"
            "ctrTitle" -> "title"
            else -> type
        }

        /** Bullet characters from symbol fonts (Wingdings "§", "Ø") don't map to readable glyphs; use plain ones. */
        fun bulletChar(c: String): String = when (c) {
            "•", "–", "-", "▪", "■", "○", "◦", "➢", "✓", "►", "→", "*" -> c
            else -> "•"
        }

        fun autoNumber(scheme: String, n: Int): String {
            val core = when {
                scheme.startsWith("alphaLc") -> letters(n).lowercase()
                scheme.startsWith("alphaUc") -> letters(n)
                scheme.startsWith("romanLc") -> roman(n).lowercase()
                scheme.startsWith("romanUc") -> roman(n)
                scheme.startsWith("circleNumDb") -> if (n in 1..20) (Char(0x2460 + n - 1)).toString() else n.toString()
                else -> n.toString()
            }
            return when {
                scheme.endsWith("ParenBoth") -> "($core)"
                scheme.endsWith("ParenR") -> "$core)"
                scheme.endsWith("Plain") || scheme.startsWith("circleNum") -> core
                else -> "$core."
            }
        }

        fun letters(n: Int): String {
            if (n <= 0) return n.toString()
            val sb = StringBuilder()
            var v = n
            while (v > 0) { v--; sb.insert(0, 'A' + v % 26); v /= 26 }
            return sb.toString()
        }

        fun roman(n: Int): String {
            if (n <= 0 || n >= 4000) return n.toString()
            val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            val sb = StringBuilder()
            var v = n
            for (i in vals.indices) while (v >= vals[i]) { sb.append(syms[i]); v -= vals[i] }
            return sb.toString()
        }
    }
}
