package com.schedulewidget.mobile.stt

// Pure speech-span planning for recognition and bounded diarization windows.

/** A half-open sample range [start, end). */
data class Span(val start: Long, val end: Long) {
    val length: Long get() = end - start
}

object SegmentPlanner {
    /**
     * Turns raw VAD speech spans (sorted, in samples) into recognizer inputs: pads each side by [pad]
     * (never across the middle of a gap), merges spans separated by at most [mergeGap] while the result
     * stays within [maxLen], splits anything longer than [maxLen], and drops spans shorter than [minLen].
     * [total] clamps the end. With [quietest] (returns the quietest sample position in a range) long spans are
     * cut at the quietest point in the last 40% before the limit; without it they are cut into equal pieces.
     */
    fun plan(
        raw: List<Span>, total: Long, maxLen: Long, mergeGap: Long, pad: Long, minLen: Long,
        quietest: ((from: Long, to: Long) -> Long)? = null,
    ): List<Span> {
        val sorted = raw.filter { it.end > it.start }.sortedBy { it.start }
        if (sorted.isEmpty()) return emptyList()
        // Pad, but at most half of the gap to each neighbour.
        val padded = sorted.mapIndexed { i, s ->
            val prevEnd = if (i > 0) sorted[i - 1].end else 0L
            val nextStart = if (i + 1 < sorted.size) sorted[i + 1].start else total
            val left = minOf(pad, maxOf(0L, (s.start - prevEnd) / 2))
            val right = minOf(pad, maxOf(0L, (nextStart - s.end) / 2))
            Span(maxOf(0L, s.start - left), minOf(total, s.end + right))
        }
        val merged = ArrayList<Span>()
        for (s in padded) {
            val last = merged.lastOrNull()
            if (last != null && s.start - last.end <= mergeGap && s.end - last.start <= maxLen) {
                merged[merged.size - 1] = Span(last.start, maxOf(last.end, s.end))
            } else {
                merged += s
            }
        }
        val out = ArrayList<Span>()
        for (s in merged) {
            if (s.length <= maxLen) { out += s; continue }
            if (quietest != null) {
                var start = s.start
                while (s.end - start > maxLen) {
                    val lo = start + maxLen * 6 / 10
                    val hi = start + maxLen
                    val cut = quietest(lo, hi).coerceIn(lo, hi)
                    out += Span(start, cut)
                    start = cut
                }
                out += Span(start, s.end)
                continue
            }
            val pieces = ((s.length + maxLen - 1) / maxLen).toInt()
            val step = s.length / pieces
            for (p in 0 until pieces) {
                val a = s.start + p * step
                val b = if (p == pieces - 1) s.end else a + step
                out += Span(a, b)
            }
        }
        return out.filter { it.length >= minLen }
    }

    /**
     * Cuts [0, total) into windows of about [target] samples for diarization, moving each cut into the
     * middle of the widest silence (gap between [speech] spans) within +-[slack] of the ideal position.
     */
    fun windows(speech: List<Span>, total: Long, target: Long, slack: Long): List<Span> {
        if (total <= target + slack) return listOf(Span(0, total))
        val gaps = ArrayList<Span>()
        var prev = 0L
        for (s in speech.sortedBy { it.start }) {
            if (s.start > prev) gaps += Span(prev, s.start)
            prev = maxOf(prev, s.end)
        }
        if (prev < total) gaps += Span(prev, total)
        val cuts = ArrayList<Long>()
        var start = 0L
        while (total - start > target + slack) {
            val ideal = start + target
            val best = gaps.filter { g -> (g.start + g.end) / 2 in (ideal - slack)..(ideal + slack) }.maxByOrNull { it.length }
            val cut = best?.let { (it.start + it.end) / 2 } ?: ideal
            cuts += cut
            start = cut
        }
        val bounds = listOf(0L) + cuts + total
        return bounds.zipWithNext { a, b -> Span(a, b) }
    }
}
