package com.schedulewidget.mobile.notes.editor

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.data.PenPreset
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.notes.ink.Tool
import com.schedulewidget.mobile.notes.ui.NotesSegmented
import com.schedulewidget.mobile.notes.ui.NotesShapes
import com.schedulewidget.mobile.notes.ui.NotesSpace
import com.schedulewidget.mobile.notes.ui.NotesTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

/** Eraser icon (not in the material icon set): a tilted eraser block. */
internal val EraserIcon: ImageVector by lazy {
    ImageVector.Builder("Eraser", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(15.5f, 4.5f); lineTo(20f, 9f); lineTo(10.5f, 18.5f); lineTo(6f, 18.5f); lineTo(3.5f, 16f)
            close()
            moveTo(9f, 11f); lineTo(13.5f, 15.5f)
            moveTo(10.5f, 18.5f); lineTo(20f, 18.5f)
        }
    }.build()
}

private val SLOT_H = 52.dp

/**
 * The editor's tool strip, a Flexcil-style pen case: one slot per pen preset (pens and highlighters, in the user's
 * order), "+" for a new pen, then eraser, lasso and text, then the active tool's options. Tap a slot to pick it,
 * tap the picked slot again (or long-press any slot) to edit it. Scrolls sideways on phones.
 * [customPen] / [customHl] are the recently used custom colours of this editor session.
 */
@Composable
internal fun ToolStrip(st: EditorState, customPen: SnapshotStateList<Int>, customHl: SnapshotStateList<Int>) {
    val c = NotesTokens.colors
    var editing by remember { mutableStateOf<String?>(null) }
    PersistPens(st)
    Column(Modifier.fillMaxWidth().background(c.canvas)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = NotesSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val penMode = !st.linkMode && (st.displayTool == EditorTool.PEN || st.displayTool == EditorTool.HIGHLIGHTER)
            st.pens.forEach { pen ->
                key(pen.id) {
                    PenSlot(
                        pen = pen,
                        selected = penMode && pen.id == st.penId,
                        editing = editing == pen.id,
                        onTap = {
                            if (penMode && pen.id == st.penId) editing = pen.id else st.selectPen(pen.id)
                        },
                        onLongPress = { st.selectPen(pen.id); editing = pen.id },
                        editor = { PenEditorPopover(st, pen.id, customPen, customHl) { editing = null } },
                    )
                }
            }
            AddSlot(enabled = NotesPens.canAdd(st.pens)) {
                val active = st.activePen
                NotesPens.newPen(st.pens, active.tool)?.let { p ->
                    st.updatePens(NotesPens.insertAfter(st.pens, active.id, p), select = p.id)
                    editing = p.id
                }
            }
            Box(Modifier.padding(horizontal = NotesSpace.xs).width(1.dp).height(28.dp).background(c.hairline))
            ToolSlot(st, EditorTool.ERASER, EraserIcon, "지우개")
            ToolSlot(st, EditorTool.LASSO, Icons.Filled.HighlightAlt, "올가미")
            ToolSlot(st, EditorTool.TEXT, Icons.Filled.TextFields, "텍스트")
            Spacer(Modifier.width(NotesSpace.xs))
            ToolOptions(st, customPen)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairlineSoft))
    }
}

/** Options of the active non-pen tool (eraser mode, text colour) and a one-line hint. */
@Composable
private fun ToolOptions(st: EditorState, customPen: SnapshotStateList<Int>) {
    if (st.linkMode) return
    when (st.displayTool) {
        EditorTool.PEN -> {
            ShapeToggle(st)
            Hint(if (st.shapeCorrection) "선 끝에서 잠깐 멈추면 직선·원·곡선으로" else "펜을 한 번 더 누르면 설정")
        }
        EditorTool.HIGHLIGHTER -> Hint("멈춰 누르고 있으면 직선")
        EditorTool.ERASER -> {
            NotesSegmented(
                listOf(false to "획 지우개", true to "부분 지우개"), st.eraserPartial, { st.eraserPartial = it },
                Modifier.width(196.dp),
            )
            Hint("S펜 버튼을 누른 채 쓰면 언제든 지우개")
        }
        EditorTool.LASSO -> Hint("필기를 둘러싸서 선택 → 끌어서 이동")
        EditorTool.TEXT -> {
            var picking by remember { mutableStateOf(false) }
            val colors = (NotesPens.PALETTE.take(4) + NotesPens.PALETTE.drop(4).take(6) + customPen).distinct()
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                colors.forEach { col -> ColorDot(col, st.textColor == col, size = 30.dp) { st.textColorChoice = col } }
                Box {
                    AddDot("글자 색 직접 고르기") { picking = true }
                    if (picking) {
                        NotesPopover(onDismiss = {
                            val col = st.textColor
                            if (col !in NotesPens.PALETTE && col !in customPen) {
                                customPen.add(0, col); while (customPen.size > 8) customPen.removeAt(customPen.size - 1)
                            }
                            picking = false
                        }) {
                            Box(Modifier.width(300.dp).padding(NotesSpace.md)) { HsvPicker(st.textColor) { st.textColorChoice = it } }
                        }
                    }
                }
            }
            Hint("페이지를 탭하면 글상자를 넣어요")
        }
    }
}

