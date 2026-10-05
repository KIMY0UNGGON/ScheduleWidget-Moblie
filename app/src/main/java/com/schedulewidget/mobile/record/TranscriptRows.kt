package com.schedulewidget.mobile.record

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.stt.Transcript

/**
 * A row of the transcript list: in reading mode consecutive segments of one speaker joined into a paragraph; while
 * editing or searching one segment per row (so each edit keeps its own timestamps for subtitles).
 */
internal data class Row(val segments: List<Int>, val startMs: Long, val endMs: Long, val speaker: Int?, val text: String, val edited: Boolean)

internal fun paragraphRows(t: Transcript): List<Row> {
    val out = ArrayList<Row>()
    t.segments.forEachIndexed { i, s ->
        val text = s.text.trim()
        if (text.isEmpty()) return@forEachIndexed
        val last = out.lastOrNull()
        // Same speaker, no long pause, not too long yet: keep going; otherwise a new paragraph (and a new timestamp).
        if (last != null && last.speaker == s.speaker && s.startMs - last.endMs < 2_500 && last.text.length < 360) {
            out[out.size - 1] = last.copy(segments = last.segments + i, endMs = s.endMs, text = last.text + " " + text, edited = last.edited || s.edited)
        } else out += Row(listOf(i), s.startMs, s.endMs, s.speaker, text, s.edited)
    }
    return out
}

internal fun segmentRows(t: Transcript): List<Row> =
    t.segments.mapIndexed { i, s -> Row(listOf(i), s.startMs, s.endMs, s.speaker, s.text, s.edited) }

/** Speaker colours (화자 1, 2, ...), readable on light and dark. */
internal val speakerColors = listOf(
    Color(0xFF0A84FF), Color(0xFFFF9F0A), Color(0xFF30D158), Color(0xFFBF5AF2),
    Color(0xFFFF375F), Color(0xFF64D2FF), Color(0xFFAC8E68), Color(0xFF5E5CE6),
)

@Composable
internal fun RowView(
    row: Row,
    names: Map<Int, String>,
    active: Boolean,
    editing: Boolean,
    editable: Boolean,
    highlights: List<Match>,
    currentMatch: Match?,
    onSeek: () -> Unit,
    onSpeaker: () -> Unit,
    onStartEdit: () -> Unit,
    onText: (String) -> Unit,
) {
    val color = speakerColor(row.speaker)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (active) color.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.speaker != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onSpeaker).padding(horizontal = 2.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(6.dp))
                    Text(speakerLabel(row.speaker, names), style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(6.dp))
            }
            Text(
                formatDuration(row.startMs),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onSeek).padding(horizontal = 4.dp, vertical = 2.dp),
            )
            if (row.edited) Text("수정됨", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
        }
        if (editing) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            OutlinedTextField(
                value = row.text, onValueChange = onText,
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).focusRequester(focus),
            )
        } else {
            val text = if (highlights.isEmpty()) AnnotatedString(row.text.ifEmpty { if (editable) "(빈 문단)" else "" })
            else highlighted(row.text, highlights, currentMatch)
            Text(
                text, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                    .then(if (editable) Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onStartEdit) else Modifier),
            )
        }
    }
}

private fun highlighted(text: String, hits: List<Match>, current: Match?): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in hits.sortedBy { it.start }) {
        if (m.start < at || m.end > text.length) continue
        append(text.substring(at, m.start))
        val strong = current != null && m.start == current.start
        withStyle(SpanStyle(background = if (strong) Color(0xFFFF9F0A) else Color(0x66FFD60A), fontWeight = if (strong) FontWeight.Bold else null)) {
            append(text.substring(m.start, m.end))
        }
        at = m.end
    }
    append(text.substring(at))
}
