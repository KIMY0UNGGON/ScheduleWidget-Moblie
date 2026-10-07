package com.schedulewidget.mobile.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import java.time.LocalDate
import kotlin.math.sin
import kotlin.math.sqrt

internal const val CALENDAR_FLIP_DURATION_MS = 520
private val CalendarFlipEase = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
private val CalendarFadeOut = CubicBezierEasing(0.4f, 0f, 1f, 1f)
private val CalendarFadeIn = CubicBezierEasing(0f, 0f, 0.2f, 1f)

/** Old/new sheets; header and rings belong outside this wrapper. No bitmap snapshots or extra windows. */
@Composable
internal fun CalendarPageFlip(
    start: LocalDate,
    effect: Int,
    paper: Color,
    aboveRoomPx: Int,
    modifier: Modifier = Modifier,
    content: @Composable (LocalDate) -> Unit,
) {
    val context = LocalContext.current
    val mode = effect.coerceIn(0, 6)
    val time = remember { Animatable(1f) }
    val sheet = rememberGraphicsLayer()
    val backPrint = rememberGraphicsLayer()
    var shown by remember { mutableStateOf(start) }
    var outgoing by remember { mutableStateOf<LocalDate?>(null) }
    var next by remember { mutableStateOf(true) }

    // A new date cancels the preceding turn; the latest requested date always becomes the live page.
    // Data recompositions do not restart a turn. Style/effect changes finish it immediately.
    LaunchedEffect(start, mode, paper) {
        val old = shown
        shown = start
        if (old == start || mode == 0 || !calendarAnimationsEnabled(context)) {
            outgoing = null
            time.snapTo(1f)
        } else {
            next = start > old
            outgoing = old
            time.snapTo(0f)
            time.animateTo(1f, tween(CALENDAR_FLIP_DURATION_MS, easing = LinearEasing))
            outgoing = null
        }
    }

    val turning = Modifier.drawWithContent {
        if (size.width <= 0f || size.height <= 0f) return@drawWithContent
        val t = time.value.coerceIn(0f, 1f)
        val phase = calendarFlipPhase(t, next)
        val odd = mode % 2 == 1
        val fadeShare = if (odd) 0.2f else 0.25f
        val opacity = if (next) {
            1f - CalendarFadeOut.transform(((t - 1f + fadeShare) / fadeShare).coerceIn(0f, 1f))
        } else CalendarFadeIn.transform((t / fadeShare).coerceIn(0f, 1f))
        sheet.rotationX = 0f
        sheet.alpha = 1f
        sheet.record {
            drawRect(paper)
            if (odd && phase * 172f > 90f) {
                drawRect(paper.copy(red = paper.red * 0.86f, green = paper.green * 0.86f, blue = paper.blue * 0.86f))
            } else {
                this@drawWithContent.drawContent()
                if (odd) drawRect(Color.Black, alpha = sin(phase * Math.PI).toFloat() * 0.22f)
            }
        }
        if (odd) {
            sheet.pivotOffset = Offset(size.width / 2f, 0f)
            sheet.cameraDistance = size.height * 5f
            sheet.rotationX = -172f * phase
            sheet.alpha = opacity
            drawBackWithRingLimit(mode, aboveRoomPx) { drawLayer(sheet) }
        } else {
            val fold = calendarFold(size.width, size.height, phase)
            val reach = 4f * (size.width + size.height)
            val flat = fold.halfPlane(false, reach)
            val lifted = fold.halfPlane(true, reach)
            clipPath(flat) { drawLayer(sheet) }
            val foot = fold.normal * fold.distance
            drawRect(
                Brush.linearGradient(
                    0f to Color.Transparent, 0.03f to Color.Black.copy(alpha = 0.33f),
                    0.35f to Color.Black.copy(alpha = 0.12f), 1f to Color.Transparent,
                    start = foot - fold.normal * 0.5f, end = foot + fold.normal * 22f * density,
                ), alpha = opacity,
            )
            drawBackWithRingLimit(mode, aboveRoomPx) {
                withTransform({ transform(fold.reflection()) }) {
                    clipPath(lifted) {
                        drawRect(paper.copy(red = paper.red * 0.95f, green = paper.green * 0.95f, blue = paper.blue * 0.95f), alpha = opacity)
                        backPrint.record { drawLayer(sheet) }
                        backPrint.alpha = 0.08f * opacity
                        drawLayer(backPrint)
                        drawRect(
                            Brush.linearGradient(
                                0f to Color.Black.copy(alpha = 0.25f), 0.1f to Color.White.copy(alpha = 0.28f),
                                0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.086f),
                                start = foot, end = foot + fold.normal * 90f * density,
                            ), alpha = opacity,
                        )
                        val along = Offset(-fold.normal.y, fold.normal.x) * reach
                        drawLine(Color.Black.copy(alpha = 0.22f * opacity), foot - along, foot + along, density)
                    }
                }
            }
        }
    }
    val old = outgoing
    val blocked = if (old == null) Modifier else Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
    // ponytail: turn backs can use measured header space; the parent card clips outside the panel, without another window.
    Box(modifier.then(blocked)) {
        if (old != null && !next) Box(Modifier.matchParentSize().clearAndSetSemantics {}) {
            key(old) { content(old) }
        }
        Box(if (old != null && !next) turning else Modifier) {
            key(shown) { content(shown) }
        }
        if (old != null && next) Box(Modifier.matchParentSize().clearAndSetSemantics {}.then(turning)) {
            key(old) { content(old) }
        }
    }
}

