package com.schedulewidget.mobile.pet

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Region
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.util.WeakHashMap

/** Alpha below this counts as transparent padding (lossy WebP leaves faint specks around a sprite). */
internal const val VISIBLE_ALPHA = 16

/**
 * Mask blocks per cell side at most: a 2k still image would otherwise give an outline too detailed to hit-test fast.
 * ponytail: such images get a silhouette up to one block too wide (bounds stay exact); sprite cells (192x208) are exact.
 */
private const val MAX_MASK = 256

/** The whole sprite box: used for a frame with nothing painted, or when the image could not be read. */
internal val FULL_BOX = Rect(0f, 0f, 1f, 1f)

/** Where the in-app pet's drawn frame is painted, as fractions of its sprite box (mirrored when flipped); null while loading. */
@Stable
class PaintedBounds {
    var fraction by mutableStateOf<Rect?>(null)
        internal set
}

/**
 * One cell's painted pixels (alpha >= [VISIBLE_ALPHA]) in cell px: tight bounds [left]..[right) x [top]..[bottom), and
 * [runs] = x0, y0, x1, y1 rectangles (end-exclusive) covering exactly them (in mask blocks for very large cells).
 */
internal class CellPixels(val left: Int, val top: Int, val right: Int, val bottom: Int, val runs: IntArray)

/**
 * Scans an ARGB image cut into [cols] x [rows] cells, row-major; pixels past the last whole cell are ignored, like the
 * drawing does. Null for a cell with nothing painted. Cells over [MAX_MASK] px are masked in blocks; bounds stay exact.
 */
internal fun scanCells(argb: IntArray, width: Int, height: Int, cols: Int, rows: Int): Array<CellPixels?> {
    val fw = if (cols > 0) width / cols else 0
    val fh = if (rows > 0) height / rows else 0
    if (fw <= 0 || fh <= 0) return arrayOfNulls(maxOf(cols, 0) * maxOf(rows, 0))
    val step = (maxOf(fw, fh) + MAX_MASK - 1) / MAX_MASK
    val gw = (fw + step - 1) / step
    val gh = (fh + step - 1) / step
    val mask = BooleanArray(gw * gh)
    return Array(cols * rows) { cell ->
        val x0 = cell % cols * fw
        val y0 = cell / cols * fh
        mask.fill(false)
        var left = fw
        var top = fh
        var right = 0
        var bottom = 0
        for (y in 0 until fh) {
            val line = (y0 + y) * width + x0
            val maskLine = y / step * gw
            for (x in 0 until fw) if (argb[line + x] ushr 24 >= VISIBLE_ALPHA) {
                if (x < left) left = x
                if (x >= right) right = x + 1
                if (y < top) top = y
                bottom = y + 1
                mask[maskLine + x / step] = true
            }
        }
        if (right == 0) null else CellPixels(left, top, right, bottom, runs(mask, gw, gh, step, fw, fh))
    }
}

/** The mask's runs as cell-px rectangles; a run repeated unchanged on the next mask row grows the same rectangle. */
private fun runs(mask: BooleanArray, gw: Int, gh: Int, step: Int, fw: Int, fh: Int): IntArray {
    val out = ArrayList<Int>()
    var open = ArrayList<IntArray>() // x0, x1, first mask row
    for (gy in 0..gh) {
        val next = ArrayList<IntArray>()
        var gx = 0
        while (gy < gh && gx < gw) {
            if (!mask[gy * gw + gx]) { gx++; continue }
            val start = gx
            while (gx < gw && mask[gy * gw + gx]) gx++
            val same = open.indexOfFirst { it[0] == start && it[1] == gx }
            next += if (same >= 0) open.removeAt(same) else intArrayOf(start, gx, gy)
        }
        for (run in open) out += listOf(run[0] * step, run[2] * step, minOf(run[1] * step, fw), minOf(gy * step, fh))
        open = next
    }
    return out.toIntArray()
}

/**
 * The nearest center (of a [box]-sized sprite) to [center] that keeps its [painted] part (fractions of the box, null =
 * the whole box) inside [area]; transparent margins may hang outside. A pet bigger than the area keeps its left/top in.
 */
