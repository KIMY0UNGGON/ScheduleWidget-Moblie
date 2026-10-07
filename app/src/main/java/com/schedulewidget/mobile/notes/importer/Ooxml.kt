package com.schedulewidget.mobile.notes.importer

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.IOException
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import org.xml.sax.ext.DefaultHandler2
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory

// Shared pieces of the offline PPTX/DOCX readers: the package (zip parts + relationships), a few DOM helpers and the
// DrawingML/WordprocessingML unit and colour conventions. Pure JVM (javax.xml DOM), so it is unit-testable.

/** Unit conversions used by Office files. Everything we draw is in PDF points (1/72 inch). */
object Units {
    const val EMU_PER_PT = 12700f
    const val EMU_PER_INCH = 914400f
    fun emuToPt(emu: Long): Float = emu / EMU_PER_PT
    /** Word's twentieths of a point (page size, margins, indents, spacing). */
    fun twipToPt(twip: Int): Float = twip / 20f
    /** Word's font sizes are half-points (w:sz="24" = 12 pt). */
    fun halfPtToPt(halfPt: Int): Float = halfPt / 2f
    /** DrawingML font sizes are hundredths of a point (a:rPr sz="1800" = 18 pt). */
    fun centiPtToPt(centi: Int): Float = centi / 100f
}

/** Read access to the parts of an OOXML package. */
interface PartSource {
    fun read(path: String): ByteArray?
}

/** Parts of an on-disk .pptx/.docx (random access, so big media parts are only read when drawn). */
class ZipPartSource(file: File) : PartSource, Closeable {
    private val zip = ZipFile(file)
    // Some writers use different case or a leading slash; look entries up case-insensitively.
    private val names: Map<String, String> = try {
        val entries = zip.entries().asSequence().take(Ooxml.MAX_ZIP_ENTRIES + 1).map { it.name }.toList()
        if (entries.size > Ooxml.MAX_ZIP_ENTRIES) throw IOException("ZIP에 항목이 너무 많아요")
        entries.associateBy { it.trimStart('/').lowercase() }
    } catch (e: Exception) {
        zip.close()
        throw e
    }

    fun has(path: String) = names.containsKey(path.trimStart('/').lowercase())

    override fun read(path: String): ByteArray? {
        val name = names[path.trimStart('/').lowercase()] ?: return null
        val entry = zip.getEntry(name) ?: return null
        val limit = if (name.endsWith(".xml", true) || name.endsWith(".rels", true)) Ooxml.MAX_XML_BYTES.toLong() else MAX_PART
        if (entry.size > limit) return null
        return zip.getInputStream(entry).use { readLimited(it, limit) }
    }

    override fun close() = zip.close()

    companion object { private const val MAX_PART = 200L * 1024 * 1024 }
}

internal fun readLimited(input: InputStream, limit: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return out.toByteArray()
        total += n
        if (total > limit) return null
        out.write(buffer, 0, n)
    }
}

/** In-memory parts (tests). */
class MapPartSource(private val parts: Map<String, ByteArray>) : PartSource {
    override fun read(path: String): ByteArray? = parts[path.trimStart('/')]
}

object Ooxml {
    const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    internal const val MAX_ZIP_ENTRIES = 100_000
    internal const val MAX_XML_BYTES = 16 * 1024 * 1024
    internal const val MAX_XML_DEPTH = 256
    internal const val MAX_XML_NODES = 100_000

