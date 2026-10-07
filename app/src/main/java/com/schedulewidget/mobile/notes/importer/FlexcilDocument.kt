package com.schedulewidget.mobile.notes.importer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException

/** A native Flexcil notebook. Files live only during the restore callback; original is a lossless .flx copy. */
class FlexcilDocument(
    val title: String,
    val folder: String?,
    val pdfs: Map<String, File>,
    val pages: List<Page>?,
    val original: File? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    /** Original Flexcil `info.key`, used to join recording metadata without relying on a title or path. */
    val sourceKey: String? = null,
) {
    class Page(
        val key: String?, val pdfKey: String?, val pdfIndex: Int, val rotation: Int,
        val width: Float?, val height: Float?, val strokes: List<FlexcilInk.NormStroke>,
    )

    companion object {
        internal fun readPage(page: JsonObject, ink: (String) -> List<FlexcilInk.NormStroke>): Page {
            val key = page.string("key")
            val attachment = page["attachmentPage"] as? JsonObject
            val file = attachment?.string("file")?.substringBeforeLast('.')?.uppercase()
            val index = attachment?.number("index")?.toInt() ?: -1
            if (attachment != null && (file == null || index < 0)) throw IOException("PDF 페이지 참조를 읽을 수 없어요")
            val angle = page.number("rotate") ?: 0.0
            val rotation = ((angle.toInt() % 360) + 360) % 360
            if (rotation !in listOf(0, 90, 180, 270)) throw IOException("지원하지 않는 페이지 회전이에요")
            val frame = page["frame"] as? JsonObject
            fun dimension(name: String): Float? = frame?.number(name)?.toFloat()?.takeIf { it.isFinite() && it > 0f && it <= 20000f }
            return Page(key, file, index, rotation, dimension("width"), dimension("height"), key?.let(ink).orEmpty())
        }

        internal fun timestamp(info: JsonObject?, key: String): Long? = info?.number(key)?.takeIf {
            it.isFinite() && it > 0 && it < Long.MAX_VALUE / 1000.0
        }?.let { (it * 1000).toLong() }

        private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()
    }
}
