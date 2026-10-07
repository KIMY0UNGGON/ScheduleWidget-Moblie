package com.schedulewidget.mobile.notes.importer

import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.notes.ink.Stroke
import com.schedulewidget.mobile.notes.ink.Tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Flexcil handwriting (objects/<pageKey>.drawings of a .flx document) converted to our [Stroke]s. Pure JVM.
 *
 * Observed format (a real .flx, and the open-source viewers github.com/c0lbarator/FWebViewer and
 * github.com/janptn/flexcil-backup-viewer): a JSON array of objects
 *   {"type":1, "mode":5, "figure":0, "points":"<base64>", "start":{"x":..,"y":..}, "strokeColor":<ARGB uint32>,
 *    "scale":{"x":1,"y":1}, "rotate":0.0, "key":"<uuid>", ...}
 * "points" = uint32 LE point count, then per point 3 float32 LE: dx, dy, width. dx/dy are offsets from "start" (the
 * top-left of the stroke's bounding box); all of x, y and width are normalized to the page (0..1 of its width/height;
 * width relative to the page width). Older files are said to use an 8-byte header instead of the count.
 * Shapes (.shapes) use the same point encoding but mean geometry per shapeType (ellipse corners, curve control
 * points...), so they are not converted.
 */
object FlexcilInk {
    /** A stroke in normalized page units: [pts] = x, y, width triples (width NaN = unknown). */
    class NormStroke(val color: Int, val highlighter: Boolean, val pts: FloatArray) {
        val count: Int get() = pts.size / 3
    }

