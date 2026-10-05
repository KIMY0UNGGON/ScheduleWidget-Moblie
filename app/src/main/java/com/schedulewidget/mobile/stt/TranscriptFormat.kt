package com.schedulewidget.mobile.stt

// Plain-text presentation of the stored transcript.

object TranscriptFormat {
    private const val PARAGRAPH_GAP_MS = 4_000L
    private const val PARAGRAPH_CHARS = 600

    fun clock(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    }

    /**
     * "[HH:MM:SS] 화자 1: text" paragraphs separated by blank lines. Consecutive segments of the same speaker
     * are joined; a long pause or a very long paragraph also starts a new one (the label repeats).
     */
    fun asText(segments: List<TranscriptSegment>, speakerNames: Map<Int, String> = emptyMap()): String {
        val out = StringBuilder()
        var current: StringBuilder? = null
        var speaker: Int? = null
        var lastEnd = 0L
        for (seg in segments) {
            val text = seg.text.trim()
            if (text.isEmpty()) continue
            val cur = current
            val sameParagraph = cur != null && seg.speaker == speaker &&
                seg.startMs - lastEnd < PARAGRAPH_GAP_MS && cur.length < PARAGRAPH_CHARS
            if (sameParagraph) {
                cur!!.append(' ').append(text)
            } else {
                if (cur != null) out.append(cur).append("\n\n")
                val label = seg.speaker?.let { s -> (speakerNames[s]?.trim()?.takeIf { it.isNotEmpty() } ?: "화자 ${s + 1}") + ": " } ?: ""
                current = StringBuilder("[${clock(seg.startMs)}] ").append(label).append(text)
                speaker = seg.speaker
            }
            lastEnd = seg.endMs
        }
        current?.let { out.append(it) }
        return out.toString()
    }
}
