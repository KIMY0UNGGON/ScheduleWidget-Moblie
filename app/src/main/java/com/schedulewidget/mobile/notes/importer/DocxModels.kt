package com.schedulewidget.mobile.notes.importer

import org.w3c.dom.Element

data class DocPage(val width: Float, val height: Float, val top: Float, val right: Float, val bottom: Float, val left: Float) {
    val contentWidth: Float get() = width - left - right
    val contentHeight: Float get() = height - top - bottom

    companion object {
        /** A4 portrait with Word's 1-inch margins. */
        val A4 = DocPage(595.3f, 841.9f, 72f, 72f, 72f, 72f)
    }
}

sealed interface DBlock

data class DPara(
    val runs: List<TRun>,
    val align: TAlign = TAlign.LEFT,
    /** List number / bullet ("1.", "•"), drawn in the hanging indent. */
    val prefix: String? = null,
    val indentLeft: Float = 0f,
    val indentRight: Float = 0f,
    /** First-line offset relative to [indentLeft]; negative = hanging. */
    val firstLine: Float = 0f,
    val spaceBefore: Float = 0f,
    val spaceAfter: Float = 0f,
    /** Line height multiplier (Word "auto" line spacing / 240). */
    val lineSpacing: Float = 1f,
    /** Height of the paragraph when it has no text. */
    val emptySize: Float = 11f,
    /** 1..9 for "heading N" styles, 0 otherwise. */
    val heading: Int = 0,
) : DBlock

data class DImage(val media: String?, val width: Float, val height: Float, val align: TAlign = TAlign.LEFT) : DBlock

data class DCell(val blocks: List<DBlock>, val span: Int = 1, val fill: Int? = null, val mergedContinue: Boolean = false)
data class DRow(val minHeight: Float, val cells: List<DCell>)
data class DTable(val cols: List<Float>, val rows: List<DRow>) : DBlock

data object DPageBreak : DBlock

data class DocxDoc(val page: DocPage, val blocks: List<DBlock>)

/** Run properties; null = inherit. */
data class RProps(
    val size: Float? = null, val bold: Boolean? = null, val italic: Boolean? = null, val underline: Boolean? = null,
    val strike: Boolean? = null, val color: Int? = null,
) {
    fun over(under: RProps?) = if (under == null) this else RProps(
        size ?: under.size, bold ?: under.bold, italic ?: under.italic, underline ?: under.underline,
        strike ?: under.strike, color ?: under.color,
    )
}

/** Paragraph properties; null = inherit. */
data class PProps(
    val align: TAlign? = null, val before: Float? = null, val after: Float? = null, val line: Float? = null,
    val left: Float? = null, val right: Float? = null, val firstLine: Float? = null,
    val numId: String? = null, val ilvl: Int? = null, val pageBreakBefore: Boolean? = null,
) {
    fun over(under: PProps?) = if (under == null) this else PProps(
        align ?: under.align, before ?: under.before, after ?: under.after, line ?: under.line,
        left ?: under.left, right ?: under.right, firstLine ?: under.firstLine,
        numId ?: under.numId, ilvl ?: under.ilvl, pageBreakBefore ?: under.pageBreakBefore,
    )
}

object DocxProps {
    fun run(rPr: Element?): RProps {
        rPr ?: return RProps()
        fun toggle(name: String) = rPr.child(name)?.let { parseOnOff(it.attr("val")) }
        return RProps(
            size = rPr.child("sz")?.intAttr("val")?.let { Units.halfPtToPt(it) },
            bold = toggle("b"),
            italic = toggle("i"),
            underline = rPr.child("u")?.let { it.attr("val") != "none" },
            strike = toggle("strike") ?: toggle("dstrike"),
            color = rPr.child("color")?.attr("val")?.let { if (it == "auto") Colors.rgb(0, 0, 0) else Colors.hex(it) },
        )
    }

    fun paragraph(pPr: Element?): PProps {
        pPr ?: return PProps()
        val spacing = pPr.child("spacing")
        val ind = pPr.child("ind")
        val numPr = pPr.child("numPr")
        val line = spacing?.intAttr("line")?.let { v ->
            // "auto" is in 240ths of a line; exact/atLeast values are twips — approximate as a multiple of 12 pt.
            if ((spacing.attr("lineRule") ?: "auto") == "auto") v / 240f else (Units.twipToPt(v) / 13.8f).coerceIn(0.8f, 3f)
        }
        val hanging = ind?.intAttr("hanging")?.let { -Units.twipToPt(it) }
        return PProps(
            align = when (pPr.child("jc")?.attr("val")) {
                "center" -> TAlign.CENTER
                "right", "end" -> TAlign.RIGHT
                "both", "distribute" -> TAlign.JUSTIFY
                "left", "start" -> TAlign.LEFT
                else -> null
            },
            before = spacing?.intAttr("before")?.let { Units.twipToPt(it) },
            after = spacing?.intAttr("after")?.let { Units.twipToPt(it) },
            line = line,
            left = (ind?.intAttr("left") ?: ind?.intAttr("start"))?.let { Units.twipToPt(it) },
            right = (ind?.intAttr("right") ?: ind?.intAttr("end"))?.let { Units.twipToPt(it) },
            firstLine = hanging ?: ind?.intAttr("firstLine")?.let { Units.twipToPt(it) },
            numId = numPr?.child("numId")?.attr("val"),
            ilvl = numPr?.child("ilvl")?.intAttr("val"),
            pageBreakBefore = pPr.child("pageBreakBefore")?.let { parseOnOff(it.attr("val")) },
        )
    }

