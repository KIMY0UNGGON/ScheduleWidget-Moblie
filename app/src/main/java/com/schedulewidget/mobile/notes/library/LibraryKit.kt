package com.schedulewidget.mobile.notes.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.notes.ink.Template
import com.schedulewidget.mobile.notes.ui.NotesShapes
import com.schedulewidget.mobile.notes.ui.NotesSpace
import com.schedulewidget.mobile.notes.ui.NotesTokens

// Library-private pieces built on the shared notes design language (notes/ui/NotesDesign.kt): Material3 menus, dialogs,
// sheets and switches recoloured to the tokens and stripped of elevation, plus a field-tint input and a shadow-free
// snackbar. Kept here (not in NotesDesign.kt) because only the library and its settings card use them.

/** Paper behind page thumbnails and template previews: pages are always white paper, like in the editor. */
internal val PaperColor = Color.White

/** Template rules drawn on the white paper previews (a page-content colour, independent of the app theme). */
private val PaperRule = Color(0xFFBDBDBD)

/** Dropdown menu: canvas background, 16dp corners, hairline border, no tonal or shadow elevation. */
@Composable
internal fun NotesMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = NotesTokens.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = NotesShapes.sm,
        containerColor = c.canvas,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, c.hairline),
        content = content,
    )
}

/** One menu row: body text, optional leading icon, [danger] in the danger colour, [checked] shows a check mark. */
@Composable
internal fun NotesMenuItem(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    danger: Boolean = false,
    checked: Boolean = false,
) {
    val c = NotesTokens.colors
    val fg = if (danger) c.danger else c.ink
    DropdownMenuItem(
        text = { Text(text, style = NotesTokens.type.body.copy(color = fg)) },
        onClick = onClick,
        leadingIcon = icon?.let { { Icon(it, null, modifier = Modifier.size(20.dp)) } },
        trailingIcon = if (checked) {
            { Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp)) }
        } else null,
        colors = MenuDefaults.itemColors(
            textColor = fg, leadingIconColor = if (danger) c.danger else c.muted, trailingIconColor = c.ink,
        ),
        modifier = Modifier.padding(horizontal = 6.dp).clip(NotesShapes.full),
    )
}

/** Alert dialog on canvas with 24dp corners, an h3 title and no tonal elevation. Buttons are [NotesPill]s. */
@Composable
internal fun NotesDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val c = NotesTokens.colors
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        title = { Text(title, style = NotesTokens.type.h3) },
        text = content,
        shape = NotesShapes.md,
        containerColor = c.canvas,
        titleContentColor = c.ink,
        textContentColor = c.ink,
        tonalElevation = 0.dp,
    )
}

/** Bottom sheet: canvas, 24dp top corners, a short hairline handle, no tonal elevation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotesSheet(onDismissRequest: () -> Unit, sheetState: SheetState, content: @Composable ColumnScope.() -> Unit) {
    val c = NotesTokens.colors
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = c.canvas,
        contentColor = c.ink,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(Modifier.padding(top = NotesSpace.sm, bottom = NotesSpace.xs).size(width = 36.dp, height = 4.dp).clip(NotesShapes.full).background(c.hairline))
        },
        content = content,
    )
}

/**
 * Text input: field-tint fill, no border at rest, a 2px ink ring when focused. [pill] = stadium (search, names);
 * [autoFocus] puts the cursor in it when it first appears.
 */
@Composable
internal fun NotesField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    autoFocus: Boolean = false,
) {
    val c = NotesTokens.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = NotesTokens.type.body,
        cursorBrush = SolidColor(c.ink),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        modifier = modifier.focusRequester(focus),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(NotesShapes.full)
                    .background(c.field)
                    .then(if (focused) Modifier.border(2.dp, c.ink, NotesShapes.full) else Modifier)
                    .padding(start = if (leadingIcon != null) 14.dp else 18.dp, end = if (trailing != null) 4.dp else 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    Icon(leadingIcon, null, tint = c.muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = NotesTokens.type.body.copy(color = c.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
                trailing?.invoke()
            }
        },
    )
}

/** Small caption-weight label above a field or option group. */
@Composable
internal fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = NotesTokens.type.label.copy(color = NotesTokens.colors.muted), modifier = modifier.padding(bottom = NotesSpace.xs))
}

