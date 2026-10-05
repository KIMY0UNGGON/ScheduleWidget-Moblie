package com.schedulewidget.mobile.record

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.schedulewidget.mobile.data.SttCorrection
import com.schedulewidget.mobile.stt.SttCorrections
import com.schedulewidget.mobile.stt.Transcriber
import com.schedulewidget.mobile.stt.Transcript
import com.schedulewidget.mobile.stt.TranscriptSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Bumped whenever a transcript is saved after a user edit, so subtitles / lists reload it. */
object TranscriptEdits {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    fun bump() { _version.value++ }
}

/** "화자 1" or the name the user gave that speaker. */
fun speakerLabel(speaker: Int, names: Map<Int, String>): String = names[speaker]?.takeIf { it.isNotBlank() } ?: "화자 ${speaker + 1}"

/** One find-and-replace hit: [segment] index and the character range in its text. */
data class Match(val segment: Int, val start: Int, val end: Int)

fun findMatches(segments: List<TranscriptSegment>, query: String): List<Match> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<Match>()
    segments.forEachIndexed { i, s ->
        var from = 0
        while (true) {
            val at = s.text.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            out += Match(i, at, at + query.length)
            from = at + query.length
        }
    }
    return out
}

/**
 * The transcript being corrected in the transcript screen: every change goes through here, with an undo stack for
 * this session and a "needs saving" flag the screen's autosave watches. Text typing pushes one undo step per
 * segment editing session ([beginTextEdit]), not one per keystroke.
 */
@Stable
class TranscriptEditor(initial: Transcript) {
    var transcript by mutableStateOf(initial)
        private set
    private val undo = ArrayDeque<Transcript>()
    var undoCount by mutableIntStateOf(0)
        private set
    /** Changes not yet written to disk; increments on every change (the autosave key). */
    var revision by mutableIntStateOf(0)
        private set
    // State too, so "저장 중…" turns into "저장됨" when the background save finishes.
    private var savedRevision by mutableIntStateOf(0)
    private var textEditSegment = -1
    private var pendingUndo = false

    val dirty: Boolean get() = revision != savedRevision

    private fun commit(new: Transcript, pushUndo: Boolean) {
        if (new == transcript) return
        if (pushUndo) {
            undo.addLast(transcript)
            if (undo.size > 100) undo.removeFirst()
            undoCount = undo.size
        }
        transcript = new.copy(editedAt = System.currentTimeMillis())
        revision++
    }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        undoCount = undo.size
        textEditSegment = -1
        transcript = prev
        revision++
    }

    /** Starts editing segment [i]'s text: one undo step for everything typed until another segment is edited. */
    fun beginTextEdit(i: Int) {
        if (textEditSegment == i) return
        textEditSegment = i
        pendingUndo = true // pushed with the first actual change
    }

    fun endTextEdit() { textEditSegment = -1; pendingUndo = false }

    fun setText(i: Int, text: String) {
        val seg = transcript.segments.getOrNull(i) ?: return
        if (seg.text == text) return
        commit(transcript.copy(segments = transcript.segments.toMutableList().also { it[i] = seg.copy(text = text, edited = true) }), pushUndo = pendingUndo)
        pendingUndo = false
    }

    fun renameSpeaker(speaker: Int, name: String) {
        val names = transcript.speakerNames.toMutableMap()
        if (name.isBlank()) names.remove(speaker) else names[speaker] = name.trim()
        endTextEdit()
        commit(transcript.copy(speakerNames = names), pushUndo = true)
    }

    fun setSpeaker(i: Int, speaker: Int) {
        val seg = transcript.segments.getOrNull(i) ?: return
        endTextEdit()
        commit(transcript.copy(segments = transcript.segments.toMutableList().also { it[i] = seg.copy(speaker = speaker, edited = true) }), pushUndo = true)
    }

    /** Like [setSpeaker] but folded into the previous undo step. */
    fun setSpeakerNoUndo(i: Int, speaker: Int) {
        val seg = transcript.segments.getOrNull(i) ?: return
        commit(transcript.copy(segments = transcript.segments.toMutableList().also { it[i] = seg.copy(speaker = speaker, edited = true) }), pushUndo = false)
    }

    fun replace(m: Match, with: String) {
        val seg = transcript.segments.getOrNull(m.segment) ?: return
        if (m.end > seg.text.length) return
        endTextEdit()
        val text = seg.text.replaceRange(m.start, m.end, with)
        commit(transcript.copy(segments = transcript.segments.toMutableList().also { it[m.segment] = seg.copy(text = text, edited = true) }), pushUndo = true)
    }

    /** Replaces every match; returns how many. One undo step. */
    fun replaceAll(query: String, with: String): Int {
        if (query.isEmpty()) return 0
        var count = 0
        val segs = transcript.segments.map { s ->
            val n = findMatches(listOf(s), query).size
            if (n == 0) s else {
                count += n
                s.copy(text = s.text.replace(query, with, ignoreCase = true), edited = true)
            }
        }
        endTextEdit()
        if (count > 0) commit(transcript.copy(segments = segs), pushUndo = true)
        return count
    }

    /** Applies the auto-correction list to every segment; returns how many segments changed. One undo step. */
    fun applyCorrections(list: List<SttCorrection>): Int {
        var changed = 0
        val segs = transcript.segments.map { s ->
            val t = SttCorrections.apply(s.text, list)
            if (t == s.text) s else { changed++; s.copy(text = t, edited = true) }
        }
        endTextEdit()
        if (changed > 0) commit(transcript.copy(segments = segs), pushUndo = true)
        return changed
    }

    /** Writes the transcript if it changed since the last save. Blocking: call off the main thread. */
    fun saveIfDirty(context: Context) {
        val rev = revision
        if (rev == savedRevision) return
        val t = transcript
        runCatching { Transcriber.save(context, t) }.onSuccess {
            savedRevision = rev
            TranscriptEdits.bump()
        }
    }
}

/** Moves the segments [indices] to [speaker] (one undo step). */
fun TranscriptEditor.setSpeakers(indices: List<Int>, speaker: Int) {
    if (indices.size == 1) return setSpeaker(indices[0], speaker)
    indices.forEachIndexed { n, i -> if (n == 0) setSpeaker(i, speaker) else setSpeakerNoUndo(i, speaker) }
}
