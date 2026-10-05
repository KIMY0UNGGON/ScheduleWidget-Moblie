package com.schedulewidget.mobile.notes.editor

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

internal fun EditorState.tileRequestsFeature(renderer: PageRenderer): List<PageRenderer.TileRequest> {
        val l = layout()
        val out = ArrayList<PageRenderer.TileRequest>()
        for (i in visibleRange()) {
            val p = note.pages[i]
            if (!p.isPdf) continue
            val shown = l.fitW * scale
            if (shown <= renderer.baseWidthFor(p, l.fitW, shown) * 1.1f) continue
            val left = max(0f, toPageX(i, 0f)); val top = max(0f, toPageY(i, 0f))
            val right = min(p.w, toPageX(i, viewW)); val bottom = min(p.h, toPageY(i, viewH))
            if (right <= left || bottom <= top) continue
            out += PageRenderer.TileRequest(p, RectF(left, top, right, bottom), pxPerPt(i))
        }
        return out
    }