internal fun clampToWalls(center: Offset, area: Size, box: Size, painted: Rect?): Offset {
    val p = painted ?: FULL_BOX
    val minX = box.width * (0.5f - p.left)
    val minY = box.height * (0.5f - p.top)
    return Offset(
        center.x.coerceIn(minX, (area.width - box.width * (p.right - 0.5f)).coerceAtLeast(minX)),
        center.y.coerceIn(minY, (area.height - box.height * (p.bottom - 0.5f)).coerceAtLeast(minY)),
    )
}

/** Every frame's painted bounds and touch silhouette for one decoded sprite image. Built off the main thread. */
internal class SpriteGeometry(private val cellW: Int, private val cellH: Int, private val cells: Array<CellPixels?>) {
    // A region's boundary is one clean outline per frame (outer edges and holes), cheap and robust for Compose's hit test.
    private val outlines = cells.map { cell ->
        cell?.runs?.let { r ->
            Region().apply { for (i in r.indices step 4) op(r[i], r[i + 1], r[i + 2], r[i + 3], Region.Op.UNION) }.boundaryPath
                .apply { fillType = android.graphics.Path.FillType.EVEN_ODD }
        }
    }
    private val shapes = arrayOfNulls<Shape>(cells.size * 2)

    /** Frame [cell]'s painted bounds as fractions of the sprite box, mirrored when [flipped]; the whole box when it has none. */
    fun bounds(cell: Int, flipped: Boolean): Rect {
        val c = cells.getOrNull(cell) ?: return FULL_BOX
        val l = c.left.toFloat() / cellW
        val r = c.right.toFloat() / cellW
        return Rect(if (flipped) 1f - r else l, c.top.toFloat() / cellH, if (flipped) 1f - l else r, c.bottom.toFloat() / cellH)
    }

    /** Frame [cell]'s silhouette as a clip shape (the whole box when it has none). Main thread only. */
    fun silhouette(cell: Int, flipped: Boolean): Shape {
        val path = outlines.getOrNull(cell) ?: return RectangleShape
        val i = cell * 2 + if (flipped) 1 else 0
        return shapes[i] ?: Silhouette(path, cellW, cellH, flipped).also { shapes[i] = it }
    }
}

/** One frame's silhouette scaled to the drawn size (rebuilt only when that size changes). */
private class Silhouette(
    private val path: android.graphics.Path,
    private val cellW: Int,
    private val cellH: Int,
    private val flipped: Boolean,
) : Shape {
    private var size = Size.Unspecified
    private var outline: Outline? = null

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        outline?.let { if (size == this.size) return it }
        val sx = size.width / cellW
        val scaled = android.graphics.Path(path).apply {
            transform(Matrix().apply {
                setScale(if (flipped) -sx else sx, size.height / cellH)
                if (flipped) postTranslate(size.width, 0f)
            })
        }
        return Outline.Generic(scaled.asComposePath()).also { this.size = size; outline = it }
    }
}

private val geometries = WeakHashMap<Bitmap, SpriteGeometry>()

/**
 * [SpriteGeometry] of a decoded sheet ([rows] rows of [Characters.COLUMNS] cells) or still image ([rows] 0), scanned once
 * per bitmap and cached with it; null when the pixels cannot be read. Call off the main thread.
 */
internal fun spriteGeometry(bitmap: Bitmap, rows: Int): SpriteGeometry? {
    synchronized(geometries) { geometries[bitmap] }?.let { return it }
    return runCatching {
        val cols = if (rows > 0) Characters.COLUMNS else 1
        val bands = rows.coerceAtLeast(1)
        val width = bitmap.width
        val cellH = bitmap.height / bands
        // One row of cells at a time: a whole sheet copied at once would be ~20 MB of ints.
        val band = IntArray(width * cellH)
        val cells = (0 until bands).flatMap { row ->
            bitmap.getPixels(band, 0, width, 0, row * cellH, width, cellH)
            scanCells(band, width, cellH, cols, 1).asList()
        }
        SpriteGeometry(width / cols, cellH, cells.toTypedArray())
    }.getOrNull()?.also { synchronized(geometries) { geometries[bitmap] = it } }
}
