package com.schedulewidget.mobile.notes.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.ink.Template
import com.schedulewidget.mobile.notes.render.PageDraw
import com.schedulewidget.mobile.notes.ui.NotesPill
import com.schedulewidget.mobile.notes.ui.NotesSegmented
import com.schedulewidget.mobile.notes.ui.NotesShapes
import com.schedulewidget.mobile.notes.ui.NotesSpace
import com.schedulewidget.mobile.notes.ui.NotesTokens
import com.schedulewidget.mobile.notes.ui.PillStyle

// ---- shared dialog chrome (canvas sheet, 24dp corners, hairline edge, pill buttons; no elevation) ----

/** Editor dialog frame: title, [content], and a right-aligned row of pill [buttons]. */
@Composable
internal fun NotesDialog(
    title: String,
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    buttons: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = NotesTokens.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false, dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible,
        ),
    ) {
        Column(
            Modifier
                .padding(horizontal = NotesSpace.lg)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .clip(NotesShapes.md)
                .background(c.canvas)
                .border(1.dp, c.hairlineSoft, NotesShapes.md)
                .padding(NotesSpace.lg),
        ) {
            Text(title, style = NotesTokens.type.h3.copy(fontSize = 20.sp, lineHeight = 26.sp))
            Spacer(Modifier.height(NotesSpace.md))
            content()
            if (buttons != null) {
                Spacer(Modifier.height(NotesSpace.lg))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NotesSpace.xs, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons,
                )
            }
        }
    }
}

/** Input on a field tint (16dp corners, no outline); [minHeight] > 0 makes it a multi-line box of that height. */
@Composable
internal fun NotesField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    val c = NotesTokens.colors
    val style = NotesTokens.type.body
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = style,
        cursorBrush = SolidColor(c.ink),
        singleLine = singleLine,
        keyboardOptions = if (onDone != null) KeyboardOptions(imeAction = ImeAction.Done) else KeyboardOptions.Default,
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(if (singleLine) Modifier else Modifier.fillMaxHeight())
                    .clip(NotesShapes.sm)
                    .background(c.field)
                    .padding(horizontal = NotesSpace.md, vertical = NotesSpace.sm),
            ) {
                if (value.text.isEmpty()) Text(placeholder, style = style.copy(color = c.faint))
                inner()
            }
        },
    )
}

// ---- dialogs ----

