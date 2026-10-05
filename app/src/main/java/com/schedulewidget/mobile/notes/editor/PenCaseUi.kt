package com.schedulewidget.mobile.notes.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.graphics.withScale
import com.schedulewidget.mobile.data.PenPreset
import com.schedulewidget.mobile.notes.ink.Stroke
import com.schedulewidget.mobile.notes.ink.Tool
import com.schedulewidget.mobile.notes.render.PageDraw
import com.schedulewidget.mobile.notes.ui.NotesIconButton
import com.schedulewidget.mobile.notes.ui.NotesPill
import com.schedulewidget.mobile.notes.ui.NotesSegmented
import com.schedulewidget.mobile.notes.ui.NotesShapes
import com.schedulewidget.mobile.notes.ui.NotesSpace
import com.schedulewidget.mobile.notes.ui.NotesTokens
import com.schedulewidget.mobile.notes.ui.PillStyle
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// UI of the pen case: the pen glyph drawn in each tool-strip slot, the pen editor popover (kind, colour, width,
// duplicate / delete / reset / reorder) and its HSV colour picker. Logic lives in [NotesPens].

// ---- pen glyph ----

/**
 * A pen standing upright, its body in the pen's colour and its tip telling the kind: fountain nib, ballpoint cone,
 * sharpened pencil, brush tuft, chisel highlighter. Drawn in a 40-unit-tall box; the body runs to the bottom edge.
 */
internal fun DrawScope.drawPenGlyph(tool: Int, color: Color, outline: Color, metal: Color, metalDark: Color) {
    val u = size.height / 40f
    val cx = size.width / 2f
    val bodyW = when (tool) { Tool.HIGHLIGHTER -> 13f * u; Tool.BRUSH -> 8f * u; else -> 9f * u }
    val half = bodyW / 2f
    val light = color.luminance() > 0.8f
    val bodyTop = 16f * u

    fun fill(p: Path, c: Color) {
        drawPath(p, c)
        if (light && c == color) drawPath(p, outline, style = DrawStroke(width = 1f))
    }

    // Body.
    drawRoundRect(color, Offset(cx - half, bodyTop), Size(bodyW, size.height - bodyTop + 4f * u), CornerRadius(2f * u))
    if (light) drawRoundRect(outline, Offset(cx - half, bodyTop), Size(bodyW, size.height - bodyTop + 4f * u), CornerRadius(2f * u), style = DrawStroke(1f))

    when (tool) {
        Tool.PEN -> {
            drawRect(metalDark, Offset(cx - half, 13f * u), Size(bodyW, 3f * u))
            val nib = Path().apply {
                moveTo(cx - half * 0.8f, 13f * u)
                cubicTo(cx - half * 0.8f, 8f * u, cx - 1.2f * u, 5f * u, cx, 2f * u)
                cubicTo(cx + 1.2f * u, 5f * u, cx + half * 0.8f, 8f * u, cx + half * 0.8f, 13f * u)
                close()
            }
            drawPath(nib, metal)
            drawLine(metalDark, Offset(cx, 3f * u), Offset(cx, 9.5f * u), strokeWidth = 0.9f * u)
            drawCircle(metalDark, 0.9f * u, Offset(cx, 10.2f * u))
            drawCircle(color, 0.9f * u, Offset(cx, 2.4f * u))
        }
        Tool.BALLPOINT -> {
            val cone = Path().apply {
                moveTo(cx - half, bodyTop); lineTo(cx - 1.4f * u, 6.5f * u); lineTo(cx + 1.4f * u, 6.5f * u); lineTo(cx + half, bodyTop); close()
            }
            drawPath(cone, metal)
            drawLine(metalDark, Offset(cx - half * 0.7f, 13f * u), Offset(cx + half * 0.7f, 13f * u), strokeWidth = 0.8f * u)
            drawCircle(color, 1.4f * u, Offset(cx, 5.2f * u))
            if (light) drawCircle(outline, 1.4f * u, Offset(cx, 5.2f * u), style = DrawStroke(1f))
        }
        Tool.PENCIL -> {
            val wood = Path().apply { moveTo(cx - half, bodyTop); lineTo(cx, 2.5f * u); lineTo(cx + half, bodyTop); close() }
            drawPath(wood, Color(0xFFE9CFA4))
            // Graphite: the top 40% of the cone, in the pen's colour.
            val gy = 2.5f * u + (bodyTop - 2.5f * u) * 0.4f
            val gw = half * 0.4f
            fill(Path().apply { moveTo(cx - gw, gy); lineTo(cx, 2.5f * u); lineTo(cx + gw, gy); close() }, color)
        }
        Tool.BRUSH -> {
            drawRect(metal, Offset(cx - half, 12f * u), Size(bodyW, 4f * u))
            val tuft = Path().apply {
                moveTo(cx - half * 0.9f, 12f * u)
                quadraticTo(cx - half * 1.15f, 6f * u, cx, 1.5f * u)
                quadraticTo(cx + half * 1.15f, 6f * u, cx + half * 0.9f, 12f * u)
                close()
            }
            fill(tuft, color)
        }
        Tool.HIGHLIGHTER -> {
            drawRect(metalDark, Offset(cx - half, 13.5f * u), Size(bodyW, 2.5f * u))
            val chisel = Path().apply {
                moveTo(cx - half * 0.6f, 13.5f * u); lineTo(cx - half * 0.6f, 8f * u)
                lineTo(cx + half * 0.6f, 4.5f * u); lineTo(cx + half * 0.6f, 13.5f * u); close()
            }
            fill(chisel, color)
        }
    }
}