    fun parse(bytes: ByteArray): Element {
        validateXml(bytes)
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // Android's DOM factory does not support this feature; validateXml rejects DTDs before the DOM parser.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.isExpandEntityReferences = false }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("외부 XML 엔터티는 허용되지 않아요") }
        }
        return builder.parse(ByteArrayInputStream(bytes)).documentElement
    }

    private fun validateXml(bytes: ByteArray) {
        if (bytes.size > MAX_XML_BYTES) throw IOException("XML 항목이 너무 커요")

        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val reader = factory.newSAXParser().xmlReader
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> throw SAXException("외부 XML 엔터티는 허용되지 않아요") }
        var depth = 0
        var nodes = 0
        fun countNode() {
            if (++nodes > MAX_XML_NODES) throw SAXException("XML 노드가 너무 많아요")
        }
        reader.contentHandler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                if (++depth > MAX_XML_DEPTH) throw SAXException("XML 중첩이 너무 깊어요")
                nodes += (attributes?.length ?: 0) + 1
                if (nodes > MAX_XML_NODES) throw SAXException("XML 노드가 너무 많아요")
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) { depth-- }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (length > 0) countNode() }
            override fun processingInstruction(target: String?, data: String?) { countNode() }
        }
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", object : DefaultHandler2() {
            override fun startDTD(name: String?, publicId: String?, systemId: String?) {
                throw SAXException("DOCTYPE은 허용되지 않아요")
            }

            override fun startCDATA() { countNode() }
            override fun comment(ch: CharArray, start: Int, length: Int) { countNode() }
        })
        reader.parse(InputSource(ByteArrayInputStream(bytes)))
    }

    fun parsePart(src: PartSource, path: String): Element? = src.read(path)?.let { runCatching { parse(it) }.getOrNull() }

    /** "ppt/slides/slide1.xml" -> "ppt/slides/_rels/slide1.xml.rels". */
    fun relsPathOf(part: String): String {
        val slash = part.lastIndexOf('/')
        return if (slash < 0) "_rels/$part.rels" else part.substring(0, slash) + "/_rels/" + part.substring(slash + 1) + ".rels"
    }

    /** Resolves a relationship target against the folder of [part] ("../media/a.png", "/word/media/a.png"). */
    fun resolve(part: String, target: String): String {
        if (target.startsWith("/")) return target.trimStart('/')
        val base = part.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
        for (seg in target.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (base.isNotEmpty()) base.removeAt(base.size - 1)
                else -> base += seg
            }
        }
        return base.joinToString("/")
    }

    data class Rel(val id: String, val type: String, val target: String, val external: Boolean)

    /** Relationships of [part], targets resolved to package paths. */
    fun rels(src: PartSource, part: String): Map<String, Rel> {
        val root = parsePart(src, relsPathOf(part)) ?: return emptyMap()
        return root.children("Relationship").associate { r ->
            val external = r.attr("TargetMode").equals("External", ignoreCase = true)
            val target = r.attr("Target").orEmpty()
            val id = r.attr("Id").orEmpty()
            id to Rel(id, r.attr("Type").orEmpty(), if (external) target else resolve(part, target), external)
        }
    }
}

// ---- DOM helpers (by local name, so prefixes don't matter) ----

val Node.local: String get() = localName ?: nodeName.substringAfter(':')

fun Element.kids(): List<Element> {
    val out = ArrayList<Element>()
    var n = firstChild
    while (n != null) {
        if (n is Element) out += n
        n = n.nextSibling
    }
    return out
}

fun Element.child(name: String): Element? {
    var n = firstChild
    while (n != null) {
        if (n is Element && n.local == name) return n
        n = n.nextSibling
    }
    return null
}

fun Element.children(name: String): List<Element> = kids().filter { it.local == name }

/** Nested child by successive local names. */
fun Element.path(vararg names: String): Element? {
    var e: Element = this
    for (n in names) e = e.child(n) ?: return null
    return e
}

/** All descendants with the local name, document order. */
fun Element.descendants(name: String): List<Element> {
    val out = ArrayList<Element>()
    fun walk(e: Element) {
        for (k in e.kids()) {
            if (k.local == name) out += k
            walk(k)
        }
    }
    walk(this)
    return out
}

/** Attribute by local name, ignoring the relationships namespace (r:id is read with [rAttr]). */
fun Element.attr(name: String): String? {
    val attrs = attributes ?: return null
    for (i in 0 until attrs.length) {
        val a = attrs.item(i)
        if (a.local == name && a.namespaceURI != Ooxml.NS_R) return a.nodeValue
    }
    return null
}

