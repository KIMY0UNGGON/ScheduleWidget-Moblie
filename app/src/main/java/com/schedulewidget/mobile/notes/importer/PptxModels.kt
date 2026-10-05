package com.schedulewidget.mobile.notes.importer

import org.w3c.dom.Element

enum class TAlign { LEFT, CENTER, RIGHT, JUSTIFY }

data class TRun(
    val text: String, val size: Float, val bold: Boolean, val italic: Boolean,
    val underline: Boolean, val strike: Boolean, val color: Int,
)

data class TPara(
    val runs: List<TRun>,
    val align: TAlign = TAlign.LEFT,
    /** Bullet / number text drawn before the first line ("•", "1."), null = none. */
    val bullet: String? = null,
    /** Left margin of the paragraph (pt) and first-line offset relative to it (negative = hanging). */
    val marginLeft: Float = 0f,
    val indent: Float = 0f,
    /** Height of an empty paragraph (its end-of-paragraph font size). */
    val emptySize: Float = 18f,
    val bulletColor: Int? = null,
)

data class TBody(
    val paras: List<TPara>,
    /** Insets left, top, right, bottom (pt). */
    val insets: List<Float> = listOf(7.2f, 3.6f, 7.2f, 3.6f),
    /** 't', 'c' or 'b'. */
    val anchor: Char = 't',
    val wrap: Boolean = true,
) {
    val isBlank: Boolean get() = paras.all { p -> p.runs.all { it.text.isBlank() } }
}

data class Box(val x: Float, val y: Float, val w: Float, val h: Float)

sealed interface SlideElement { val box: Box }

data class ShapeEl(
    override val box: Box,
    /** prstGeom: "rect", "roundRect", "ellipse" (anything else is drawn as a rectangle). */
    val geom: String,
    val fill: Int?,
    val line: Int?,
    val lineWidth: Float,
    val text: TBody?,
    /** A picture used as the shape's fill (package path). */
    val imageFill: String? = null,
) : SlideElement

data class LineEl(override val box: Box, val color: Int, val width: Float, val flipH: Boolean, val flipV: Boolean) : SlideElement

data class PictureEl(
    override val box: Box,
    val media: String?,
    /** Crop as fractions of the source image: left, top, right, bottom. */
    val crop: List<Float> = listOf(0f, 0f, 0f, 0f),
) : SlideElement

data class TableCell(val text: TBody, val fill: Int?, val span: Int, val rowSpan: Int, val covered: Boolean)
data class TableRow(val height: Float, val cells: List<TableCell>)
data class TableEl(override val box: Box, val cols: List<Float>, val rows: List<TableRow>, val lineColor: Int) : SlideElement

/** Something we can't draw (chart, SmartArt, OLE object): a labelled box. */
data class PlaceholderEl(override val box: Box, val label: String) : SlideElement

data class Slide(val background: Int, val backgroundImage: String?, val elements: List<SlideElement>)

/** Affine EMU -> pt mapping (group shapes nest these). */
data class Xf(val sx: Float = 1f / Units.EMU_PER_PT, val sy: Float = 1f / Units.EMU_PER_PT, val tx: Float = 0f, val ty: Float = 0f) {
    fun box(x: Long, y: Long, cx: Long, cy: Long) = Box(tx + x * sx, ty + y * sy, cx * sx, cy * sy)

    /** Child coordinates of a group (chOff/chExt) mapped onto its off/ext. */
    fun group(off: Pair<Long, Long>, ext: Pair<Long, Long>, chOff: Pair<Long, Long>, chExt: Pair<Long, Long>): Xf {
        val kx = if (chExt.first != 0L) ext.first.toFloat() / chExt.first else 1f
        val ky = if (chExt.second != 0L) ext.second.toFloat() / chExt.second else 1f
        return Xf(sx * kx, sy * ky, tx + sx * (off.first - chOff.first * kx), ty + sy * (off.second - chOff.second * ky))
    }
}

/** Per-level paragraph/run defaults from list styles (lvl1pPr..lvl9pPr); null = not set at this layer. */
data class Lvl(
    val size: Float? = null, val bold: Boolean? = null, val italic: Boolean? = null, val underline: Boolean? = null,
    val color: Int? = null, val align: TAlign? = null, val bullet: Bullet? = null, val marL: Float? = null, val indent: Float? = null,
) {
    fun over(under: Lvl?) = if (under == null) this else Lvl(
        size ?: under.size, bold ?: under.bold, italic ?: under.italic, underline ?: under.underline,
        color ?: under.color, align ?: under.align, bullet ?: under.bullet, marL ?: under.marL, indent ?: under.indent,
    )
}

sealed interface Bullet {
    data object None : Bullet
    data class Char(val text: String) : Bullet
    data class AutoNum(val scheme: String, val start: Int) : Bullet
}

data class BodyPr(
    val insets: List<Float?> = listOf(null, null, null, null), val anchor: Char? = null, val wrap: Boolean? = null,
    val fontScale: Float? = null,
) {
    fun over(under: BodyPr?) = if (under == null) this else BodyPr(
        insets.mapIndexed { i, v -> v ?: under.insets[i] }, anchor ?: under.anchor, wrap ?: under.wrap, fontScale ?: under.fontScale,
    )
}

/** Theme colour scheme plus the master's colour map (bg1 -> lt1, tx1 -> dk1, ...). */
class Theme(private val scheme: Map<String, Int>, private val map: Map<String, String>) {
    fun scheme(name: String): Int? {
        val key = map[name] ?: when (name) { "bg1" -> "lt1"; "tx1" -> "dk1"; "bg2" -> "lt2"; "tx2" -> "dk2"; else -> name }
        return scheme[key]
    }

    companion object {
        val DEFAULT = Theme(
            mapOf(
                "dk1" to Colors.rgb(0, 0, 0), "lt1" to Colors.rgb(255, 255, 255), "dk2" to Colors.hex("44546A")!!,
                "lt2" to Colors.hex("E7E6E6")!!, "accent1" to Colors.hex("4472C4")!!, "accent2" to Colors.hex("ED7D31")!!,
                "accent3" to Colors.hex("A5A5A5")!!, "accent4" to Colors.hex("FFC000")!!, "accent5" to Colors.hex("5B9BD5")!!,
                "accent6" to Colors.hex("70AD47")!!, "hlink" to Colors.hex("0563C1")!!, "folHlink" to Colors.hex("954F72")!!,
            ),
            emptyMap(),
        )

        fun parse(theme: Element?, clrMap: Element?): Theme {
            val scheme = HashMap<String, Int>()
            theme?.path("themeElements", "clrScheme")?.kids()?.forEach { slot ->
                val c = slot.child("srgbClr")?.attr("val")?.let(Colors::hex)
                    ?: slot.child("sysClr")?.let { Colors.hex(it.attr("lastClr")) ?: if (it.attr("val") == "window") Colors.rgb(255, 255, 255) else Colors.rgb(0, 0, 0) }
                if (c != null) scheme[slot.local] = c
            }
            val map = HashMap<String, String>()
            clrMap?.attributes?.let { a -> for (i in 0 until a.length) map[a.item(i).local] = a.item(i).nodeValue }
            return Theme(DEFAULT.schemeMap() + scheme, map)
        }
    }

    private fun schemeMap() = scheme
}
