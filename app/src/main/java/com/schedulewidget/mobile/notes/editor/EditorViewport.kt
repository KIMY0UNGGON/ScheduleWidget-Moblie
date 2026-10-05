package com.schedulewidget.mobile.notes.editor

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max

internal fun EditorState.layoutFeature(): EditorState.Layout {
        val pages = note.pages
        val w = viewW
        val key = pages to w
        layoutCache?.let { if (layoutKey?.first === pages && layoutKey?.second == w) return it }
        val margin = 12f * density
        val gap = 12f * density
        val fitW = max(1f, w - 2 * margin)
        val tops = FloatArray(pages.size)
        val heights = FloatArray(pages.size)
        var y = margin
        pages.forEachIndexed { i, p ->
            tops[i] = y
            heights[i] = fitW * p.h / p.w
            y += heights[i] + gap
        }
        val l = EditorState.Layout(margin, fitW, tops, heights, y - gap + margin + endSpace, w)
        layoutCache = l
        layoutKey = key
        return l
    }

    /** Points → content pixels at zoom 1 for page [i]. */
internal fun EditorState.kFeature(i: Int): Float = layout().fitW / note.pages[i].w
internal fun EditorState.pxPerPtFeature(i: Int): Float = k(i) * scale

internal fun EditorState.setViewportFeature(w: Float, h: Float) {
        if (w == viewW && h == viewH) return
        val first = viewW == 0f
        // Keep the page at the top of the view in place when the size changes (rotation, split screen).
        val anchor = if (first) -1 else pageAtContentYFeature(oy + 1f)
        val within = if (anchor >= 0) (oy - layout().tops[anchor]) / layout().heights[anchor] else 0f
        viewW = w; viewH = h
        if (anchor >= 0) {
            val l = layout()
            oy = l.tops[anchor] + within * l.heights[anchor]
        }
        clampViewportFeature()
    }

internal fun EditorState.pageAtContentYFeature(cy: Float): Int {
        val l = layout()
        if (l.tops.isEmpty()) return -1
        var lo = 0; var hi = l.tops.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (l.tops[mid] <= cy) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Index of the page under the screen point, or -1 in a gap / outside. */
internal fun EditorState.pageAtFeature(sx: Float, sy: Float, slackPx: Float = 0f): Int {
        val l = layout()
        val cx = sx / scale + ox; val cy = sy / scale + oy
        val i = pageAtContentYFeature(cy)
        if (i < 0) return -1
        val slack = slackPx / scale
        if (cx < l.margin - slack || cx > l.margin + l.fitW + slack) return -1
        if (cy < l.tops[i] - slack || cy > l.tops[i] + l.heights[i] + slack) {
            // Maybe within the slack above the next page.
            val n = i + 1
            if (n < l.tops.size && cy >= l.tops[n] - slack) return n
            return -1
        }
        return i
    }

internal fun EditorState.toPageXFeature(i: Int, sx: Float): Float = (sx / scale + ox - layout().margin) / k(i)
internal fun EditorState.toPageYFeature(i: Int, sy: Float): Float = (sy / scale + oy - layout().tops[i]) / k(i)
internal fun EditorState.toScreenXFeature(i: Int, px: Float): Float = (layout().margin + px * k(i) - ox) * scale
internal fun EditorState.toScreenYFeature(i: Int, py: Float): Float = (layout().tops[i] + py * k(i) - oy) * scale

    /** Pages intersecting the view. */
internal fun EditorState.visibleRangeFeature(): IntRange {
        val l = layout()
        if (l.tops.isEmpty() || viewH <= 0f) return IntRange.EMPTY
        val top = oy; val bottom = oy + viewH / scale
        var first = pageAtContentYFeature(top)
        if (first < 0) first = 0
        if (l.tops[first] + l.heights[first] < top && first + 1 < l.tops.size) first++
        var last = first
        while (last + 1 < l.tops.size && l.tops[last + 1] <= bottom) last++
        return first..last
    }

internal fun EditorState.clampViewportFeature() {
        val l = layout()
        val visW = viewW / scale; val visH = viewH / scale
        ox = if (l.contentW <= visW) (l.contentW - visW) / 2f else ox.coerceIn(0f, l.contentW - visW)
        oy = if (l.totalH <= visH) (l.totalH - visH) / 2f else oy.coerceIn(0f, l.totalH - visH)
    }

internal fun EditorState.panByFeature(dx: Float, dy: Float) {
        ox -= dx / scale
        oy -= dy / scale
        clampViewportFeature()
    }

internal fun EditorState.zoomByFeature(factor: Float, fx: Float, fy: Float) {
        val s2 = (scale * factor).coerceIn(EditorState.MIN_ZOOM, EditorState.MAX_ZOOM)
        if (s2 == scale) return
        val cx = fx / scale + ox; val cy = fy / scale + oy
        scale = s2
        ox = cx - fx / s2
        oy = cy - fy / s2
        clampViewportFeature()
    }

internal fun EditorState.goToPageFeature(i: Int) {
        val l = layout()
        if (i !in l.tops.indices) return
        flingJob?.cancel()
        oy = l.tops[i] - l.margin
        clampViewportFeature()
    }

internal fun EditorState.stopFlingFeature() { flingJob?.cancel(); flingJob = null }

internal fun EditorState.flingFeature(vx: Float, vy: Float, decay: DecayAnimationSpec<Offset>) {
        stopFling()
        flingJob = scope.launch {
            var last = Offset.Zero
            AnimationState(Offset.VectorConverter, Offset.Zero, Offset(vx, vy)).animateDecay(decay) {
                val d = value - last
                last = value
                val beforeX = ox; val beforeY = oy
                panBy(d.x, d.y)
                if (beforeX == ox && beforeY == oy) cancelAnimation()
            }
        }
    }
