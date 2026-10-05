package com.schedulewidget.mobile.notes.importer

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
internal object PptxRenderer {
    fun render(src: PartSource, out: File, progress: (Int, Int) -> Unit): Int {
        val reader = PptxReader(src)
        if (reader.slides.isEmpty()) error("슬라이드가 없어요")
        val images = ImageLoader(src)
        val pageW = reader.widthPt.roundToInt().coerceIn(72, 14400)
        val pageH = reader.heightPt.roundToInt().coerceIn(72, 14400)
        val doc = PdfDocument()
        try {
            reader.slides.indices.forEach { i ->
                progress(i + 1, reader.slides.size)
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create())
                try {
                    drawSlide(page.canvas, reader.slide(i), pageW.toFloat(), pageH.toFloat(), images)
                    doc.finishPage(page)
                } catch (e: CancellationException) {
                    runCatching { doc.finishPage(page) }
                    throw e
                } catch (e: Exception) {
                    runCatching { doc.finishPage(page) }
                    throw IOException("슬라이드 ${i + 1}을 그리지 못했어요", e)
                }
            }
            out.outputStream().buffered().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return reader.slides.size
    }

    fun drawSlide(c: Canvas, slide: Slide, w: Float, h: Float, images: ImageLoader) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = slide.background
        c.drawRect(0f, 0f, w, h, p)
        slide.backgroundImage?.let { Draw.image(c, images.load(it, w, h), RectF(0f, 0f, w, h)) }
        for (e in slide.elements) element(c, e, images)
    }

    private fun rect(b: Box) = RectF(b.x, b.y, b.x + b.w, b.y + b.h)

    private fun element(c: Canvas, e: SlideElement, images: ImageLoader) {
        val r = rect(e.box)
        when (e) {
            is ShapeEl -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                if (e.imageFill != null) Draw.image(c, images.load(e.imageFill, r.width(), r.height()), r)
                e.fill?.let { p.color = it; p.style = Paint.Style.FILL; shapePath(c, e.geom, r, p) }
                e.line?.let { p.color = it; p.style = Paint.Style.STROKE; p.strokeWidth = max(0.5f, e.lineWidth); shapePath(c, e.geom, r, p) }
                e.text?.let { text(c, it, r) }
            }
            is LineEl -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = e.color; strokeWidth = e.width; style = Paint.Style.STROKE }
                val x1 = if (e.flipH) r.right else r.left
                val x2 = if (e.flipH) r.left else r.right
                val y1 = if (e.flipV) r.bottom else r.top
                val y2 = if (e.flipV) r.top else r.bottom
                c.drawLine(x1, y1, x2, y2, p)
            }
            is PictureEl -> Draw.image(c, images.load(e.media, r.width(), r.height()), r, e.crop)
            is TableEl -> table(c, e)
            is PlaceholderEl -> Draw.placeholder(c, r, e.label)
        }
    }

    private fun shapePath(c: Canvas, geom: String, r: RectF, p: Paint) {
        when (geom) {
            "ellipse" -> c.drawOval(r, p)
            "roundRect" -> { val rad = min(r.width(), r.height()) * 0.1667f; c.drawRoundRect(r, rad, rad, p) }
            "line", "straightConnector1" -> c.drawLine(r.left, r.top, r.right, r.bottom, p)
            else -> c.drawRect(r, p)
        }
    }

    /** Text of a box, inside its insets, anchored top/middle/bottom; overflow is drawn like PowerPoint does. */
    fun text(c: Canvas, body: TBody, r: RectF): Float {
        if (body.paras.isEmpty()) return 0f
        val (l, t, rr, b) = body.insets
        val width = r.width() - l - rr
        val layout = TextLayouts.fromTBody(body, width)
        val h = layout.height.toFloat()
        val y = when (body.anchor) {
            'c' -> r.top + t + (r.height() - t - b - h) / 2
            'b' -> r.bottom - b - h
            else -> r.top + t
        }
        // Unwrapped text wider than its box grows around the box like PowerPoint's "do not wrap".
        val x = if (!body.wrap && layout.width > width) when (body.paras.first().align) {
            TAlign.CENTER -> r.centerX() - layout.width / 2f
            TAlign.RIGHT -> r.right - rr - layout.width
            else -> r.left + l
        } else r.left + l
        c.save()
        c.translate(x, y)
        layout.draw(c)
        c.restore()
        return h + t + b
    }

    private fun table(c: Canvas, t: TableEl) {
        val xs = FloatArray(t.cols.size + 1)
        for (i in t.cols.indices) xs[i + 1] = xs[i] + t.cols[i]
        var y = t.box.y
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.lineColor; style = Paint.Style.STROKE; strokeWidth = 0.75f }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        for (row in t.rows) {
            // Row height grows with its text.
            var rowH = row.height
            var col = 0
            for (cell in row.cells) {
                val span = cell.span.coerceAtLeast(1)
                if (!cell.covered && cell.text.paras.isNotEmpty() && col < t.cols.size) {
                    val w = xs[min(col + span, t.cols.size)] - xs[col] - cell.text.insets[0] - cell.text.insets[2]
                    val lh = TextLayouts.fromTBody(cell.text, w).height + cell.text.insets[1] + cell.text.insets[3]
                    if (cell.rowSpan <= 1) rowH = max(rowH, lh)
                }
                col += span
            }
            col = 0
            for (cell in row.cells) {
                val span = cell.span.coerceAtLeast(1)
                if (col >= t.cols.size) break
                val r = RectF(t.box.x + xs[col], y, t.box.x + xs[min(col + span, t.cols.size)], y + rowH)
                if (!cell.covered) {
                    cell.fill?.let { fill.color = it; c.drawRect(r, fill) }
                    text(c, cell.text, r)
                }
                c.drawRect(r, line)
                col += span
            }
            y += rowH
        }
    }
}

