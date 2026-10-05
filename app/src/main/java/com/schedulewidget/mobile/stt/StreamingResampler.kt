package com.schedulewidget.mobile.stt

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Pure resampling stage of the transcription pipeline.

/**
 * Streaming band-limited resampler (windowed sinc, Hann window) for arbitrary rate pairs.
 * Low-pass cutoff sits at 95% of the lower Nyquist so downsampling 44.1/48 kHz to 16 kHz does not alias.
 * Common rate pairs have few distinct fractional positions (48k->16k: 1, 44.1k->16k: 160), so the filter
 * taps are precomputed per position (polyphase) and each output is a plain dot product.
 * Feed any chunk sizes to [process]; call [flush] once at the end.
 */
class StreamingResampler(private val inRate: Int, private val outRate: Int, zeroCrossings: Int = 10) {
    private val passthrough = inRate == outRate
    // Cutoff as a fraction of the input sample rate's Nyquist.
    private val cutoff = 0.95 * min(1.0, outRate.toDouble() / inRate)
    // Kernel half width in input samples (wider when downsampling so the filter keeps its sharpness).
    private val halfWidth = zeroCrossings / cutoff
    private val reach = halfWidth.toInt() + 1
    private val taps = 2 * reach

    // Output k sits at input position k * inRate / outRate; its fractional part is a multiple of g / outRate.
    private val g = gcd(inRate, outRate)
    private val phases = outRate / g
    private val bank: Array<FloatArray>? = if (passthrough || phases > 4096) null else Array(phases) { p -> coefficients(p.toDouble() / phases) }

    // Input history; buf[0] is absolute input sample [bufStart].
    private var buf = FloatArray(8192)
    private var bufLen = 0
    private var bufStart = 0L
    private var consumed = 0L // total input samples received
    private var nextOut = 0L  // index of the next output sample

    private fun kernel(x: Double): Double {
        val d = abs(x)
        if (d >= halfWidth) return 0.0
        val sinc = if (d == 0.0) 1.0 else sin(PI * cutoff * d) / (PI * cutoff * d)
        return cutoff * sinc * (0.5 + 0.5 * cos(PI * d / halfWidth))
    }

    /** Taps for input samples center-reach+1 .. center+reach, normalised to unity gain at DC. */
    private fun coefficients(frac: Double): FloatArray {
        val c = DoubleArray(taps) { t -> kernel((t - reach + 1) - frac) }
        val sum = c.sum().takeIf { it != 0.0 } ?: 1.0
        return FloatArray(taps) { (c[it] / sum).toFloat() }
    }

    /** Appends [n] samples of [input] and returns every output sample that can be computed so far. */
    fun process(input: FloatArray, n: Int = input.size): FloatArray {
        if (passthrough) return input.copyOf(n)
        append(input, n)
        consumed += n
        return produce(final = false)
    }

    /** Emits the tail (zero-padded past the end). */
    fun flush(): FloatArray {
        if (passthrough) return FloatArray(0)
        return produce(final = true)
    }

    private fun append(input: FloatArray, n: Int) {
        if (bufLen + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + n))
        System.arraycopy(input, 0, buf, bufLen, n)
        bufLen += n
    }

    private fun produce(final: Boolean): FloatArray {
        val lastOut = if (final) (consumed * outRate + inRate - 1) / inRate else Long.MAX_VALUE
        var result = FloatArray(((bufLen.toLong() * outRate / inRate) + 4).toInt())
        var count = 0
        val b = buf
        while (nextOut < lastOut) {
            val num = nextOut * inRate
            val center = num / outRate
            if (!final && center + reach >= bufStart + bufLen) break
            val rem = num % outRate
            val coef = bank?.get((rem / g).toInt()) ?: coefficients(rem.toDouble() / outRate)
            val base = (center - reach + 1 - bufStart).toInt()
            var acc = 0f
            if (base >= 0 && base + taps <= bufLen) {
                for (t in 0 until taps) acc += b[base + t] * coef[t]
            } else {
                // Edges: the start of the stream or the zero-padded tail.
                for (t in 0 until taps) {
                    val idx = base + t
                    if (idx in 0 until bufLen) acc += b[idx] * coef[t]
                }
            }
            if (count == result.size) result = result.copyOf(result.size * 2 + 16)
            result[count++] = acc
            nextOut++
        }
        // Drop input that no future output needs.
        val keepFrom = nextOut * inRate / outRate - reach
        val drop = (keepFrom - bufStart).toInt().coerceIn(0, bufLen)
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart += drop
        }
        return result.copyOf(count)
    }

    private companion object {
        tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}
