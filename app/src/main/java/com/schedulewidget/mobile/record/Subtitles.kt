package com.schedulewidget.mobile.record

import android.content.Context
import androidx.core.content.edit
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schedulewidget.mobile.stt.TranscriptSegment
import com.schedulewidget.mobile.stt.Transcriber
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Subtitle text sizes (current line); the faded lines around it are smaller. */
enum class SubtitleSize(val id: String, val label: String, val sp: Int) {
    S("s", "작게", 16), M("m", "보통", 20), L("l", "크게", 25);

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: M
    }
}

/**
 * View preferences for subtitles during playback (on/off, size). Kept in the "record" SharedPreferences: they are
 * per-device viewing choices, not part of the synced AppData. Compose-observable once [load]ed.
 */
object SubtitlePrefs {
    private const val FILE = "record"
    private const val KEY_ON = "subtitles_on"
    private const val KEY_SIZE = "subtitles_size"
    private var loaded = false
    var enabled by mutableStateOf(true)
        private set
    var size by mutableStateOf(SubtitleSize.M)
        private set

    fun load(context: Context) {
        if (loaded) return
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        enabled = p.getBoolean(KEY_ON, true)
        size = SubtitleSize.of(p.getString(KEY_SIZE, null))
        loaded = true
    }

    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean(KEY_ON, on) }
    }

    fun setSize(context: Context, s: SubtitleSize) {
        size = s
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(KEY_SIZE, s.id) }
    }
}

/** A segment stays on screen this long after it ends (pauses between sentences), then fades. */
private const val HOLD_MS = 1_500L

/** Index of the last segment starting at or before [ms] (segments sorted by start), or -1. */
fun segmentIndexAt(segments: List<TranscriptSegment>, ms: Long): Int {
    var lo = 0
    var hi = segments.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (segments[mid].startMs <= ms) { found = mid; lo = mid + 1 } else hi = mid - 1
    }
    return found
}

/** The segment to show as the subtitle at [ms]: inside it, or within [HOLD_MS] after it ended; -1 for none. */
fun subtitleIndexAt(segments: List<TranscriptSegment>, ms: Long): Int {
    val i = segmentIndexAt(segments, ms)
    return if (i >= 0 && ms <= segments[i].endMs + HOLD_MS) i else -1
}

/** Segments with text, sorted by start, for subtitles. */
fun subtitleSegments(segments: List<TranscriptSegment>): List<TranscriptSegment> =
    segments.filter { it.text.isNotBlank() }.sortedBy { it.startMs }

/**
 * Subtitles for the playing recording: the current line large (with the speaker when diarized), the previous line
 * faded above and the next one faded below. Changes cross-fade; between sentences the last line lingers briefly.
 * Recomposes only when the shown segment changes, not on every position tick.
 */
@Composable
fun SubtitleView(
    segments: List<TranscriptSegment>, index: Int, size: SubtitleSize, modifier: Modifier = Modifier,
    names: Map<Int, String> = emptyMap(),
) {
    val sideSp = (size.sp * 0.72f).sp
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        AnimatedContent(
            targetState = index,
            transitionSpec = {
                val down = targetState > initialState
                (fadeIn(tween(220)) + slideInVertically(tween(220)) { if (down) it / 6 else -it / 6 }) togetherWith
                    (fadeOut(tween(160)) + slideOutVertically(tween(160)) { if (down) -it / 6 else it / 6 })
            },
            label = "subtitle",
        ) { i ->
            val cur = segments.getOrNull(i)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Fixed slots so the player below does not jump when a line is missing.
                SideLine(segments.getOrNull(i - 1)?.text.takeIf { cur != null }, sideSp.value, muted)
                Box(Modifier.fillMaxWidth().heightIn(min = (size.sp * 2.9f).dp), contentAlignment = Alignment.Center) {
                    if (cur != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (cur.speaker != null) Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(speakerColor(cur.speaker)))
                            Spacer(Modifier.width(5.dp))
                            Text(speakerLabel(cur.speaker, names), color = speakerColor(cur.speaker), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            cur.text.trim(), fontSize = size.sp.sp, lineHeight = (size.sp * 1.3f).sp,
                            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                SideLine(segments.getOrNull(if (cur != null) i + 1 else -1)?.text, sideSp.value, muted)
            }
        }
    }
}

@Composable
private fun SideLine(text: String?, sp: Float, color: androidx.compose.ui.graphics.Color) {
    Text(
        text?.trim().orEmpty(), fontSize = sp.sp, lineHeight = (sp * 1.25f).sp, color = color.copy(alpha = 0.55f),
        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().heightIn(min = (sp * 1.35f).dp),
    )
}

/** "자막" on/off and 작게 / 보통 / 크게, under the player. */
@Composable
fun SubtitleControls(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    SubtitlePrefs.load(context) // no-op after the first call
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = SubtitlePrefs.enabled,
            onClick = { SubtitlePrefs.setEnabled(context, !SubtitlePrefs.enabled) },
            label = { Text("자막") },
            leadingIcon = { Icon(Icons.Outlined.ClosedCaption, null, Modifier.size(18.dp)) },
        )
        if (SubtitlePrefs.enabled) SubtitleSize.entries.forEach { s ->
            FilterChip(selected = SubtitlePrefs.size == s, onClick = { SubtitlePrefs.setSize(context, s) }, label = { Text(s.label) })
        }
    }
}

/** Subtitle lines plus the speaker names the user gave. */
data class SubtitleTrack(val segments: List<TranscriptSegment> = emptyList(), val names: Map<Int, String> = emptyMap())

/**
 * The transcript's subtitle lines for [item] (loaded off the main thread; reloaded after the user corrects it), empty
 * without a transcript. Uses the corrected text and speaker names.
 */
@Composable
fun rememberSubtitleTrack(item: RecordingItem, available: Boolean): SubtitleTrack {
    val context = LocalContext.current
    SubtitlePrefs.load(context) // no-op after the first call
    val edits by TranscriptEdits.version.collectAsStateWithLifecycle()
    val track by produceState(SubtitleTrack(), item.id, available, edits) {
        value = if (!available) SubtitleTrack() else withContext(Dispatchers.IO) {
            runCatching { Transcriber.load(context, item.id) }.getOrNull()
                ?.let { SubtitleTrack(subtitleSegments(it.segments), it.speakerNames) } ?: SubtitleTrack()
        }
    }
    return track
}