/** A pen slot: the pen glyph (raised when selected, Flexcil-style), its width bar, soft pill + accent underline. */
@Composable
private fun PenSlot(
    pen: PenPreset,
    selected: Boolean,
    editing: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    editor: @Composable () -> Unit,
) {
    val c = NotesTokens.colors
    val drop by animateDpAsState(if (selected) 0.dp else 6.dp, label = "penLift")
    val name = NotesPens.kindLabel(pen.tool)
    Box(
        Modifier
            .minimumInteractiveComponentSize().size(40.dp, SLOT_H)
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected || editing) c.canvasSoft else Color.Transparent)
            .combinedClickable(
                role = Role.RadioButton, onClickLabel = name, onLongClickLabel = "$name 설정",
                onLongClick = onLongPress, onClick = onTap,
            )
            .semantics { this.selected = selected },
    ) {
        Box(Modifier.align(Alignment.TopCenter).padding(top = 3.dp).size(24.dp, 36.dp).clipToBounds()) {
            PenGlyph(pen, Modifier.fillMaxSize().offset { IntOffset(0, drop.roundToPx()) })
        }
        // Width: a bar in the pen's colour, thicker for wider pens.
        val barH = widthBarDp(pen).dp
        val barColor = Color(pen.color).let { if (pen.tool == Tool.HIGHLIGHTER) it.copy(alpha = 0.7f) else it }
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 42.dp).size(18.dp, barH)
                .clip(NotesShapes.full).background(barColor)
                .then(if (pen.color.isLightArgb()) Modifier.border(0.5.dp, c.hairline, NotesShapes.full) else Modifier),
        )
        if (selected) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp).size(14.dp, 2.dp).clip(NotesShapes.full).background(c.ink))
        }
        if (editing) editor()
    }
}

/** Eraser / lasso / text: icon on a soft pill when active, accent underline. */
@Composable
private fun ToolSlot(st: EditorState, tool: EditorTool, icon: ImageVector, label: String) {
    val c = NotesTokens.colors
    val selected = st.displayTool == tool && !st.linkMode
    Box(
        Modifier
            .minimumInteractiveComponentSize().size(44.dp, SLOT_H)
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) c.canvasSoft else Color.Transparent)
            .combinedClickable(role = Role.RadioButton, onClickLabel = label, onClick = {
                st.selectTool(tool)
                if (tool != EditorTool.LASSO) st.clearSelection()
            })
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (selected) c.ink else c.muted, modifier = Modifier.size(22.dp))
        if (selected) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp).size(14.dp, 2.dp).clip(NotesShapes.full).background(c.ink))
        }
    }
}

/** "+" slot: a new pen (disabled when the case holds NotesPens.MAX_SLOTS). */
@Composable
private fun AddSlot(enabled: Boolean, onClick: () -> Unit) {
    val c = NotesTokens.colors
    Box(
        Modifier.minimumInteractiveComponentSize().size(40.dp, SLOT_H).padding(horizontal = 2.dp).clip(RoundedCornerShape(14.dp))
            .combinedClickable(enabled = enabled, onClickLabel = "펜 추가", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).border(1.dp, c.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Add, "펜 추가", tint = if (enabled) c.ink else c.faint, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun AddDot(label: String, onClick: () -> Unit) {
    val c = NotesTokens.colors
    Box(
        Modifier.minimumInteractiveComponentSize().size(30.dp).clip(CircleShape).border(1.dp, c.hairline, CircleShape)
            .combinedClickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.Add, label, tint = c.ink, modifier = Modifier.size(16.dp)) }
}

/** 도형 보정 on/off (NotesSettings.shapeCorrection), saved at once. */
@Composable
private fun ShapeToggle(st: EditorState) {
    val c = NotesTokens.colors
    val context = LocalContext.current
    val on = st.shapeCorrection
    Box(
        Modifier
            .clip(NotesShapes.full)
            .background(if (on) c.ink else c.canvasSoft)
            .minimumInteractiveComponentSize()
            .toggleable(value = on, role = Role.Switch) { v ->
                st.shapeCorrection = v
                Repository.get(context).update { it.copy(notes = it.notes.copy(shapeCorrection = v)) }
            }
            .padding(horizontal = NotesSpace.sm, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (on) "도형 보정 켬" else "도형 보정 끔", style = NotesTokens.type.label.copy(color = if (on) c.onInk else c.ink), maxLines = 1)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = NotesTokens.type.caption, modifier = Modifier.padding(horizontal = NotesSpace.xs), maxLines = 1)
}

private fun Int.isLightArgb(): Boolean {
    val r = (this shr 16) and 0xFF; val g = (this shr 8) and 0xFF; val b = this and 0xFF
    return 0.299 * r + 0.587 * g + 0.114 * b > 225
}

// ---- persistence ----

/** Saves the pen case and the active slot to NotesSettings, debounced, and once more when the editor closes. */
@Composable
private fun PersistPens(st: EditorState) {
    val context = LocalContext.current
    LaunchedEffect(st) {
        snapshotFlow { st.pens to st.penId }.drop(1).collectLatest { (pens, id) ->
            delay(600)
            savePens(context, pens, id)
        }
    }
    DisposableEffect(st) { onDispose { savePens(context, st.pens, st.penId) } }
}

private fun savePens(context: Context, pens: List<PenPreset>, selected: String) {
    val stored = NotesPens.forStorage(pens)
    val repo = Repository.get(context)
    val cur = repo.data.value.notes
    if (cur.pens == stored && cur.selectedPen == selected) return
    repo.update { it.copy(notes = it.notes.copy(pens = stored, selectedPen = selected)) }
}