/** Slot glyph with the pen-case colours of the current notes theme. */
@Composable
internal fun PenGlyph(pen: PenPreset, modifier: Modifier = Modifier) {
    val c = NotesTokens.colors
    val metal = if (c.dark) Color(0xFF8E9096) else Color(0xFFC9CBD0)
    val metalDark = if (c.dark) Color(0xFF5E6066) else Color(0xFF8E9096)
    Canvas(modifier) { drawPenGlyph(pen.tool, Color(pen.color), c.hairline, metal, metalDark) }
}

/** Drawn width (dp) of a pen's width bar under its slot: grows with the width, but stays a bar. */
internal fun widthBarDp(pen: PenPreset): Float {
    val r = NotesPens.widthRange(pen.tool)
    val t = ((pen.width - r.start) / (r.endInclusive - r.start)).coerceIn(0f, 1f)
    return 1.5f + 4.5f * sqrt(t)
}

// ---- pen editor popover ----

/** Places a popover under its anchor, kept inside the window (above the anchor when there is no room below). */
internal class BelowAnchorPosition(private val gapPx: Int, private val marginPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maxX = max(marginPx, windowSize.width - popupContentSize.width - marginPx)
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(marginPx, maxX)
        var y = anchorBounds.bottom + gapPx
        if (y + popupContentSize.height > windowSize.height - marginPx) {
            val above = anchorBounds.top - gapPx - popupContentSize.height
            y = if (above >= marginPx) above else max(marginPx, windowSize.height - popupContentSize.height - marginPx)
        }
        return IntOffset(x, y)
    }
}

/** Shadow-free popover surface: canvas fill, hairline outline, 24dp corners. */
@Composable
internal fun NotesPopover(onDismiss: () -> Unit, focusable: Boolean = true, content: @Composable () -> Unit) {
    val c = NotesTokens.colors
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pos = remember(density) { BelowAnchorPosition(with(density) { 6.dp.roundToPx() }, with(density) { 8.dp.roundToPx() }) }
    Popup(popupPositionProvider = pos, onDismissRequest = onDismiss, properties = PopupProperties(focusable = focusable)) {
        Box(
            Modifier
                .widthIn(max = 360.dp)
                .clip(NotesShapes.md)
                .background(c.canvas)
                .border(1.dp, c.hairline, NotesShapes.md),
        ) { content() }
    }
}

