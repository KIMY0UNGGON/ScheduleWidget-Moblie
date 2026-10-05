package com.schedulewidget.mobile.stt

import kotlin.math.min
import kotlin.math.sqrt

// Pure speaker embedding clustering and assignment helpers.

/** A diarization turn in seconds with a global (0-based) speaker id. */
data class SpeakerTurn(val start: Double, val end: Double, val speaker: Int)

object Speakers {
    fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
    }

    /**
     * Average-linkage agglomerative clustering of speaker embeddings by cosine similarity.
     * With [numClusters] > 0 merges until that many clusters remain (or nothing is left to merge);
     * otherwise merges while the best pair is more similar than [threshold]. Returns a cluster id per input.
     * [weights] (e.g. seconds of speech) weight the cluster averages.
     */
    fun cluster(embeddings: List<FloatArray>, numClusters: Int, threshold: Double, weights: List<Double>? = null): IntArray {
        val n = embeddings.size
        if (n == 0) return IntArray(0)
        val members = MutableList(n) { mutableListOf(it) }
        val sim = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else cosine(embeddings[i], embeddings[j]) } }
        val w = weights ?: List(n) { 1.0 }
        fun linkage(a: List<Int>, b: List<Int>): Double {
            var total = 0.0; var weight = 0.0
            for (i in a) for (j in b) { val ww = w[i] * w[j]; total += sim[i][j] * ww; weight += ww }
            return if (weight == 0.0) 0.0 else total / weight
        }
        while (members.size > 1) {
            var bestA = -1; var bestB = -1; var best = Double.NEGATIVE_INFINITY
            for (a in members.indices) for (b in a + 1 until members.size) {
                val s = linkage(members[a], members[b])
                if (s > best) { best = s; bestA = a; bestB = b }
            }
            val keepGoing = if (numClusters > 0) members.size > numClusters else best >= threshold
            if (!keepGoing) break
            members[bestA].addAll(members[bestB])
            members.removeAt(bestB)
        }
        val labels = IntArray(n)
        members.sortedBy { it.min() }.forEachIndexed { id, group -> group.forEach { labels[it] = id } }
        return labels
    }

    /**
     * Picks for every recognized segment (seconds) the speaker whose turns overlap it most. Segments that
     * overlap no turn take the nearest turn within [maxDistance] seconds, else the previous segment's speaker.
     */
    fun assign(segments: List<Pair<Double, Double>>, turns: List<SpeakerTurn>, maxDistance: Double = 2.0): List<Int?> {
        val sorted = turns.sortedBy { it.start }
        var previous: Int? = null
        return segments.map { (s, e) ->
            val overlap = HashMap<Int, Double>()
            for (t in sorted) {
                if (t.start >= e) break
                val o = min(e, t.end) - maxOf(s, t.start)
                if (o > 0) overlap[t.speaker] = (overlap[t.speaker] ?: 0.0) + o
            }
            val chosen = overlap.maxByOrNull { it.value }?.key
                ?: sorted.minByOrNull { t -> if (t.end <= s) s - t.end else t.start - e }
                    ?.takeIf { t -> (if (t.end <= s) s - t.end else t.start - e) <= maxDistance }?.speaker
                ?: previous
            previous = chosen
            chosen
        }
    }

    /**
     * Cleans up clustering [labels]: a cluster holding less than [minShare] of all speech ([seconds]) is folded
     * into the cluster whose centroid is most similar, and while more than [target] (> 0) clusters remain the
     * smallest one is folded the same way. Labels come back renumbered 0..n-1 by first member.
     */
    fun consolidate(labels: IntArray, embeddings: List<FloatArray>, seconds: List<Double>, minShare: Double, target: Int): IntArray {
        val out = labels.copyOf()
        val total = seconds.sum().takeIf { it > 0 } ?: return out
        while (true) {
            val ids = out.distinct()
            if (ids.size <= 1) break
            val share = ids.associateWith { id -> out.indices.filter { out[it] == id }.sumOf { seconds[it] } / total }
            val smallest = ids.minBy { share.getValue(it) }
            val tooSmall = share.getValue(smallest) < minShare
            val tooMany = target > 0 && ids.size > target
            if (!tooSmall && !tooMany) break
            val centroid = { id: Int -> centroid(out.indices.filter { out[it] == id }, embeddings, seconds) }
            val mine = centroid(smallest)
            val into = ids.filter { it != smallest }.maxBy { cosine(mine, centroid(it)) }
            for (i in out.indices) if (out[i] == smallest) out[i] = into
        }
        val order = HashMap<Int, Int>()
        return IntArray(out.size) { order.getOrPut(out[it]) { order.size } }
    }

    private fun centroid(members: List<Int>, embeddings: List<FloatArray>, weights: List<Double>): FloatArray {
        val c = FloatArray(embeddings[members.first()].size)
        for (m in members) {
            val e = embeddings[m]
            var norm = 0.0
            for (x in e) norm += x * x
            val scale = (weights[m] / sqrt(norm.coerceAtLeast(1e-12))).toFloat()
            for (k in c.indices) c[k] += e[k] * scale
        }
        return c
    }

    /** Renumbers speakers by order of first appearance so the first voice heard is 화자 1. */
    fun renumber(speakers: List<Int?>): List<Int?> {
        val map = HashMap<Int, Int>()
        return speakers.map { s -> s?.let { map.getOrPut(it) { map.size } } }
    }
}
