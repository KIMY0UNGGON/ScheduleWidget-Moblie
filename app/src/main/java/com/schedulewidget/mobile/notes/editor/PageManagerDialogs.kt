package com.schedulewidget.mobile.notes.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.notes.ink.Template
import com.schedulewidget.mobile.notes.render.PageTemplates
import com.schedulewidget.mobile.notes.ui.NotesPill
import com.schedulewidget.mobile.notes.ui.NotesSegmented
import com.schedulewidget.mobile.notes.ui.NotesShapes
import com.schedulewidget.mobile.notes.ui.NotesSpace
import com.schedulewidget.mobile.notes.ui.NotesTokens
import com.schedulewidget.mobile.notes.ui.PillStyle

/** Bottom sheet shared by the page and import pickers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotesSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = NotesTokens.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.canvas,
        contentColor = c.ink,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).clip(NotesShapes.full).background(c.hairline))
        },
    ) {
        Column(Modifier.navigationBarsPadding()) { content() }
    }
}

/** Round check marker used on selectable page thumbnails. */
@Composable
internal fun CheckCircle(checked: Boolean, modifier: Modifier = Modifier) {
    val c = NotesTokens.colors
    Box(
        modifier.size(24.dp).clip(CircleShape)
            .background(if (checked) c.ink else Color.White.copy(alpha = 0.85f))
            .border(1.5.dp, if (checked) c.ink else c.faint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Filled.Check, null, tint = c.onInk, modifier = Modifier.size(16.dp))
    }
}

@Composable
internal fun BusyDialog(stage: String, onCancel: () -> Unit) {
    val c = NotesTokens.colors
    NotesDialog(title = "페이지 준비 중", onDismiss = {}, dismissible = false, buttons = {
        NotesPill("취소", onClick = onCancel, style = PillStyle.Outline)
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), color = c.ink, trackColor = c.field, strokeWidth = 2.5.dp)
            Spacer(Modifier.width(NotesSpace.md))
            Text(stage, style = NotesTokens.type.bodySm.copy(color = c.muted))
        }
    }
}

/** Which pages of a file to insert: all, or a range such as "1-3, 5". */
@Composable
internal fun PageRangeDialog(title: String, count: Int, onInsert: (List<Int>) -> Unit, onDismiss: () -> Unit) {
    val c = NotesTokens.colors
    var all by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf(TextFieldValue("")) }
    val parsed = if (all) (0 until count).toList() else PageOps.parseRange(text.text, count)
    NotesDialog(
        title = "페이지 넣기",
        onDismiss = onDismiss,
        buttons = {
            NotesPill("취소", onClick = onDismiss, style = PillStyle.Outline)
            NotesPill(if (parsed != null) "${parsed.size}쪽 넣기" else "넣기", onClick = { parsed?.let(onInsert) }, enabled = parsed != null)
        },
    ) {
        Text("$title · 총 ${count}쪽", style = NotesTokens.type.bodySm.copy(color = c.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(NotesSpace.md))
        NotesSegmented(listOf(true to "전체", false to "페이지 지정"), all, { all = it }, Modifier.fillMaxWidth())
        if (!all) {
            Spacer(Modifier.height(NotesSpace.sm))
            NotesField(text, { text = it }, placeholder = "예: 1-3, 5", singleLine = true, modifier = Modifier.fillMaxWidth(),
                onDone = { parsed?.let(onInsert) })
            Spacer(Modifier.height(NotesSpace.xs))
            Text(
                if (text.text.isNotBlank() && parsed == null) "1부터 ${count}까지의 쪽 번호로 적어 주세요 (예: 1-3, 5)"
                else "쉼표로 여러 쪽, 하이픈으로 범위를 적어요",
                style = NotesTokens.type.caption.copy(color = if (text.text.isNotBlank() && parsed == null) c.danger else c.muted),
            )
        }
    }
}

/** Template and paper choices for the blank pages in [indices]. */
@Composable
internal fun BackgroundDialog(st: EditorState, indices: List<Int>, onApply: (String, String?) -> Unit, onDismiss: () -> Unit) {
    val c = NotesTokens.colors
    val first = indices.mapNotNull { st.note.pages.getOrNull(it) }.firstOrNull { !it.isPdf }
    var template by remember { mutableStateOf(first?.template ?: Template.PLAIN) }
    var paper by remember { mutableStateOf(first?.paper ?: PageTemplates.PAPER_WHITE) }
    NotesDialog(
        title = "배경 바꾸기",
        onDismiss = onDismiss,
        buttons = {
            NotesPill("취소", onClick = onDismiss, style = PillStyle.Outline)
            NotesPill("적용", onClick = { onApply(template, paper) }, enabled = first != null)
        },
    ) {
        if (first == null) {
            Text("PDF 페이지는 배경을 바꿀 수 없어요", style = NotesTokens.type.bodySm.copy(color = c.muted))
            return@NotesDialog
        }
        TemplateStrip(template, paper, first.w / first.h) { template = it }
        Spacer(Modifier.height(NotesSpace.md))
        PaperPicker(paper) { paper = it }
    }
}
