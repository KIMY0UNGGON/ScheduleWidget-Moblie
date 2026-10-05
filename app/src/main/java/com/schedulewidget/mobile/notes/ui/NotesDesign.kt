package com.schedulewidget.mobile.notes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.ui.MiniTheme
import com.schedulewidget.mobile.ui.MiniThemes
import com.schedulewidget.mobile.ui.ScheduleColors

// The 노트 design language: a gallery-white, shadow-free system (Light theme = monochrome ink on white, as in the
// reference brief) where structure comes from a tint ladder and 1px hairlines, every interactive element is a
// stadium pill, containers are 24dp / media 16dp rounded. Other app themes (Dark, Blue, Pink, Modern) keep the same
// structure in their own hues: ink, tints and hairlines are derived from the theme's colours.
//
// Every notes screen reads colours from [NotesTokens.colors] and type from [NotesTokens.type]; nothing in notes/*
// hard-codes a colour except handwriting / pen colours.

@Immutable
data class NotesColors(
    val dark: Boolean,
    /** Page / screen background and resting cards. */
    val canvas: Color,
    /** The workhorse tint (6% ink wash): soft pills, segmented tracks, featured cards, tool strip. */
    val canvasSoft: Color,
    /** Input fills (8% ink wash). */
    val field: Color,
    /** Faintest card outline. */
    val hairlineSoft: Color,
    /** Control borders (outlined pills). */
    val hairline: Color,
    /** Primary text, primary pill fill. */
    val ink: Color,
    val inkSoft: Color,
    /** Text on [ink] fills. */
    val onInk: Color,
    val muted: Color,
    val faint: Color,
    /** The one accent: used sparingly (current selection marker, badges that ask for a decision). */
    val accent: Color,
    val onAccent: Color,
    /** Area behind notebook pages in the editor (pages are white paper on it). */
    val desk: Color,
    /** Destructive actions (delete). */
    val danger: Color,
    /** Translucent pill laid over thumbnails (badges). */
    val overlay: Color,
)

@Immutable
data class NotesType(
    val display: TextStyle,
    val h1: TextStyle,
    val h2: TextStyle,
    val h3: TextStyle,
    val title: TextStyle,
    val bodyLg: TextStyle,
    val body: TextStyle,
    val bodySm: TextStyle,
    val link: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
)

object NotesShapes {
    val sm = RoundedCornerShape(16.dp)
    val md = RoundedCornerShape(24.dp)
    val full = RoundedCornerShape(percent = 50)
    /** iOS-style squircle-ish icon tile (30% radius). */
    val squircle = RoundedCornerShape(percent = 30)
}

object NotesSpace {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    /** Fixed height of pill buttons. */
    val pill = 44.dp
    val pillSmall = 36.dp
}

private val LocalNotesColors = staticCompositionLocalOf { notesColors(MiniThemes.all.first()) }
private val LocalNotesType = staticCompositionLocalOf { notesType(notesColors(MiniThemes.all.first())) }

object NotesTokens {
    val colors: NotesColors @Composable get() = LocalNotesColors.current
    val type: NotesType @Composable get() = LocalNotesType.current
}

/** Wraps a notes screen: palette from the app theme picked in the calendar's style dropdown (AppData.miniTheme). */
@Composable
fun NotesTheme(content: @Composable () -> Unit) {
    val data by Repository.get(LocalContext.current).data.collectAsStateWithLifecycle()
    val theme = MiniThemes.of(data.miniTheme)
    val colors = remember(theme) { notesColors(theme) }
    val type = remember(colors) { notesType(colors) }
    CompositionLocalProvider(LocalNotesColors provides colors, LocalNotesType provides type, content = content)
}

