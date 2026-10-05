package com.schedulewidget.mobile.notes.ink

import com.schedulewidget.mobile.notes.NoteMeta
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

// The notebook data on disk (pure Kotlin, no Android types: unit-tested on the JVM).
// Coordinates are PDF points of the page (1/72 inch, origin top-left), so ink stays put at any zoom or export size.

/** Stroke kinds. Highlighter strokes are drawn under pen strokes. */
object Tool {
    /** Fountain-style pen: width follows pressure. */
    const val PEN = 0
    const val HIGHLIGHTER = 1
    /** Even width regardless of pressure. */
    const val BALLPOINT = 2
    /** Grainy, slightly translucent graphite; pressure changes darkness more than width. */
    const val PENCIL = 3
    /** Strong pressure response: thin hairlines to wide strokes. */
    const val BRUSH = 4
}

/**
 * One handwriting stroke. [pts] holds x, y, pressure triples in page points (pressure 0..1; fingers report 1).
 * Instances are immutable: edits make new strokes, so undo snapshots can share them.
 */
@Serializable
class Stroke(
    val id: Long,
    val tool: Int = Tool.PEN,
    /** ARGB; the highlighter's translucency is applied when drawing, not stored here. */
    val color: Int,
    /** Nominal width in page points at full pressure. */
    val width: Float,
    val pts: FloatArray,
    /** Lecture recording (record.Recordings id) running when the stroke was written, and the offset into it. */
    val rec: String? = null,
    val recMs: Long = 0,
) {
    val count: Int get() = pts.size / 3
    fun x(i: Int) = pts[i * 3]
    fun y(i: Int) = pts[i * 3 + 1]
    fun p(i: Int) = pts[i * 3 + 2]

    /** Half the drawn width at point [i] (per tool kind, see InkGeometry.halfWidth). */
    fun halfWidth(i: Int): Float = InkGeometry.halfWidth(tool, width, pts, count, i, if (tool == Tool.BRUSH) arcLengths() else null)

    /** Half the widest width any point of this stroke can be drawn with. */
    val maxHalfWidth: Float get() = width * InkGeometry.maxWidthFactor(tool) / 2f

    @Transient private var cachedArc: FloatArray? = null

    /** Cumulative path length at each point (computed once; brush tapers use it). */
    fun arcLengths(): FloatArray = cachedArc ?: InkGeometry.arcLengths(pts, count).also { cachedArc = it }

    @Transient private var cachedBounds: FloatArray? = null

    /** left, top, right, bottom including the stroke width. */
    fun bounds(): FloatArray = cachedBounds ?: run {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until count) {
            val x = x(i); val y = y(i)
            if (x < l) l = x; if (x > r) r = x
            if (y < t) t = y; if (y > b) b = y
        }
        val hw = maxHalfWidth
        floatArrayOf(l - hw, t - hw, r + hw, b + hw).also { cachedBounds = it }
    }

    fun copy(
        id: Long = this.id, color: Int = this.color, pts: FloatArray = this.pts,
    ) = Stroke(id, tool, color, width, pts, rec, recMs)
}

/** A typed text box; ([x], [y]) is its top-left corner, [size] the font size, both in page points. */
@Serializable
data class TextBox(
    val id: Long,
    val x: Float,
    val y: Float,
    val text: String,
    val color: Int = 0xFF000000.toInt(),
    val size: Float = 14f,
)

/** Everything written on one page (ink/<pageUid>.json). */
@Serializable
data class PageInk(
    val strokes: List<Stroke> = emptyList(),
    val texts: List<TextBox> = emptyList(),
) {
    val isEmpty: Boolean get() = strokes.isEmpty() && texts.isEmpty()

    companion object {
        val EMPTY = PageInk()
    }
}

/** Page templates of blank pages. */
object Template {
    const val PLAIN = "plain"
    const val LINED = "lined"
    const val GRID = "grid"
    const val DOT = "dot"
    val all = listOf(PLAIN, LINED, GRID, DOT)
    fun label(t: String) = when (t) { LINED -> "줄"; GRID -> "모눈"; DOT -> "점"; else -> "무지" }
}

/**
 * One page of a notebook. [uid] is stable (ink files are keyed by it), so pages can be reordered, inserted and
 * duplicated freely. [kind] "pdf" = page [pdf] (0-based) of base.pdf; "blank" = [template] drawn as vectors.
 * [w] × [h] is the page size in PDF points as shown (after [rot]).
 * [src] = file name (in the notebook folder) of the PDF a "pdf" page comes from; null = base.pdf (pages inserted
 * from other files get their own source file). [rot] = clockwise rotation (0/90/180/270) of the source PDF page.
 * [paper] = paper colour of a blank page (see render.PageTemplates); null = white.
 */
@Serializable
data class PageInfo(
    val uid: String,
    val kind: String = KIND_BLANK,
    val pdf: Int = -1,
    val template: String = Template.PLAIN,
    val w: Float = A4_W,
    val h: Float = A4_H,
    val src: String? = null,
    val rot: Int = 0,
    val paper: String? = null,
) {
    val isPdf: Boolean get() = kind == KIND_PDF && pdf >= 0

    companion object {
        const val KIND_PDF = "pdf"
        const val KIND_BLANK = "blank"
        const val A4_W = 595f
        const val A4_H = 842f
    }
}

/** meta.json: the library's [NoteMeta] plus the page list. */
@Serializable
data class StoredNote(
    val v: Int = 1,
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val source: String,
    val folder: String? = null,
    val pages: List<PageInfo> = emptyList(),
) {
    fun toMeta() = NoteMeta(id, title, createdAt, updatedAt, pages.size, source, folder)
}

object InkJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    fun encodeInk(ink: PageInk): String = json.encodeToString(PageInk.serializer(), ink)
    fun decodeInk(text: String): PageInk = json.decodeFromString(PageInk.serializer(), text)
    fun encodeNote(note: StoredNote): String = json.encodeToString(StoredNote.serializer(), note)
    fun decodeNote(text: String): StoredNote = json.decodeFromString(StoredNote.serializer(), text)
}