    /** Page size and margins from a w:sectPr (A4 / 1 inch when missing). */
    fun page(sectPr: Element?): DocPage {
        val sz = sectPr?.child("pgSz")
        val mar = sectPr?.child("pgMar")
        val d = DocPage.A4
        var w = sz?.intAttr("w")?.let { Units.twipToPt(it) } ?: d.width
        var h = sz?.intAttr("h")?.let { Units.twipToPt(it) } ?: d.height
        if (sz?.attr("orient") == "landscape" && w < h) { val t = w; w = h; h = t }
        fun m(name: String, def: Float) = mar?.intAttr(name)?.let { Units.twipToPt(it).let(Math::abs) } ?: def
        return DocPage(w, h, m("top", d.top), m("right", d.right), m("bottom", d.bottom), m("left", d.left))
    }
}

/** Numbering definitions (numbering.xml) and the running list counters. */
class DocxNumbering(root: Element?) {
    data class Level(val start: Int, val format: String, val text: String, val props: PProps)

    private val abstracts = HashMap<String, Map<Int, Level>>()
    private val nums = HashMap<String, Pair<String, Map<Int, Int>>>()
    private val counters = HashMap<String, IntArray>()

    init {
        root?.children("abstractNum")?.forEach { a ->
            val id = a.attr("abstractNumId") ?: return@forEach
            abstracts[id] = a.children("lvl").associate { l ->
                (l.intAttr("ilvl") ?: 0) to Level(
                    l.child("start")?.intAttr("val") ?: 1, l.child("numFmt")?.attr("val") ?: "decimal",
                    l.child("lvlText")?.attr("val") ?: "", DocxProps.paragraph(l.child("pPr")),
                )
            }
        }
        root?.children("num")?.forEach { n ->
            val id = n.attr("numId") ?: return@forEach
            val abs = n.child("abstractNumId")?.attr("val") ?: return@forEach
            val overrides = n.children("lvlOverride").mapNotNull { o ->
                val s = o.child("startOverride")?.intAttr("val") ?: return@mapNotNull null
                (o.intAttr("ilvl") ?: 0) to s
            }.toMap()
            nums[id] = abs to overrides
        }
    }

    fun level(numId: String, ilvl: Int): Level? {
        val (abs, over) = nums[numId] ?: return null
        val l = abstracts[abs]?.get(ilvl) ?: return null
        return over[ilvl]?.let { l.copy(start = it) } ?: l
    }

    /** Advances the counter of [numId]/[ilvl] and returns the label ("1.", "가.", "•"); null for "none" / unknown. */
    fun next(numId: String, ilvl: Int): String? {
        if (numId == "0") return null
        val lvl = level(numId, ilvl) ?: return null
        val c = counters.getOrPut(numId) { IntArray(9) { -1 } }
        c[ilvl] = if (c[ilvl] < 0) lvl.start else c[ilvl] + 1
        for (i in ilvl + 1 until 9) c[i] = -1
        return when (lvl.format) {
            "none" -> null
            "bullet" -> listOf("•", "◦", "▪")[ilvl % 3]
            else -> {
                var t = lvl.text
                for (k in 1..9) {
                    if (!t.contains("%$k")) continue
                    val l = level(numId, k - 1)
                    val v = if (k - 1 == ilvl) c[ilvl] else c[k - 1].takeIf { it >= 0 } ?: (l?.start ?: 1)
                    t = t.replace("%$k", format(v, l?.format ?: "decimal"))
                }
                t.ifBlank { null }
            }
        }
    }

    companion object {
        private const val GANADA = "가나다라마바사아자차카타파하"
        private const val CHOSUNG = "ㄱㄴㄷㄹㅁㅂㅅㅇㅈㅊㅋㅌㅍㅎ"

        fun format(n: Int, fmt: String): String = when (fmt) {
            "lowerLetter" -> PptxReader.letters(n).lowercase()
            "upperLetter" -> PptxReader.letters(n)
            "lowerRoman" -> PptxReader.roman(n).lowercase()
            "upperRoman" -> PptxReader.roman(n)
            "decimalZero" -> if (n in 0..9) "0$n" else n.toString()
            "ganada" -> if (n >= 1) GANADA[(n - 1) % GANADA.length].toString() else n.toString()
            "chosung" -> if (n >= 1) CHOSUNG[(n - 1) % CHOSUNG.length].toString() else n.toString()
            "decimalEnclosedCircle", "decimalEnclosedCircleChinese" -> if (n in 1..20) (Char(0x2460 + n - 1)).toString() else n.toString()
            else -> n.toString()
        }
    }
}