/** Palette for an app theme. Light ("paper") is the reference monochrome system; others keep their hues. */
fun notesColors(t: MiniTheme): NotesColors {
    fun wash(ink: Color, over: Color, alpha: Float) = ink.copy(alpha = alpha).compositeOver(over)
    return when (t.id) {
        "paper" -> NotesColors(
            dark = false,
            canvas = Color(0xFFFFFFFF), canvasSoft = Color(0xFFF3F3F3), field = Color(0xFFF0F0F0),
            hairlineSoft = Color(0xFFF0F0F0), hairline = Color(0xFFE0E0E0),
            ink = Color(0xFF141414), inkSoft = Color(0xFF262626), onInk = Color(0xFFFFFFFF),
            muted = Color(0xFF707070), faint = Color(0xFFADADAD),
            accent = Color(0xFF0066FF), onAccent = Color(0xFFFFFFFF),
            desk = Color(0xFFF3F3F3), danger = Color(0xFFD93025), overlay = Color(0x8F737373),
        )
        "modern" -> NotesColors(
            dark = false,
            canvas = Color(0xFFFFFFFF), canvasSoft = Color(0xFFF2F2F2), field = Color(0xFFEDEDED),
            hairlineSoft = Color(0xFFEDEDED), hairline = Color(0xFFD9D9D9),
            ink = Color(0xFF111111), inkSoft = Color(0xFF222222), onInk = Color(0xFFFFFFFF),
            muted = Color(0xFF6B6B6B), faint = Color(0xFFA8A8A8),
            accent = Color(0xFF1A73E8), onAccent = Color(0xFFFFFFFF),
            desk = Color(0xFFEDEDED), danger = Color(0xFFD93025), overlay = Color(0x8F6B6B6B),
        )
        else -> {
            // Same structure from the theme's own colours: canvas = its surface, tints = ink washes over it.
            val canvas = if (t.dark) t.surface else Color.White
            val ink = t.auxInk
            NotesColors(
                dark = t.dark,
                canvas = canvas,
                canvasSoft = if (t.dark) wash(Color.White, canvas, 0.06f) else wash(t.accent, canvas, 0.07f),
                field = if (t.dark) wash(Color.White, canvas, 0.09f) else wash(t.accent, canvas, 0.09f),
                hairlineSoft = if (t.dark) wash(Color.White, canvas, 0.08f) else wash(t.accent, canvas, 0.10f),
                hairline = t.hairline,
                ink = ink,
                inkSoft = wash(canvas, ink, 0.12f),
                onInk = canvas,
                muted = t.auxMuted,
                faint = wash(canvas, t.auxMuted, 0.4f),
                accent = t.accent,
                onAccent = ScheduleColors.readableOn(t.accent),
                desk = if (t.dark) t.canvas else wash(t.accent, Color.White, 0.06f),
                danger = t.holiday,
                overlay = Color(0x8F737373),
            )
        }
    }
}

/** Type scale (system sans at the brief's weights: 650 headings, 450 text, 300 light leads; no tracking). */
fun notesType(c: NotesColors): NotesType {
    val heading = FontWeight(650)
    val text = FontWeight(450)
    fun s(size: Int, w: FontWeight, lh: Float, color: Color = c.ink) =
        TextStyle(fontSize = size.sp, fontWeight = w, lineHeight = (size * lh).sp, letterSpacing = 0.sp, color = color)
    return NotesType(
        display = s(40, heading, 1.0f),
        h1 = s(32, heading, 1.05f),
        h2 = s(26, heading, 1.13f),
        h3 = s(22, heading, 1.13f),
        title = s(18, FontWeight.SemiBold, 1.3f),
        bodyLg = s(17, FontWeight.Light, 1.38f, c.muted),
        body = s(16, text, 1.38f),
        bodySm = s(14, text, 1.43f),
        link = s(15, FontWeight.SemiBold, 1.38f),
        label = s(12, FontWeight.SemiBold, 1.33f),
        caption = s(12, text, 1.33f, c.muted),
    )
}

// ---- base components (shadow-free; pills everywhere) ----

enum class PillStyle { Primary, Outline, Soft, Danger }