internal fun calendarBackAboveLimitPx(effect: Int, aboveRoomPx: Int): Float {
    val room = aboveRoomPx.coerceAtLeast(0).toFloat()
    return when (effect.coerceIn(0, 6)) {
        1, 2 -> room
        3, 4 -> room / 3f
        else -> 0f
    }
}

private fun DrawScope.drawBackWithRingLimit(effect: Int, aboveRoomPx: Int, content: DrawScope.() -> Unit) {
    val above = calendarBackAboveLimitPx(effect, aboveRoomPx)
    val top = -above
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(0f, top, size.width, size.height), Paint())
    try {
        canvas.clipRect(0f, top, size.width, size.height)
        content()
        if (effect in 3..4 && above > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent, 0.25f to Color.Black.copy(alpha = 0.15625f),
                        0.5f to Color.Black.copy(alpha = 0.5f), 0.75f to Color.Black.copy(alpha = 0.84375f),
                        1f to Color.Black,
                    ),
                    startY = top,
                    endY = top + above * 0.25f,
                ),
                topLeft = Offset(0f, top),
                size = Size(size.width, size.height - top),
                blendMode = BlendMode.DstIn,
            )
        }
    } finally {
        canvas.restore()
    }
}

internal fun calendarFlipPhase(time: Float, next: Boolean): Float =
    CalendarFlipEase.transform(time.coerceIn(0f, 1f)).let { if (next) it else 1f - it }

/** Desktop PeelLine: B climbs the right edge; C moves left along the bottom, then up the left edge. */
internal fun calendarFold(width: Float, height: Float, phase: Float): CalendarFold {
    require(width > 0f && height > 0f)
    val p = phase.coerceIn(0f, 1f)
    if (p <= 0.000001f) {
        val length = sqrt(width * width + height * height)
        val n = Offset(width / length, height / length)
        return CalendarFold(n, n.x * width + n.y * height + 1f)
    }
    val b = Offset(width, height * (1f - p))
    val along = p * (width + height)
    val c = if (along <= width) Offset(width - along, height) else Offset(0f, height - (along - width))
    val d = b - c
    val length = d.getDistance()
    var n = Offset(d.y / length, -d.x / length)
    if (n.x * (width - c.x) + n.y * (height - c.y) < 0f) n = -n
    return CalendarFold(n, n.x * c.x + n.y * c.y)
}

internal data class CalendarFold(val normal: Offset, val distance: Float) {
    fun reflection(): Matrix = Matrix().apply {
        this[0, 0] = 1f - 2f * normal.x * normal.x
        this[0, 1] = -2f * normal.x * normal.y
        this[1, 0] = -2f * normal.x * normal.y
        this[1, 1] = 1f - 2f * normal.y * normal.y
        this[3, 0] = 2f * distance * normal.x
        this[3, 1] = 2f * distance * normal.y
    }

    fun halfPlane(beyond: Boolean, reach: Float): Path {
        val foot = normal * distance
        val along = Offset(-normal.y, normal.x) * reach
        val away = normal * if (beyond) reach else -reach
        return Path().apply {
            moveTo((foot + along).x, (foot + along).y)
            lineTo((foot - along).x, (foot - along).y)
            lineTo((foot - along + away).x, (foot - along + away).y)
            lineTo((foot + along + away).x, (foot + along + away).y)
            close()
        }
    }
}