/**
 * Editor of pen slot [penId]: kind, colour (palette, recent custom colours, HSV picker with hex), width with a live
 * preview stroke, move left / right, duplicate, reset, delete. Every change applies at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PenEditorPopover(
    st: EditorState,
    penId: String,
    recentPen: SnapshotStateList<Int>,
    recentHl: SnapshotStateList<Int>,
    onDismiss: () -> Unit,
) {
    val pen = st.pens.firstOrNull { it.id == penId }
    if (pen == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    val c = NotesTokens.colors
    val type = NotesTokens.type
    var picking by remember { mutableStateOf(false) }
    val index = st.pens.indexOfFirst { it.id == penId }
    fun set(p: PenPreset) = st.updatePens(NotesPens.replace(st.pens, p))

    NotesPopover(onDismiss) {
        Column(
            Modifier.width(340.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(NotesSpace.md),
            verticalArrangement = Arrangement.spacedBy(NotesSpace.sm),
        ) {
            // Header: name, position, reorder, close.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(NotesPens.kindLabel(pen.tool), style = type.title)
                    Text("${index + 1} / ${st.pens.size}번째 펜", style = type.caption)
                }
                NotesIconButton(Icons.Filled.ChevronLeft, "앞으로 옮기기", { st.updatePens(NotesPens.move(st.pens, penId, -1)) }, size = 36.dp, enabled = index > 0)
                NotesIconButton(Icons.Filled.ChevronRight, "뒤로 옮기기", { st.updatePens(NotesPens.move(st.pens, penId, 1)) }, size = 36.dp, enabled = index < st.pens.size - 1)
                NotesIconButton(Icons.Filled.Close, "닫기", onDismiss, size = 36.dp)
            }

            PenPreview(pen, Modifier.fillMaxWidth().height(72.dp))

            NotesSegmented(NotesPens.KINDS, pen.tool, { set(NotesPens.withKind(pen, it)) }, Modifier.fillMaxWidth())

            // Colour.
            SectionLabel("색")
            val palette = NotesPens.paletteFor(pen.tool)
            palette.chunked(8).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { col -> ColorDot(col, pen.color == col) { set(pen.copy(color = col)) } }
                    repeat(8 - row.size) { Spacer(Modifier.size(32.dp)) }
                }
            }
            val recent = if (pen.tool == Tool.HIGHLIGHTER) recentHl else recentPen
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NotesSpace.xxs)) {
                recent.filter { it !in palette }.take(4).forEach { col -> ColorDot(col, pen.color == col) { set(pen.copy(color = col)) } }
                Spacer(Modifier.weight(1f))
                NotesPill(
                    if (picking) "완료" else "직접 고르기",
                    {
                        if (picking && pen.color !in palette) {
                            recent.remove(pen.color); recent.add(0, pen.color)
                            while (recent.size > 8) recent.removeAt(recent.size - 1)
                        }
                        picking = !picking
                    },
                    style = if (picking) PillStyle.Primary else PillStyle.Soft, small = true,
                    icon = if (picking) Icons.Filled.Check else Icons.Filled.Palette,
                )
            }
            if (picking) HsvPicker(pen.color) { set(pen.copy(color = it)) }

            // Width.
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("굵기", Modifier.weight(1f))
                Text("%.1fpt".format(pen.width), style = type.label.copy(color = c.muted))
            }
            val r = NotesPens.widthRange(pen.tool)
            // Square-root scale: fine steps for thin pens.
            val t = sqrt(((pen.width - r.start) / (r.endInclusive - r.start)).coerceIn(0f, 1f))
            Slider(
                value = t,
                onValueChange = { v ->
                    val w = r.start + (r.endInclusive - r.start) * v * v
                    set(pen.copy(width = (Math.round(w * 10f) / 10f).coerceIn(r.start, r.endInclusive)))
                },
                colors = SliderDefaults.colors(
                    thumbColor = c.ink, activeTrackColor = c.ink, inactiveTrackColor = c.field,
                    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                ),
            )

            Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairlineSoft))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NotesSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                NotesPill("복제", {
                    NotesPens.duplicate(st.pens, penId)?.let { (list, copy) -> st.updatePens(list, select = copy.id) }
                }, style = PillStyle.Soft, small = true, icon = Icons.Filled.ContentCopy, enabled = NotesPens.canAdd(st.pens))
                NotesPill("기본값으로", { set(NotesPens.reset(pen)) }, style = PillStyle.Soft, small = true, icon = Icons.Filled.Restore)
                Spacer(Modifier.weight(1f))
                NotesPill("삭제", {
                    val next = NotesPens.selectionAfterRemove(st.pens, penId)
                    st.updatePens(NotesPens.remove(st.pens, penId), select = if (st.penId == penId) next else null)
                    onDismiss()
                }, style = PillStyle.Danger, small = true, enabled = NotesPens.canRemove(st.pens))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = NotesTokens.type.label.copy(color = NotesTokens.colors.muted), modifier = modifier)
}

/** A round colour choice; [selected] = ink ring with a canvas gap. */
@Composable
internal fun ColorDot(color: Int, selected: Boolean, size: androidx.compose.ui.unit.Dp = 32.dp, onClick: () -> Unit) {
    val c = NotesTokens.colors
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .clip(CircleShape)
            .then(if (selected) Modifier.border(2.dp, c.ink, CircleShape) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = colorA11yLabel(color) }
            .padding(if (selected) 4.dp else 3.dp)
            .background(Color(color), CircleShape)
            .border(1.dp, c.hairline.copy(alpha = 0.7f), CircleShape),
    )
}

private fun colorA11yLabel(color: Int) = "색상 #${(color and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()}"

