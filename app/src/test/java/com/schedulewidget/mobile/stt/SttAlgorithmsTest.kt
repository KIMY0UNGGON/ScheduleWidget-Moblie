package com.schedulewidget.mobile.stt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class SttAlgorithmsTest {

    private fun sine(freq: Double, rate: Int, n: Int) = FloatArray(n) { sin(2 * PI * freq * it / rate).toFloat() }

    /** Resamples in uneven chunks, like the decoder does. */
    private fun resample(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        val r = StreamingResampler(inRate, outRate)
        val parts = ArrayList<FloatArray>()
        var i = 0
        var chunk = 1000
        while (i < input.size) {
            val n = minOf(chunk, input.size - i)
            parts += r.process(input.copyOfRange(i, i + n))
            i += n
            chunk = if (chunk == 1000) 4093 else 1000
        }
        parts += r.flush()
        val out = FloatArray(parts.sumOf { it.size })
        var p = 0
        for (a in parts) { a.copyInto(out, p); p += a.size }
        return out
    }

    private fun rms(a: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += a[i] * a[i]
        return sqrt(s / (to - from))
    }

    @Test
    fun resamplerKeepsLengthAndInBandTone() {
        for (inRate in listOf(48_000, 44_100, 8_000)) {
            val input = sine(440.0, inRate, inRate * 2) // 2 s
            val out = resample(input, inRate, 16_000)
            assertTrue("length for $inRate: ${out.size}", abs(out.size - 32_000) <= 2)
            // Steady-state amplitude of a 440 Hz tone is preserved (RMS of a unit sine = 0.707).
            assertEquals(0.7071, rms(out, 4_000, 28_000), 0.01)
            // And it is still the same tone: compare with an ideal 440 Hz sine at 16 kHz.
            val expected = sine(440.0, 16_000, out.size)
            var err = 0.0
            for (i in 4_000 until 28_000) err += (out[i] - expected[i]).let { it * it }
            assertTrue("phase/frequency error for $inRate", sqrt(err / 24_000) < 0.02)
        }
    }

    @Test
    fun resamplerRejectsAboveNyquist() {
        // 10 kHz cannot exist at 16 kHz; it must be filtered, not aliased to 6 kHz.
        val out = resample(sine(10_000.0, 48_000, 96_000), 48_000, 16_000)
        assertTrue("alias rms ${rms(out, 4_000, 28_000)}", rms(out, 4_000, 28_000) < 0.01)
    }

    @Test
    fun resamplerPassthroughAt16k() {
        val input = sine(300.0, 16_000, 5_000)
        assertArrayEquals(input, resample(input, 16_000, 16_000), 0f)
    }

    private val sr = 16_000L

    @Test
    fun planMergesSmallGapsAndPads() {
        val raw = listOf(Span(1 * sr, 2 * sr), Span(2 * sr + 4000, 3 * sr), Span(10 * sr, 11 * sr))
        val plan = SegmentPlanner.plan(raw, total = 20 * sr, maxLen = 25 * sr, mergeGap = sr * 6 / 10, pad = sr / 4, minLen = sr * 3 / 10)
        assertEquals(2, plan.size)
        // First two merged (0.25 s gap); padded by 0.25 s on the outer sides.
        assertEquals(Span(sr - sr / 4, 3 * sr + sr / 4), plan[0])
        assertEquals(Span(10 * sr - sr / 4, 11 * sr + sr / 4), plan[1])
    }

    @Test
    fun planSplitsLongSpansAndRespectsCap() {
        val raw = listOf(Span(0, 60 * sr))
        val plan = SegmentPlanner.plan(raw, total = 60 * sr, maxLen = 25 * sr, mergeGap = sr, pad = sr / 4, minLen = sr / 4)
        assertEquals(3, plan.size)
        assertTrue(plan.all { it.length <= 25 * sr })
        assertEquals(0L, plan.first().start)
        assertEquals(60 * sr, plan.last().end)
        plan.zipWithNext { a, b -> assertEquals(a.end, b.start) }
    }

    @Test
    fun planDoesNotMergePastCapAndDropsTinySpans() {
        val raw = listOf(Span(0, 20 * sr), Span(20 * sr + 100, 30 * sr), Span(40 * sr, 40 * sr + 1000))
        val plan = SegmentPlanner.plan(raw, total = 50 * sr, maxLen = 25 * sr, mergeGap = sr, pad = 0, minLen = sr / 4)
        assertEquals(listOf(Span(0, 20 * sr), Span(20 * sr + 100, 30 * sr)), plan)
    }

    @Test
    fun planCutsLongSpansAtQuietPoints() {
        val quiet = listOf(20 * sr, 41 * sr) // pretend these are the pauses
        val quietest = { from: Long, to: Long -> quiet.firstOrNull { it in from..to } ?: to }
        val plan = SegmentPlanner.plan(listOf(Span(0, 60 * sr)), 60 * sr, maxLen = 25 * sr, mergeGap = sr, pad = 0, minLen = sr / 4, quietest = quietest)
        assertEquals(listOf(Span(0, 20 * sr), Span(20 * sr, 41 * sr), Span(41 * sr, 60 * sr)), plan)
        // No quiet point in range: cut at the limit, never past it.
        val hard = SegmentPlanner.plan(listOf(Span(0, 60 * sr)), 60 * sr, 25 * sr, sr, 0, sr / 4) { _, to -> to + 999 }
        assertEquals(listOf(Span(0, 25 * sr), Span(25 * sr, 50 * sr), Span(50 * sr, 60 * sr)), hard)
    }

    @Test
    fun consolidateMergesTinyAndSurplusClusters() {
        val a = floatArrayOf(1f, 0f, 0f); val a2 = floatArrayOf(0.9f, 0.2f, 0f)
        val b = floatArrayOf(0f, 1f, 0f); val c = floatArrayOf(0f, 0.2f, 1f)
        val emb = listOf(a, b, a2, c)
        // Cluster 2 (a2) is 2% of the speech -> folds into the similar cluster 0.
        val labels = intArrayOf(0, 1, 2, 3)
        val secs = listOf(50.0, 30.0, 2.0, 18.0)
        assertArrayEquals(intArrayOf(0, 1, 0, 2), Speakers.consolidate(labels, emb, secs, 0.05, 0))
        // Asked for 2 speakers: the smallest remaining (c, 18 s) joins its nearest (b).
        assertArrayEquals(intArrayOf(0, 1, 0, 1), Speakers.consolidate(labels, emb, secs, 0.05, 2))
        // Already few enough and none tiny: unchanged.
        assertArrayEquals(intArrayOf(0, 1), Speakers.consolidate(intArrayOf(0, 1), listOf(a, b), listOf(10.0, 10.0), 0.05, 3))
    }

    @Test
    fun whisperTokensRoundTripHangulBytes() {
        val b64 = { s: ByteArray -> java.util.Base64.getEncoder().encodeToString(s) }
        val hangul = "데".toByteArray(Charsets.UTF_8) // 3 bytes, each alone is invalid UTF-8
        // Printable ASCII tokens stay byte-identical.
        assertEquals(b64("<|ko|>".toByteArray()), WhisperByteTokens.encodeToken(b64("<|ko|>".toByteArray())))
        // Split pieces become valid UTF-8 strings; concatenated output decodes back to the syllable.
        val pieces = listOf(hangul.copyOfRange(0, 2), hangul.copyOfRange(2, 3), " 이".toByteArray(Charsets.UTF_8))
        val text = pieces.joinToString("") { p ->
            String(java.util.Base64.getDecoder().decode(WhisperByteTokens.encodeToken(b64(p))), Charsets.UTF_8)
        }
        assertEquals("데 이", WhisperByteTokens.decodeText(text))
    }

    @Test
    fun windowsCutInsideSilence() {
        // Speech everywhere except a 4 s pause at 9:58-10:02 and one at 20:30-20:31.
        val speech = listOf(Span(0, 598 * sr), Span(602 * sr, 1230 * sr), Span(1231 * sr, 1500 * sr))
        val w = SegmentPlanner.windows(speech, total = 1500 * sr, target = 600 * sr, slack = 60 * sr)
        assertEquals(Span(0, 600 * sr), w[0])
        assertEquals(1500 * sr, w.last().end)
        w.zipWithNext { a, b -> assertEquals(a.end, b.start) }
        assertEquals(listOf(Span(0, 500 * sr)), SegmentPlanner.windows(speech, 500 * sr, 600 * sr, 60 * sr))
    }

    @Test
    fun clusterByThresholdAndByCount() {
        val a = floatArrayOf(1f, 0f, 0f); val a2 = floatArrayOf(0.95f, 0.1f, 0f)
        val b = floatArrayOf(0f, 1f, 0f); val b2 = floatArrayOf(0.1f, 0.9f, 0.1f)
        val c = floatArrayOf(0f, 0f, 1f)
        assertArrayEquals(intArrayOf(0, 1, 0, 1, 2), Speakers.cluster(listOf(a, b, a2, b2, c), 0, 0.5))
        val two = Speakers.cluster(listOf(a, b, a2, b2, c), 2, 0.5)
        assertEquals(2, two.toSet().size)
        assertEquals(two[0], two[2])
        assertEquals(two[1], two[3])
    }

    @Test
    fun assignPicksLargestOverlapThenNearestThenPrevious() {
        val turns = listOf(SpeakerTurn(0.0, 5.0, 3), SpeakerTurn(5.0, 12.0, 7), SpeakerTurn(30.0, 40.0, 3))
        val segs = listOf(0.0 to 4.0, 4.0 to 9.0, 12.5 to 13.0, 20.0 to 21.0, 31.0 to 32.0)
        val got = Speakers.assign(segs, turns)
        assertEquals(listOf(3, 7, 7, 7, 3), got)
        assertEquals(listOf(0, 1, 1, 1, 0), Speakers.renumber(got))
    }

    @Test
    fun asTextMergesSameSpeakerParagraphs() {
        val segs = listOf(
            TranscriptSegment(0, 2000, 0, "안녕하세요"),
            TranscriptSegment(2500, 4000, 0, "오늘은"),
            TranscriptSegment(4200, 6000, 1, "질문 있어요"),
            TranscriptSegment(3_725_000, 3_726_000, 0, "끝"),
        )
        assertEquals(
            "[00:00:00] 화자 1: 안녕하세요 오늘은\n\n[00:00:04] 화자 2: 질문 있어요\n\n[01:02:05] 화자 1: 끝",
            Transcript("r", "sense_voice", 0, segs).asText(),
        )
        val plain = listOf(TranscriptSegment(0, 1000, null, "가"), TranscriptSegment(1200, 2000, null, "나"), TranscriptSegment(9000, 9500, null, "다"))
        assertEquals("[00:00:00] 가 나\n\n[00:00:09] 다", Transcript("r", "m", 0, plain).asText())
    }

    @Test
    fun asTextUsesSpeakerNames() {
        val segs = listOf(TranscriptSegment(0, 1000, 0, "질문 있나요"), TranscriptSegment(9000, 9500, 1, "네", edited = true))
        assertEquals(
            "[00:00:00] 교수님: 질문 있나요\n\n[00:00:09] 화자 2: 네",
            Transcript("r", "m", 0, segs, speakerNames = mapOf(0 to "교수님", 1 to " ")).asText(),
        )
    }

    @Test
    fun correctionsLongestFirstNoCascadeSkipBlank() {
        val list = listOf(
            com.schedulewidget.mobile.data.SttCorrection("데이터", "DATA"),
            com.schedulewidget.mobile.data.SttCorrection("데이터 베이스", "데이터베이스"),
            com.schedulewidget.mobile.data.SttCorrection("", "X"),
            com.schedulewidget.mobile.data.SttCorrection("  ", "Y"),
            com.schedulewidget.mobile.data.SttCorrection("자료 구조", "자료구조"),
        )
        assertEquals("데이터베이스 수업, DATA 분석과 자료구조", SttCorrections.apply("데이터 베이스 수업, 데이터 분석과 자료 구조", list))
        assertEquals("그대로", SttCorrections.apply("그대로", emptyList()))
    }

    @Test
    fun wavHeaderAndSamples() {
        // 2-channel 16-bit 8 kHz, two frames.
        val data = byteArrayOf(0x00, 0x40, 0x00, 0x40, 0x00, 0xC0.toByte(), 0x00, 0x00)
        val header = java.io.ByteArrayOutputStream().apply {
            fun le32(v: Int) = write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun le16(v: Int) = write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            write("RIFF".toByteArray()); le32(36 + data.size); write("WAVE".toByteArray())
            write("fmt ".toByteArray()); le32(16); le16(1); le16(2); le32(8000); le32(32000); le16(4); le16(16)
            write("LIST".toByteArray()); le32(2); write(byteArrayOf(0, 0))
            write("data".toByteArray()); le32(data.size); write(data)
        }.toByteArray()
        val input = header.inputStream()
        val h = WavReader.readHeader(input)!!
        assertEquals(WavReader.Header(2, 8000, 2, false, data.size.toLong()), h)
        val body = input.readBytes()
        assertArrayEquals(floatArrayOf(0.5f, -0.25f), WavReader.toMono(body, body.size, h), 1e-6f)
    }
}