/** Stadium pill button: Primary = ink fill, Outline = hairline border on canvas, Soft = canvasSoft fill. */
@Composable
fun NotesPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    small: Boolean = false,
) {
    val c = NotesTokens.colors
    val (bg, fg) = when (style) {
        PillStyle.Primary -> c.ink to c.onInk
        PillStyle.Outline -> c.canvas to c.ink
        PillStyle.Soft -> c.canvasSoft to c.ink
        PillStyle.Danger -> c.canvasSoft to c.danger
    }
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .height(if (small) NotesSpace.pillSmall else NotesSpace.pill)
            .clip(NotesShapes.full)
            .background(bg)
            .then(if (style == PillStyle.Outline) Modifier.border(1.dp, c.hairline, NotesShapes.full) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (small) NotesSpace.sm else NotesSpace.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val tint = if (enabled) fg else fg.copy(alpha = 0.4f)
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(if (small) 16.dp else 18.dp))
            if (text.isNotEmpty()) Box(Modifier.size(NotesSpace.xs))
        }
        if (text.isNotEmpty()) Text(text, style = NotesTokens.type.link.copy(color = tint, fontSize = if (small) 14.sp else 15.sp))
    }
}

/** Round icon button: transparent at rest, [selected] = canvasSoft fill (or ink fill when [strong]). */
@Composable
fun NotesIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    strong: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    tint: Color? = null,
) {
    val c = NotesTokens.colors
    val bg = when {
        selected && strong -> c.ink
        selected -> c.canvasSoft
        else -> Color.Transparent
    }
    val fg = tint ?: if (selected && strong) c.onInk else c.ink
    Box(
        modifier.minimumInteractiveComponentSize().size(size).clip(NotesShapes.full).background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (enabled) fg else c.faint, modifier = Modifier.size(size * 0.55f))
    }
}

/** Segmented control: canvasSoft stadium track, the active option a white (canvas) pill. */
@Composable
fun <T> NotesSegmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = NotesTokens.colors
    Row(modifier.clip(NotesShapes.full).background(c.canvasSoft).padding(NotesSpace.xxs)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(NotesShapes.full)
                    .background(if (on) c.canvas else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(value) }
                    .padding(horizontal = NotesSpace.sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = NotesTokens.type.label.copy(fontSize = 13.sp, color = if (on) c.ink else c.muted))
            }
        }
    }
}

/** Container: canvas fill + soft hairline (resting) or canvasSoft fill without border ([featured]). 24dp corners. */
@Composable
fun NotesCard(
    modifier: Modifier = Modifier,
    featured: Boolean = false,
    shape: Shape = NotesShapes.md,
    padding: PaddingValues = PaddingValues(NotesSpace.lg),
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val c = NotesTokens.colors
    Box(
        modifier
            .clip(shape)
            .background(if (featured) c.canvasSoft else c.canvas)
            .then(if (featured) Modifier else Modifier.border(1.dp, c.hairlineSoft, shape))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(padding),
    ) { content() }
}

/** Small stadium chip (badges, filters). [overlay] = translucent gray over thumbnails. */
@Composable
fun NotesChip(text: String, modifier: Modifier = Modifier, selected: Boolean = false, overlay: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = NotesTokens.colors
    val bg = when {
        overlay -> c.overlay
        selected -> c.ink
        else -> c.canvasSoft
    }
    val fg = if (overlay) Color.White else if (selected) c.onInk else c.ink
    Box(
        modifier
            .clip(NotesShapes.full)
            .background(bg)
            .then(
                if (onClick != null) {
                    Modifier.minimumInteractiveComponentSize().selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = onClick,
                    )
                } else Modifier,
            )
            .padding(horizontal = NotesSpace.sm, vertical = 6.dp),
    ) { Text(text, style = NotesTokens.type.label.copy(color = fg)) }
}

/** Top bar row: transparent on canvas, no shadow/divider. */
@Composable
fun NotesTopBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.background(NotesTokens.colors.canvas).padding(horizontal = NotesSpace.xs, vertical = NotesSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
