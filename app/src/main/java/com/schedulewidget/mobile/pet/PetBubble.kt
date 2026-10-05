package com.schedulewidget.mobile.pet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import com.schedulewidget.mobile.apps.Mp3Palette
import kotlin.math.roundToInt

/** Where the bubble tail points and how much vertical room its pager has beside the pet. */
data class BubbleTail(val up: Boolean, val x: Int)

val LocalBubbleTail = compositionLocalOf<BubbleTail?> { null }
val LocalBubbleRoom = compositionLocalOf<Dp?> { null }

internal data class BubbleEdge(val color: Color, val width: Dp)

/** Positions the in-app bubble beside its pet and supplies the tail and available room. */
internal class BesidePet : PopupPositionProvider {
    var tail by mutableStateOf<BubbleTail?>(null)
    var room by mutableStateOf<Int?>(null)

    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val above = anchorBounds.top - popupContentSize.height
        val below = above < 0
        val y = if (!below) above else anchorBounds.bottom.coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
        val nextTail = BubbleTail(up = below, x = anchorBounds.center.x - x)
        if (nextTail != tail) tail = nextTail
        val nextRoom = maxOf(anchorBounds.top, windowSize.height - anchorBounds.bottom)
        if (nextRoom != room) room = nextRoom
        return IntOffset(x, y)
    }
}

@Composable
internal fun TailRow(p: Mp3Palette, tail: BubbleTail?, up: Boolean, edge: BubbleEdge) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth.toFloat()
        val tailWidth = with(density) { 20.dp.toPx() }
        val inset = with(density) { 8.dp.toPx() }
        val margin = with(density) { 24.dp.toPx() }
        val center = tail?.let { (it.x - inset).coerceIn(margin, (width - margin).coerceAtLeast(margin)) } ?: width / 2f
        BubbleTailShape(p, up, edge, Modifier.offset { IntOffset((center - tailWidth / 2f).roundToInt(), 0) })
    }
}

@Composable
private fun BubbleTailShape(p: Mp3Palette, up: Boolean, edge: BubbleEdge, modifier: Modifier) {
    Canvas(modifier.size(width = 20.dp, height = 10.dp)) {
        val w = size.width
        val h = size.height
        fun y(v: Float) = if (up) h - v else v
        val tail = Path().apply {
            moveTo(0f, y(-1f))
            cubicTo(w * 0.30f, y(0f), w * 0.42f, y(h * 0.85f), w / 2, y(h))
            cubicTo(w * 0.58f, y(h * 0.85f), w * 0.70f, y(0f), w, y(-1f))
            close()
        }
        drawPath(tail, p.panel)
        drawPath(tail, edge.color, style = Stroke(width = edge.width.toPx()))
        val seam = 1.5.dp.toPx()
        drawRect(p.panel, topLeft = Offset(1.dp.toPx(), if (up) h + 1f - seam else -1f), size = Size(w - 2.dp.toPx(), seam))
    }
}