/** r:id / r:embed / r:link. */
fun Element.rAttr(name: String): String? = getAttributeNS(Ooxml.NS_R, name).takeIf { it.isNotEmpty() }

fun Element.intAttr(name: String): Int? = attr(name)?.trim()?.toIntOrNull()
fun Element.longAttr(name: String): Long? = attr(name)?.trim()?.toLongOrNull()

/** OOXML booleans: "1"/"true"/"on" (and absent value = true for w:b-style toggles). */
fun parseOnOff(value: String?, absent: Boolean = true): Boolean = when (value?.trim()?.lowercase()) {
    null, "" -> absent
    "0", "false", "off", "none" -> false
    else -> true
}

/** mc:AlternateContent: the Choice (modern markup we understand best), else the Fallback. */
fun Element.alternateContentBody(): List<Element> {
    val choice = child("Choice")?.kids().orEmpty()
    return choice.ifEmpty { child("Fallback")?.kids().orEmpty() }
}

// ---- Colours ----

object Colors {
    fun rgb(r: Int, g: Int, b: Int, a: Int = 255): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** "1F4E79" -> opaque ARGB; null for "auto" or garbage. */
    fun hex(value: String?): Int? {
        val v = value?.trim()?.removePrefix("#") ?: return null
        if (v.length != 6) return null
        val n = v.toLongOrNull(16) ?: return null
        return (0xFF000000L or n).toInt()
    }

    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun alpha(c: Int) = (c ushr 24) and 0xFF

    /** DrawingML colour transforms in their usual order of effect (values are 1/1000 percent). */
    fun transform(color: Int, mods: List<Element>): Int {
        var c = color
        for (m in mods) {
            val v = (m.intAttr("val") ?: continue) / 100000f
            c = when (m.local) {
                "lumMod" -> hsl(c) { h, s, l -> Triple(h, s, l * v) }
                "lumOff" -> hsl(c) { h, s, l -> Triple(h, s, l + v) }
                "satMod" -> hsl(c) { h, s, l -> Triple(h, s * v, l) }
                "tint" -> rgb(
                    (red(c) + (255 - red(c)) * (1 - v)).toInt(), (green(c) + (255 - green(c)) * (1 - v)).toInt(),
                    (blue(c) + (255 - blue(c)) * (1 - v)).toInt(), alpha(c),
                )
                "shade" -> rgb((red(c) * v).toInt(), (green(c) * v).toInt(), (blue(c) * v).toInt(), alpha(c))
                "alpha" -> (c and 0x00FFFFFF) or ((v * 255).toInt().coerceIn(0, 255) shl 24)
                else -> c
            }
        }
        return c
    }

    private inline fun hsl(c: Int, f: (Float, Float, Float) -> Triple<Float, Float, Float>): Int {
        val r = red(c) / 255f; val g = green(c) / 255f; val b = blue(c) / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        val l = (max + min) / 2
        var h = 0f; var s = 0f
        if (max != min) {
            val d = max - min
            s = if (l > 0.5f) d / (2 - max - min) else d / (max + min)
            h = when (max) {
                r -> (g - b) / d + (if (g < b) 6 else 0)
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            } / 6
        }
        val (h2, s2, l2) = f(h, s, l)
        return fromHsl(h2, s2.coerceIn(0f, 1f), l2.coerceIn(0f, 1f), alpha(c))
    }

    private fun fromHsl(h: Float, s: Float, l: Float, a: Int): Int {
        if (s == 0f) { val v = (l * 255).toInt(); return rgb(v, v, v, a) }
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(t0: Float): Float {
            var t = t0
            if (t < 0) t += 1; if (t > 1) t -= 1
            return when {
                t < 1f / 6 -> p + (q - p) * 6 * t
                t < 1f / 2 -> q
                t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
                else -> p
            }
        }
        return rgb((hue(h + 1f / 3) * 255).toInt(), (hue(h) * 255).toInt(), (hue(h - 1f / 3) * 255).toInt(), a)
    }
}
