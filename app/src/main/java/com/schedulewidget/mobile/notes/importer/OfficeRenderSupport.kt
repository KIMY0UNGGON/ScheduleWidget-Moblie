package com.schedulewidget.mobile.notes.importer

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Shared text and drawing support for the offline PPTX and DOCX renderers.
/** Paragraph input of [TextLayouts]: margins are absolute from the layout's left edge. */
internal data class LPara(
    val runs: List<TRun>, val align: TAlign, val prefix: String?, val firstMargin: Float, val restMargin: Float,
    val emptySize: Float, val prefixColor: Int? = null,
)

internal object TextLayouts {
    /** Font size as a float (AbsoluteSizeSpan only takes whole pixels). */
    private class SizeSpan(private val size: Float) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) { tp.textSize = size }
        override fun updateMeasureState(tp: TextPaint) { tp.textSize = size }
    }

    fun build(paras: List<LPara>): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val ranges = ArrayList<Triple<LPara, Int, Int>>()
        paras.forEachIndexed { i, p ->
            val start = sb.length
            val firstSize = p.runs.firstOrNull { it.text.isNotEmpty() }?.size ?: p.emptySize
            if (p.prefix != null) {
                val s = sb.length
                sb.append(p.prefix).append(Char(0xA0))
                val color = p.prefixColor ?: p.runs.firstOrNull()?.color ?: 0xFF000000.toInt()
                sb.setSpan(SizeSpan(firstSize), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(color), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (p.runs.none { it.text.isNotEmpty() }) {
                // An empty paragraph still takes a line of its own size.
                val s = sb.length
                sb.append(Char(0x200B))
                sb.setSpan(SizeSpan(p.emptySize), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            for (r in p.runs) {
                if (r.text.isEmpty()) continue
                val s = sb.length
                sb.append(r.text)
                val e = sb.length
                sb.setSpan(SizeSpan(r.size), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(r.color), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val style = (if (r.bold) Typeface.BOLD else 0) or (if (r.italic) Typeface.ITALIC else 0)
                if (style != 0) sb.setSpan(StyleSpan(style), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (r.underline) sb.setSpan(UnderlineSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (r.strike) sb.setSpan(StrikethroughSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (i < paras.size - 1) sb.append(Char(10))
            ranges += Triple(p, start, sb.length)
        }
        // Paragraph spans are set once all text is in: they would grow over text appended at their end.
        for ((p, start, end) in ranges) {
            val alignment = when (p.align) {
                TAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                TAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            sb.setSpan(AlignmentSpan.Standard(alignment), start, end, Spanned.SPAN_PARAGRAPH)
            val first = p.firstMargin.coerceAtLeast(0f).roundToInt()
            val rest = p.restMargin.coerceAtLeast(0f).roundToInt()
            if (first > 0 || rest > 0) sb.setSpan(LeadingMarginSpan.Standard(first, rest), start, end, Spanned.SPAN_PARAGRAPH)
        }
        return sb
    }

    @SuppressLint("WrongConstant") // see setJustificationMode below
    fun layout(paras: List<LPara>, width: Float, lineSpacing: Float = 1f, justify: Boolean = false, wrap: Boolean = true): StaticLayout {
        val text = build(paras)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT; textSize = 18f; color = 0xFF000000.toInt() }
        var w = max(1, width.toInt())
        if (!wrap) w = max(w, ceil(Layout.getDesiredWidth(text, paint)).toInt() + 1)
        val b = StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, lineSpacing.coerceIn(0.5f, 4f))
            .setIncludePad(false)
        // Same value as android.graphics.text.LineBreaker.JUSTIFICATION_MODE_INTER_WORD (API 29), available from API 26.
        if (justify) b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        if (Build.VERSION.SDK_INT >= 28) b.setUseLineSpacingFromFallbacks(true)
        return b.build()
    }

    fun fromTBody(body: TBody, width: Float): StaticLayout = layout(
        body.paras.map { p ->
            LPara(p.runs, p.align, p.bullet, p.marginLeft + p.indent, p.marginLeft, p.emptySize, p.bulletColor)
        },
        width, wrap = body.wrap, justify = body.paras.isNotEmpty() && body.paras.all { it.align == TAlign.JUSTIFY },
    )
}

/** Decodes package images at roughly the size they are drawn (2 px per point, capped), with a small cache. */
internal class ImageLoader(private val src: PartSource) {
    private val cache = HashMap<String, Bitmap?>()
    private var cachedBytes = 0L

    fun load(path: String?, wPt: Float, hPt: Float): Bitmap? {
        path ?: return null
        val key = "$path@${(wPt / 50).roundToInt()}x${(hPt / 50).roundToInt()}"
        if (cache.containsKey(key)) return cache[key]
        val bmp = runCatching { decode(path, wPt, hPt) }.getOrNull()
        if (cachedBytes > MAX_CACHE) { cache.clear(); cachedBytes = 0 }
        cache[key] = bmp
        cachedBytes += bmp?.byteCount?.toLong() ?: 0
        return bmp
    }

    private fun decode(path: String, wPt: Float, hPt: Float): Bitmap? {
        val bytes = src.read(path) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null // EMF/WMF/TIFF etc.
        val targetW = (wPt * 2).coerceIn(64f, MAX_PX)
        val targetH = (hPt * 2).coerceIn(64f, MAX_PX)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) sample *= 2
        while (bounds.outWidth / sample > MAX_PX * 1.5f || bounds.outHeight / sample > MAX_PX * 1.5f) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        private const val MAX_PX = 1600f
        private const val MAX_CACHE = 48L * 1024 * 1024
    }
}

internal object Draw {
    fun placeholder(c: Canvas, r: RectF, label: String) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFF1F1F1.toInt(); p.style = Paint.Style.FILL
        c.drawRect(r, p)
        p.color = 0xFF9E9E9E.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 1f
        p.pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
        c.drawRect(r, p)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF757575.toInt(); typeface = Typeface.DEFAULT; textAlign = Paint.Align.CENTER
            textSize = min(14f, max(6f, min(r.width(), r.height()) / 4))
        }
        c.drawText(label, r.centerX(), r.centerY() + tp.textSize / 3, tp)
    }

    fun image(c: Canvas, bmp: Bitmap?, r: RectF, crop: List<Float> = listOf(0f, 0f, 0f, 0f), label: String = "(그림)") {
        if (bmp == null) { placeholder(c, r, label); return }
        val w = bmp.width; val h = bmp.height
        val s = Rect(
            (w * crop[0]).roundToInt().coerceIn(0, w - 1), (h * crop[1]).roundToInt().coerceIn(0, h - 1),
            (w * (1 - crop[2])).roundToInt().coerceIn(1, w), (h * (1 - crop[3])).roundToInt().coerceIn(1, h),
        )
        if (s.width() <= 0 || s.height() <= 0) s.set(0, 0, w, h)
        c.drawBitmap(bmp, s, r, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    /** Draws [layout] lines [from, to) with their top at [y]. */
    fun lines(c: Canvas, layout: StaticLayout, x: Float, y: Float, from: Int, to: Int) {
        val top = layout.getLineTop(from).toFloat()
        val bottom = layout.getLineBottom(to - 1).toFloat()
        c.save()
        c.translate(x, y - top)
        c.clipRect(-1000f, top, layout.width + 1000f, bottom)
        layout.draw(c)
        c.restore()
    }
}

/** PPTX -> PDF, one page per slide at the slide size. */
