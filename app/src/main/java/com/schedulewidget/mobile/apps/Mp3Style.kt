package com.schedulewidget.mobile.apps

import android.annotation.SuppressLint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.ui.MiniThemes
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Design tokens for the MP3 module (Apple-web inspired: one accent, hairlines, no shadows). */
@Immutable
data class Mp3Palette(
    val dark: Boolean,
    val accent: Color,
    val ink: Color,
    val muted: Color,
    val secondary: Color,
    val card: Color,
    val panel: Color,
    val hairline: Color,
    val chip: Color,
    val track: Color,
    val fill: Color,
)

object Mp3Style {
    val Light = Mp3Palette(
        dark = false,
        accent = Color(0xFF0066CC),
        ink = Color(0xFF1D1D1F),
        muted = Color(0xFF7A7A7A),
        secondary = Color(0xFF333333),
        card = Color(0xFFF5F5F7),
        panel = Color(0xFFFFFFFF),
        hairline = Color(0xFFE0E0E0),
        chip = Color(0xA3D2D2D7),
        track = Color(0xFFD2D2D7),
        fill = Color(0xFF1D1D1F),
    )
    val Dark = Mp3Palette(
        dark = true,
        accent = Color(0xFF2997FF),
        ink = Color(0xFFFFFFFF),
        muted = Color(0xFF98989D),
        secondary = Color(0xFFD2D2D7),
        card = Color(0xFF272729),
        panel = Color(0xFF272729),
        hairline = Color(0x24FFFFFF),
        chip = Color(0x3DFFFFFF),
        track = Color(0xFF48484A),
        fill = Color(0xFFFFFFFF),
    )

    val title = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.374).sp)
    val body = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.374).sp)
    val caption = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.224).sp)
    val fine = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.12).sp)
    val header = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.28).sp)
}

/** Palette that follows the current mini theme (dark theme -> dark tiles). */
@Composable
@SuppressLint("StateFlowValueCalledInComposition") // only the starting value; the flow keeps it current
fun rememberMp3Palette(): Mp3Palette {
    val ctx = LocalContext.current
    // Only the dark flag matters: don't recompose the player on every unrelated AppData change.
    val repo = remember { Repository.get(ctx) }
    val dark by remember(repo) { repo.data.map { MiniThemes.of(it.miniTheme).dark }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(MiniThemes.of(repo.data.value.miniTheme).dark)
    return if (dark) Mp3Style.Dark else Mp3Style.Light
}

/** Clickable with the 0.95 press-scale micro-interaction and no ripple. */
fun Modifier.pressable(enabled: Boolean = true, role: Role? = Role.Button, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    this
        .graphicsLayer {
            val s = if (pressed) 0.95f else 1f
            scaleX = s
            scaleY = s
        }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
}