/** iOS-style icon tile: squircle, tint fill, ink icon. */
@Composable
internal fun SquircleTile(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 44.dp, background: Color? = null) {
    val c = NotesTokens.colors
    Box(modifier.size(size).clip(NotesShapes.squircle).background(background ?: c.canvasSoft), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = c.ink, modifier = Modifier.size(size * 0.5f))
    }
}

/** Thin indeterminate ink progress bar on a hairline track. */
@Composable
internal fun ThinProgress(modifier: Modifier = Modifier) {
    val c = NotesTokens.colors
    LinearProgressIndicator(
        modifier = modifier.fillMaxWidth().height(3.dp).clip(NotesShapes.full),
        color = c.ink,
        trackColor = c.hairline,
        strokeCap = StrokeCap.Round,
    )
}

/** Switch in the tokens: ink track when on, field tint when off, no outline. */
@Composable
internal fun notesSwitchColors(): SwitchColors {
    val c = NotesTokens.colors
    return SwitchDefaults.colors(
        checkedThumbColor = c.onInk, checkedTrackColor = c.ink, checkedBorderColor = c.ink, checkedIconColor = c.ink,
        uncheckedThumbColor = c.muted, uncheckedTrackColor = c.field, uncheckedBorderColor = c.hairline, uncheckedIconColor = c.field,
    )
}

/** Title + subtitle row with a trailing switch; the whole row toggles. */
@Composable
internal fun NotesSwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = NotesTokens.colors
    Row(
        modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = NotesSpace.md, vertical = NotesSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = NotesSpace.md)) {
            Text(title, style = NotesTokens.type.body)
            if (subtitle != null) Text(subtitle, style = NotesTokens.type.bodySm.copy(color = c.muted), modifier = Modifier.padding(top = 2.dp))
        }
        Switch(checked = checked, onCheckedChange = null, colors = notesSwitchColors())
    }
}

/** Snackbar host whose snackbars are flat ink cards (Material's snackbar casts a shadow). */
@Composable
internal fun NotesSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data -> NotesSnackbar(data) }
}

@Composable
private fun NotesSnackbar(data: SnackbarData) {
    val c = NotesTokens.colors
    Row(
        Modifier
            .padding(horizontal = NotesSpace.md, vertical = NotesSpace.xs)
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(NotesShapes.sm)
            .background(c.ink)
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(data.visuals.message, style = NotesTokens.type.bodySm.copy(color = c.onInk), modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        data.visuals.actionLabel?.let { label ->
            Box(
                Modifier.padding(start = NotesSpace.xs).minimumInteractiveComponentSize().height(NotesSpace.pillSmall)
                    .clip(NotesShapes.full).clickable(role = Role.Button) { data.performAction() }.padding(horizontal = NotesSpace.sm),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = NotesTokens.type.link.copy(color = c.onInk)) }
        }
    }
}

/** A tiny page with the template's rules (무지 / 줄 / 모눈 / 점), as the blank page will look. */
@Composable
internal fun TemplatePreview(template: String, landscape: Boolean, selected: Boolean, modifier: Modifier = Modifier) {
    val c = NotesTokens.colors
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .aspectRatio(if (landscape) 1.414f else 0.707f)
            .clip(shape)
            .background(PaperColor)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.ink else c.hairline, shape),
    ) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp)) {
            val step = size.minDimension / 6f
            val stroke = 1.dp.toPx()
            when (template) {
                Template.LINED -> {
                    var y = step
                    while (y < size.height) { drawLine(PaperRule, Offset(0f, y), Offset(size.width, y), stroke); y += step }
                }
                Template.GRID -> {
                    var y = 0f
                    while (y <= size.height) { drawLine(PaperRule, Offset(0f, y), Offset(size.width, y), stroke * 0.8f); y += step }
                    var x = 0f
                    while (x <= size.width) { drawLine(PaperRule, Offset(x, 0f), Offset(x, size.height), stroke * 0.8f); x += step }
                }
                Template.DOT -> {
                    var y = step / 2
                    while (y < size.height) {
                        var x = step / 2
                        while (x < size.width) { drawCircle(PaperRule, radius = stroke * 1.1f, center = Offset(x, y)); x += step }
                        y += step
                    }
                }
                else -> Unit
            }
        }
    }
}

/** Equal-width option tiles in a row (template picker). */
@Composable
internal fun <T> TileRow(options: List<T>, modifier: Modifier = Modifier, tile: @Composable (T, Modifier) -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { tile(it, Modifier.weight(1f)) }
    }
}
