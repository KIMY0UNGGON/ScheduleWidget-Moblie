package com.schedulewidget.mobile.stt

import java.io.File
import java.util.Base64

/**
 * Workaround for a sherpa-onnx 1.13.8 Whisper bug: the C++ side runs RemoveInvalidUtf8Sequences on every
 * token separately, so byte-level BPE pieces holding part of a multi-byte character (most Hangul syllables)
 * are dropped ("데이터베이스" -> "데이터이스"). We hand it a tokens file whose entries are re-encoded with
 * GPT-2's bytes_to_unicode table (always valid UTF-8; printable ASCII unchanged so "<|ko|>" etc. still
 * match), then map the recognized text back to bytes and decode it as UTF-8.
 */
internal object WhisperByteTokens {
    private val byteToChar: CharArray
    private val charToByte = HashMap<Char, Byte>(512)

    init {
        val bs = ArrayList<Int>().apply { addAll(33..126); addAll(161..172); addAll(174..255) }
        val cs = ArrayList(bs)
        var n = 0
        for (b in 0..255) if (b !in bs) { bs += b; cs += 256 + n; n++ }
        byteToChar = CharArray(256)
        for (i in bs.indices) {
            byteToChar[bs[i]] = cs[i].toChar()
            charToByte[cs[i].toChar()] = bs[i].toByte()
        }
    }

    /** The "-b2u" companion of [tokens] (e.g. small-tokens.txt -> small-tokens-b2u.txt), written once. */
    fun fixedTokens(tokens: File): File {
        val out = File(tokens.parentFile, tokens.nameWithoutExtension + "-b2u.txt")
        if (out.isFile && out.length() > 0 && out.lastModified() >= tokens.lastModified()) return out
        val tmp = File(out.path + ".tmp")
        tmp.bufferedWriter(Charsets.UTF_8).use { w ->
            tokens.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val parts = line.trimEnd('\r').split(' ')
                    if (parts.size != 2) { w.write(line); w.write("\n"); continue }
                    w.write(encodeToken(parts[0]))
                    w.write(" ")
                    w.write(parts[1])
                    w.write("\n")
                }
            }
        }
        if (!tmp.renameTo(out)) throw java.io.IOException("could not write ${out.name}")
        return out
    }

    /** base64(raw bytes) -> base64(UTF-8 of bytes_to_unicode(raw bytes)). */
    fun encodeToken(base64: String): String {
        val raw = Base64.getDecoder().decode(base64)
        val mapped = String(CharArray(raw.size) { byteToChar[raw[it].toInt() and 0xFF] })
        return Base64.getEncoder().encodeToString(mapped.toByteArray(Charsets.UTF_8))
    }

    /** Inverse of the token mapping on recognized text; characters outside the table are dropped. */
    fun decodeText(text: String): String {
        val bytes = java.io.ByteArrayOutputStream(text.length)
        for (c in text) charToByte[c]?.let { bytes.write(it.toInt()) }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
