package com.schedulewidget.mobile.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.Repository

/** Like the desktop, every screen follows the theme picked in the mini calendar's style dropdown. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val data by Repository.get(LocalContext.current).data.collectAsStateWithLifecycle()
    val t = MiniThemes.of(data.miniTheme)
    val activity = LocalActivity.current
    val view = LocalView.current
    LaunchedEffect(activity, t.dark) {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !t.dark
                isAppearanceLightNavigationBars = !t.dark
            }
        }
    }
    // Remembered per theme: a fresh ColorScheme on every Repository write (pet double tap, pet drag, volume…) swaps
    // the static LocalColorScheme and recomposes the whole app each time.
    val scheme = remember(t) { schemeFor(t) }
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun schemeFor(t: MiniTheme): ColorScheme {
    val neutralChrome = t.id == "paper" || t.id == "modern"
    val primary = if (neutralChrome) t.auxInk else t.accent
    val onPrimary = ScheduleColors.readableOn(primary)
    val container = if (neutralChrome) Color(0xFFF3F3F3) else t.hover
    return if (t.dark) darkColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = container, onPrimaryContainer = t.auxInk,
        inversePrimary = primary,
        secondary = primary, onSecondary = onPrimary,
        secondaryContainer = container, onSecondaryContainer = t.auxInk,
        tertiary = primary, onTertiary = onPrimary,
        tertiaryContainer = container, onTertiaryContainer = t.auxInk,
        background = t.canvas, onBackground = t.auxInk,
        surface = t.surface, onSurface = t.auxInk,
        surfaceVariant = t.header, onSurfaceVariant = t.auxMuted,
        surfaceContainer = t.canvas, surfaceContainerLow = t.surface, surfaceContainerHigh = t.day,
        outline = t.frame, outlineVariant = t.hairline, surfaceTint = primary,
    ) else lightColorScheme(
        primary = primary, onPrimary = onPrimary,
        primaryContainer = container, onPrimaryContainer = t.auxInk,
        inversePrimary = primary,
        secondary = primary, onSecondary = onPrimary,
        secondaryContainer = container, onSecondaryContainer = t.auxInk,
        tertiary = primary, onTertiary = onPrimary,
        tertiaryContainer = container, onTertiaryContainer = t.auxInk,
        background = t.canvas, onBackground = t.auxInk,
        surface = t.surface, onSurface = t.auxInk,
        surfaceVariant = t.header, onSurfaceVariant = t.auxMuted,
        surfaceContainer = t.canvas, surfaceContainerLow = t.surface, surfaceContainerHigh = t.day,
        outline = t.muted, outlineVariant = t.hairline, surfaceTint = primary,
    )
}