/** Text box editor: empty text deletes the box. */
@Composable
internal fun TextEditDialog(edit: TextEdit, onDone: (String, Float) -> Unit, onDismiss: () -> Unit) {
    val initial = edit.box?.text.orEmpty()
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var size by remember { mutableFloatStateOf(edit.box?.size ?: 14f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    NotesDialog(
        title = if (edit.box == null) "글상자 추가" else "글상자 편집",
        onDismiss = onDismiss,
        buttons = {
            if (edit.box != null) {
                NotesPill("삭제", onClick = { onDone("", size) }, style = PillStyle.Danger)
                Spacer(Modifier.weight(1f))
            }
            NotesPill("취소", onClick = onDismiss, style = PillStyle.Outline)
            NotesPill("확인", onClick = { onDone(value.text, size) })
        },
    ) {
        NotesField(
            value = value, onValueChange = { value = it }, placeholder = "내용을 입력하세요",
            modifier = Modifier.fillMaxWidth().height(140.dp).focusRequester(focus),
        )
        Spacer(Modifier.height(NotesSpace.sm))
        NotesSegmented(
            options = listOf(10f to "작게", 14f to "보통", 20f to "크게", 28f to "제목"),
            selected = size, onSelect = { size = it }, modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Notebook title editor (tap on the editor's title). Blank names are not accepted. */
@Composable
internal fun RenameDialog(initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val name = value.text.trim()
    val submit = { if (name.isNotEmpty()) onDone(name) }
    NotesDialog(
        title = "이름 바꾸기",
        onDismiss = onDismiss,
        buttons = {
            NotesPill("취소", onClick = onDismiss, style = PillStyle.Outline)
            NotesPill("저장", onClick = submit, enabled = name.isNotEmpty())
        },
    ) {
        NotesField(
            value = value, onValueChange = { value = it }, placeholder = "노트 이름",
            modifier = Modifier.fillMaxWidth().focusRequester(focus), singleLine = true, onDone = submit,
        )
    }
}

/** Blocking progress while a PDF is written (share / save as). */
@Composable
internal fun ExportProgressDialog(title: String = "PDF 만드는 중…", message: String = "페이지가 많으면 시간이 걸려요") {
    val c = NotesTokens.colors
    NotesDialog(title = title, onDismiss = {}, dismissible = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), color = c.ink, trackColor = c.field, strokeWidth = 2.5.dp)
            Spacer(Modifier.width(NotesSpace.md))
            Text(message, style = NotesTokens.type.bodySm.copy(color = c.muted))
        }
    }
}

/** Template choice for a new blank page. */
@Composable
internal fun TemplatePickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = NotesTokens.colors
    NotesDialog(
        title = "빈 페이지 추가",
        onDismiss = onDismiss,
        buttons = { NotesPill("취소", onClick = onDismiss, style = PillStyle.Outline) },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(NotesSpace.sm)) {
            Template.all.forEach { t ->
                Column(
                    Modifier.weight(1f).clip(NotesShapes.sm).clickable { onPick(t) }.padding(NotesSpace.xxs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val page = remember(t) { PageInfo("preview", PageInfo.KIND_BLANK, template = t, w = 120f, h = 170f) }
                    // Paper on the desk tint, edged by a hairline (no shadow).
                    Box(Modifier.fillMaxWidth().clip(NotesShapes.sm).background(c.desk).padding(NotesSpace.xs)) {
                        Canvas(
                            Modifier.fillMaxWidth().aspectRatio(120f / 170f)
                                .background(Color.White)
                                .border(1.dp, c.hairline)
                        ) {
                            drawIntoCanvas { cv ->
                                val sc = size.width / page.w
                                cv.nativeCanvas.save()
                                cv.nativeCanvas.scale(sc, sc)
                                PageDraw.drawTemplate(cv.nativeCanvas, page, sc)
                                cv.nativeCanvas.restore()
                            }
                        }
                    }
                    Spacer(Modifier.height(NotesSpace.xs))
                    Text(Template.label(t), style = NotesTokens.type.label)
                }
            }
        }
    }
}

/** Custom colour: hue / saturation / brightness sliders with a preview. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorPickerDialog(initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = NotesTokens.colors
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    val color = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    val sliderColors = SliderDefaults.colors(
        thumbColor = c.ink, activeTrackColor = c.ink, inactiveTrackColor = c.field,
        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
    )
    NotesDialog(
        title = "색 고르기",
        onDismiss = onDismiss,
        buttons = {
            NotesPill("취소", onClick = onDismiss, style = PillStyle.Outline)
            NotesPill("확인", onClick = { onPick(color) })
        },
    ) {
        Box(
            Modifier.fillMaxWidth().height(48.dp).clip(NotesShapes.sm).background(Color(color))
                .border(1.dp, c.hairline, NotesShapes.sm)
        )
        Spacer(Modifier.height(NotesSpace.md))
        Text("색상", style = NotesTokens.type.caption)
        Slider(value = h, onValueChange = { h = it }, valueRange = 0f..359f, colors = sliderColors)
        Text("채도", style = NotesTokens.type.caption)
        Slider(value = s, onValueChange = { s = it }, colors = sliderColors)
        Text("밝기", style = NotesTokens.type.caption)
        Slider(value = v, onValueChange = { v = it }, colors = sliderColors)
        Spacer(Modifier.height(NotesSpace.xxs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(NotesSpace.xs), verticalArrangement = Arrangement.spacedBy(NotesSpace.xxs)) {
            QUICK.forEach { q ->
                val selected = q == color
                Box(
                    Modifier.minimumInteractiveComponentSize().size(28.dp).clip(CircleShape)
                        .selectable(selected = selected, role = Role.RadioButton) {
                            val t = FloatArray(3); android.graphics.Color.colorToHSV(q, t)
                            h = t[0]; s = t[1]; v = t[2]
                        }
                        .semantics { contentDescription = colorA11yLabel(q) }
                        .background(Color(q))
                        .border(if (selected) 2.dp else 1.dp, if (selected) c.ink else c.hairline, CircleShape)
                )
            }
        }
    }
}

private val QUICK = listOf(
    0xFF5D4037.toInt(), 0xFFFF9800.toInt(), 0xFF9C27B0.toInt(), 0xFF00897B.toInt(),
    0xFF3F51B5.toInt(), 0xFF757575.toInt(), Color(0xFFE91E63).toArgb(),
)

/** A colour swatch; [selected] gets an accent ring. */
@Composable
internal fun Swatch(color: Int, selected: Boolean, onClick: () -> Unit) {
    val c = NotesTokens.colors
    Box(
        Modifier.padding(horizontal = 3.dp).minimumInteractiveComponentSize().size(30.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.border(2.dp, c.ink, CircleShape) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = colorA11yLabel(color) }
            .padding(4.dp)
            .background(Color(color), CircleShape)
            .border(1.dp, c.hairline, CircleShape)
    )
}

/** A pen width choice drawn as a dot of that width. */
@Composable
internal fun WidthDot(widthPt: Float, maxPt: Float, color: Int, selected: Boolean, onClick: () -> Unit) {
    val c = NotesTokens.colors
    Box(
        Modifier.padding(horizontal = 2.dp).minimumInteractiveComponentSize().size(30.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(c.canvasSoft).border(2.dp, c.ink, CircleShape) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "선 굵기 ${widthPt}pt" },
        contentAlignment = Alignment.Center,
    ) {
        val d = (4f + 14f * (widthPt / maxPt)).dp
        Box(Modifier.width(d).height(d).background(Color(color).copy(alpha = 1f), CircleShape))
    }
}

private fun colorA11yLabel(color: Int) = "색상 #${(color and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()}"