    class Parsed(val strokes: List<NormStroke>, val unreadable: Int)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Decodes a "points" value into dx, dy, width triples; null when the layout is not one we know. */
    fun decodePoints(base64: String): FloatArray? {
        val bytes = try {
            Base64.getDecoder().decode(base64.trim().replace("\\u003d", "="))
        } catch (e: IllegalArgumentException) {
            return null
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val offset = when {
            bytes.size >= 16 && (bytes.size - 4) % 12 == 0 &&
                buf.getInt(0).let { it == 0 || it == (bytes.size - 4) / 12 } -> 4
            bytes.size >= 20 && (bytes.size - 8) % 12 == 0 -> 8
            else -> return null
        }
        val n = (bytes.size - offset) / 12
        val out = FloatArray(n * 3)
        for (i in 0 until n) {
            val o = offset + i * 12
            out[i * 3] = buf.getFloat(o)
            out[i * 3 + 1] = buf.getFloat(o + 4)
            out[i * 3 + 2] = buf.getFloat(o + 8)
        }
        return out
    }

    /** Parses a .drawings file. Entries we cannot place on the page count as [Parsed.unreadable]. */
    fun parseDrawings(text: String): Parsed = parseDrawings(text) {}

    internal fun parseDrawings(text: String, checkActive: () -> Unit): Parsed {
        if (!hasSafeJsonStructure(text)) return Parsed(emptyList(), 1)
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return Parsed(emptyList(), 1)
        val strokes = ArrayList<NormStroke>()
        var bad = 0
        for (el in root) {
            checkActive()
            val o = el as? JsonObject ?: continue
            val type = o.num("type")
            if (type != null && type.toInt() !in listOf(1, 2)) { bad++; continue }
            val s = parseStroke(o)
            if (s == null) bad++ else strokes += s
        }
        return Parsed(strokes, bad)
    }

    private fun parseStroke(o: JsonObject): NormStroke? {
        val raw = (o["points"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val start = o["start"] as? JsonObject ?: return null
        val sx = start.num("x")?.toFloat() ?: return null
        val sy = start.num("y")?.toFloat() ?: return null
        val d = decodePoints(raw) ?: return null
        if (d.isEmpty()) return null
        val scale = o["scale"] as? JsonObject
        val kx = scale?.num("x")?.toFloat()?.takeIf { it.isFinite() && it > 0f } ?: 1f
        val ky = scale?.num("y")?.toFloat()?.takeIf { it.isFinite() && it > 0f } ?: kx
        val n = d.size / 3
        val pts = FloatArray(n * 3)
        for (i in 0 until n) {
            val dx = d[i * 3]; val dy = d[i * 3 + 1]; val w = d[i * 3 + 2]
            if (!dx.isFinite() || !dy.isFinite()) return null
            pts[i * 3] = sx + dx * kx
            pts[i * 3 + 1] = sy + dy * ky
            pts[i * 3 + 2] = if (w.isFinite() && w > 0f && w < 0.1f) w * (kx + ky) / 2f else Float.NaN
        }
        val angle = o.num("rotate")?.toFloat() ?: 0f
        if (angle.isFinite() && angle != 0f) rotate(pts, angle)
        // Something far off the page means we misread the layout: better no stroke than a wild one.
        for (i in 0 until n) {
            val x = pts[i * 3]; val y = pts[i * 3 + 1]
            if (x < -4f || x > 4f || y < -4f || y > 4f) return null
        }
        val argb = o.num("strokeColor")?.toLong() ?: 0xFF000000L
        val color = argb.toInt()
        val alpha = (color ushr 24) and 0xFF
        // Flexcil's highlighter stores a translucent colour (our highlighter applies its own translucency).
        val highlighter = alpha in 1..0xEF
        val opaque = if (highlighter || alpha == 0) color or (0xFF shl 24) else color
        return NormStroke(opaque, highlighter, pts)
    }

    /** Rotates the points by [radians] around the centre of their bounding box. */
    private fun rotate(pts: FloatArray, radians: Float) {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until pts.size / 3) {
            l = minOf(l, pts[i * 3]); r = maxOf(r, pts[i * 3])
            t = minOf(t, pts[i * 3 + 1]); b = maxOf(b, pts[i * 3 + 1])
        }
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        val c = cos(radians); val s = sin(radians)
        for (i in 0 until pts.size / 3) {
            val x = pts[i * 3] - cx; val y = pts[i * 3 + 1] - cy
            pts[i * 3] = cx + x * c - y * s
            pts[i * 3 + 1] = cy + x * s + y * c
        }
    }

    /**
     * Our page ink for strokes placed on a [pageW] × [pageH] point page. Flexcil's per-point width becomes our nominal
     * width (the widest point) plus a pressure that reproduces each point's width through InkGeometry.pressureWidth.
     */
    fun toPageInk(strokes: List<NormStroke>, pageW: Float, pageH: Float, firstId: Long): PageInk {
        var id = firstId
        val out = strokes.mapNotNull { s ->
            if (s.count == 0) return@mapNotNull null
            var maxW = 0f
            for (i in 0 until s.count) { val w = s.pts[i * 3 + 2]; if (w.isFinite()) maxW = max(maxW, w) }
            val width = if (maxW > 0f) (maxW * pageW).coerceIn(0.3f, 40f) else DEFAULT_WIDTH_PT
            val pts = FloatArray(s.count * 3)
            for (i in 0 until s.count) {
                pts[i * 3] = s.pts[i * 3] * pageW
                pts[i * 3 + 1] = s.pts[i * 3 + 1] * pageH
                val w = s.pts[i * 3 + 2]
                pts[i * 3 + 2] = if (s.highlighter || !w.isFinite() || maxW <= 0f) 1f else pressureFor(w / maxW)
            }
            Stroke(id++, if (s.highlighter) Tool.HIGHLIGHTER else Tool.PEN, s.color, width, pts)
        }
        return PageInk(strokes = out)
    }

    /** Inverse of InkGeometry.pressureWidth's factor 0.35 + 0.65 * p^0.8. */
    internal fun pressureFor(ratio: Float): Float {
        val r = ratio.coerceIn(0f, 1f)
        if (r <= 0.35f) return 0f
        return ((r - 0.35f) / 0.65f).toDouble().pow(1.0 / 0.8).toFloat().coerceIn(0f, 1f)
    }

    /** Flexcil's default pen is about 2 px on a 768 px wide page. */
    private const val DEFAULT_WIDTH_PT = 1.6f

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.longOrNull?.toDouble() }
}