/** The pen's own rendering (PageDraw) of an S-curve with a pressure swell, on a canvasSoft card. */
@Composable
internal fun PenPreview(pen: PenPreset, modifier: Modifier = Modifier) {
    val c = NotesTokens.colors
    Canvas(modifier.clip(NotesShapes.sm).background(c.canvasSoft)) {
        // 1pt = 1.25dp: between the phone (page fit to width) and tablet scales.
        val pxPerPt = 1.25.dp.toPx()
        val wPt = size.width / pxPerPt
        val hPt = size.height / pxPerPt
        val n = 48
        val pts = FloatArray(n * 3)
        val straight = pen.tool == Tool.HIGHLIGHTER
        for (i in 0 until n) {
            val t = i / (n - 1f)
            pts[i * 3] = 14f + (wPt - 28f) * t
            pts[i * 3 + 1] = hPt / 2f + if (straight) 0f else (sin(t * 2 * PI).toFloat() * hPt * 0.22f)
            pts[i * 3 + 2] = (0.15f + 0.85f * sin(t * PI).toFloat().pow(0.7f)).coerceIn(0f, 1f)
        }
        val s = Stroke(0L, pen.tool, pen.color, pen.width, pts)
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            nc.withScale(pxPerPt, pxPerPt) { runCatching { PageDraw.drawStroke(nc, s, cache = false) } }
        }
    }
}

// ---- HSV colour picker ----

/** Saturation / value square, hue bar and hex field. Calls [onChange] with opaque ARGB colours while dragging. */
@Composable
internal fun HsvPicker(color: Int, onChange: (Int) -> Unit) {
    val c = NotesTokens.colors
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    var hex by remember { mutableStateOf(NotesPens.toHex(color)) }
    val emit by rememberUpdatedState(onChange)
    fun current() = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    fun push() { val col = current(); hex = NotesPens.toHex(col); emit(col) }
    // A palette tap (or the hex field) changed the colour from outside: follow it.
    LaunchedEffect(color) {
        if (color != current()) {
            val t = FloatArray(3); android.graphics.Color.colorToHSV(color, t)
            h = t[0]; s = t[1]; v = t[2]
            if (NotesPens.parseHex(hex) != (color or 0xFF000000.toInt())) hex = NotesPens.toHex(color)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(NotesSpace.sm)) {
        val hueColor = Color.hsv(h.coerceIn(0f, 360f), 1f, 1f)
        Canvas(
            Modifier.fillMaxWidth().height(140.dp).clip(NotesShapes.sm)
                .pointerInput(Unit) {
                    fun at(o: Offset) {
                        s = (o.x / size.width).coerceIn(0f, 1f); v = 1f - (o.y / size.height).coerceIn(0f, 1f); push()
                    }
                    detectDragGestures(onDragStart = ::at) { ch, _ -> at(ch.position) }
                }
                .pointerInput(Unit) {
                    detectTapGestures { o -> s = (o.x / size.width).coerceIn(0f, 1f); v = 1f - (o.y / size.height).coerceIn(0f, 1f); push() }
                },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val p = Offset(s * size.width, (1f - v) * size.height)
            drawCircle(Color.White, 9.dp.toPx(), p, style = DrawStroke(2.5.dp.toPx()))
            drawCircle(Color(0x66000000), 10.5.dp.toPx(), p, style = DrawStroke(1f))
        }
        Canvas(
            Modifier.fillMaxWidth().height(24.dp).clip(NotesShapes.full)
                .pointerInput(Unit) {
                    fun at(o: Offset) { h = (o.x / size.width).coerceIn(0f, 1f) * 359.9f; push() }
                    detectDragGestures(onDragStart = ::at) { ch, _ -> at(ch.position) }
                }
                .pointerInput(Unit) { detectTapGestures { o -> h = (o.x / size.width).coerceIn(0f, 1f) * 359.9f; push() } },
        ) {
            drawRect(Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }))
            val x = (h / 360f) * size.width
            drawLine(Color.White, Offset(x, 2f), Offset(x, size.height - 2f), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NotesSpace.xs)) {
            Box(Modifier.size(28.dp).background(Color(current()), CircleShape).border(1.dp, c.hairline, CircleShape))
            val valid = NotesPens.parseHex(hex) != null
            BasicTextField(
                value = hex,
                onValueChange = { txt ->
                    hex = txt.take(7)
                    NotesPens.parseHex(hex)?.let { col ->
                        val t = FloatArray(3); android.graphics.Color.colorToHSV(col, t)
                        h = t[0]; s = t[1]; v = t[2]
                        emit(col)
                    }
                },
                singleLine = true,
                textStyle = NotesTokens.type.bodySm.copy(fontFamily = FontFamily.Monospace, color = if (valid) c.ink else c.danger, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { hex = NotesPens.toHex(current()) }),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.ink),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(
                        Modifier.height(NotesSpace.pillSmall).clip(NotesShapes.full).background(c.field).padding(horizontal = NotesSpace.sm),
                        contentAlignment = Alignment.CenterStart,
                    ) { inner() }
                },
            )
        }
    }
}