/** DOCX -> PDF: flows the blocks over pages of the document's page size. */
internal class DocxRenderer(private val src: PartSource, private val progress: (Int) -> Unit) {
    private val images = ImageLoader(src)
    private lateinit var doc: PdfDocument
    private lateinit var setup: DocPage
    private var page: PdfDocument.Page? = null
    private var pageNo = 0
    private var y = 0f

    private val canvas: Canvas get() = page!!.canvas
    private val top get() = setup.top
    private val bottom get() = setup.height - setup.bottom

    fun render(out: File): Int {
        val model = DocxReader(src).read()
        setup = model.page.let { if (it.contentWidth < 72 || it.contentHeight < 72) DocPage.A4 else it }
        doc = PdfDocument()
        try {
            newPage()
            for (b in model.blocks) block(b)
            page?.let { doc.finishPage(it) }
            page = null
            out.outputStream().buffered().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return pageNo
    }

    private fun newPage() {
        page?.let { doc.finishPage(it) }
        pageNo++
        progress(pageNo)
        page = doc.startPage(PdfDocument.PageInfo.Builder(setup.width.roundToInt(), setup.height.roundToInt(), pageNo).create())
        y = top
    }

    private fun block(b: DBlock) {
        when (b) {
            is DPageBreak -> if (y > top) newPage()
            is DPara -> paragraph(b, setup.left, setup.contentWidth)
            is DImage -> image(b, setup.left, setup.contentWidth)
            is DTable -> runCatching { table(b) }
        }
    }

    private fun layoutOf(p: DPara, width: Float): StaticLayout = TextLayouts.layout(
        listOf(LPara(p.runs, p.align, p.prefix, p.indentLeft + p.firstLine, p.indentLeft, p.emptySize)),
        width - p.indentRight, p.lineSpacing, justify = p.align == TAlign.JUSTIFY,
    )

    private fun paragraph(p: DPara, x: Float, width: Float) {
        if (y > top) y += p.spaceBefore
        val layout = layoutOf(p, width)
        var from = 0
        while (from < layout.lineCount) {
            val avail = bottom - y
            var to = from
            while (to < layout.lineCount && layout.getLineBottom(to) - layout.getLineTop(from) <= avail) to++
            if (to == from) {
                if (y > top) { newPage(); continue }
                to = from + 1 // a line taller than the page: draw it anyway (clipped by the page)
            }
            Draw.lines(canvas, layout, x, y, from, to)
            y += layout.getLineBottom(to - 1) - layout.getLineTop(from)
            from = to
            if (from < layout.lineCount) newPage()
        }
        y += p.spaceAfter
    }

    private fun fit(img: DImage, maxW: Float, maxH: Float): Pair<Float, Float> {
        val s = minOf(1f, maxW / img.width, maxH / img.height)
        return img.width * s to img.height * s
    }

    private fun image(img: DImage, x: Float, width: Float) {
        val (w, h) = fit(img, width, setup.contentHeight)
        if (y + h > bottom && y > top) newPage()
        val left = when (img.align) {
            TAlign.CENTER -> x + (width - w) / 2
            TAlign.RIGHT -> x + width - w
            else -> x
        }
        Draw.image(canvas, images.load(img.media, w, h), RectF(left, y, left + w, y + h))
        y += h
    }

    private sealed interface CellItem { val height: Float }
    private class CellText(val layout: StaticLayout, val x: Float, val before: Float, val after: Float) : CellItem {
        override val height get() = before + layout.height + after
    }
    private class CellImage(val img: DImage, val w: Float, val h: Float, val x: Float) : CellItem { override val height get() = h }

    private fun cellItems(blocks: List<DBlock>, width: Float): List<CellItem> = blocks.flatMap { b ->
        when (b) {
            is DPara -> listOf(CellText(layoutOf(b, width), 0f, b.spaceBefore.coerceAtMost(6f), b.spaceAfter.coerceAtMost(6f)))
            is DImage -> { val (w, h) = fit(b, width, setup.contentHeight / 2); listOf(CellImage(b, w, h, 0f)) }
            // Nested tables: their cells' content one after another (simplified).
            is DTable -> b.rows.flatMap { r -> r.cells.flatMap { cellItems(it.blocks, width) } }
            else -> emptyList()
        }
    }

    private fun table(t: DTable) {
        val xs = FloatArray(t.cols.size + 1)
        for (i in t.cols.indices) xs[i + 1] = xs[i] + t.cols[i]
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.5f }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val padX = 5.4f
        val padY = 2f
        for (row in t.rows) {
            var col = 0
            val cells = row.cells.map { cell ->
                val span = cell.span.coerceAtLeast(1)
                val x0 = xs[min(col, t.cols.size)]
                val x1 = xs[min(col + span, t.cols.size)]
                col += span
                Triple(cell, x0 to x1, cellItems(cell.blocks, (x1 - x0 - 2 * padX).coerceAtLeast(10f)))
            }
            val content = cells.maxOfOrNull { (_, _, items) -> items.sumOf { it.height.toDouble() }.toFloat() } ?: 0f
            val rowH = max(row.minHeight, content + 2 * padY).coerceAtMost(setup.contentHeight)
            if (y + rowH > bottom && y > top) newPage()
            for ((cell, xr, items) in cells) {
                val r = RectF(setup.left + xr.first, y, setup.left + xr.second, y + rowH)
                if (r.width() <= 0) continue
                cell.fill?.let { fill.color = it; canvas.drawRect(r, fill) }
                canvas.save()
                canvas.clipRect(r)
                var cy = r.top + padY
                for (item in items) {
                    when (item) {
                        is CellText -> {
                            cy += item.before
                            canvas.save(); canvas.translate(r.left + padX, cy); item.layout.draw(canvas); canvas.restore()
                            cy += item.layout.height + item.after
                        }
                        is CellImage -> {
                            Draw.image(canvas, images.load(item.img.media, item.w, item.h), RectF(r.left + padX, cy, r.left + padX + item.w, cy + item.h))
                            cy += item.h
                        }
                    }
                }
                canvas.restore()
                // Merged-down cells: no line between them and the cell above.
                if (cell.mergedContinue) {
                    canvas.drawLine(r.left, r.top, r.left, r.bottom, line)
                    canvas.drawLine(r.right, r.top, r.right, r.bottom, line)
                    canvas.drawLine(r.left, r.bottom, r.right, r.bottom, line)
                } else canvas.drawRect(r, line)
            }
            y += rowH
        }
        y += 4f
    }
}
