package com.schedulewidget.mobile.notes.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.notes.ui.NotesPill
import com.schedulewidget.mobile.notes.ui.PillStyle
import kotlin.math.roundToInt

/** The two canvas layers redraw independently: committed page content and the active gesture overlay. */
@Composable
internal fun EditorCanvas(
    st: EditorState,
    renderer: PageRenderer,
    background: Int,
    pageAreaWidthPx: Int,
) {
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        st.inkVersion
        renderer.version
        drawIntoCanvas { EditorDraw.drawPages(it.nativeCanvas, st, renderer, background) }
    }
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        st.liveVersion
        drawIntoCanvas { EditorDraw.drawOverlay(it.nativeCanvas, st) }
    }
    PageEndAdd(st)
    SelectionBar(st, maxWidthPx = pageAreaWidthPx)
}

/** Adds a page from the affordance beneath the last sheet of paper. */
@Composable
internal fun PageEndAdd(st: EditorState) {
    val pages = st.note.pages
    if (pages.isEmpty() || st.viewW <= 0f) return
    val l = st.layout()
    val last = pages.lastIndex
    val s = st.scale
    val y = (l.tops[last] + l.heights[last] + l.margin - st.oy) * s
    if (y > st.viewH || y + st.endSpace * s < 0f) return
    val density = LocalDensity.current
    val cx = (l.margin + l.fitW / 2f - st.ox) * s
    val wPx = with(density) { 132.dp.toPx() }
    Box(Modifier.offset { IntOffset((cx - wPx / 2f).roundToInt(), y.roundToInt()) }) {
        NotesPill(
            "페이지", onClick = st::addPageAtEnd, style = PillStyle.Outline, icon = Icons.Filled.Add, small = true,
            modifier = Modifier.width(132.dp),
        )
    }
}
