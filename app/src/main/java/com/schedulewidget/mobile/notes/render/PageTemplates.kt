package com.schedulewidget.mobile.notes.render

import android.graphics.Canvas
import android.graphics.Paint
import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.ink.Template
import kotlin.math.max

/**
 * Paper colours and blank-page templates. [draw] handles paper, Cornell and music pages; [drawBasic] handles ruled,
 * grid and dot paper after [draw] returns false.
 */
object PageTemplates {
    const val CORNELL = "cornell"
    const val MUSIC = "music"

    /** Every template offered for blank pages, in picker order. */
    val all: List<String> = Template.all + listOf(CORNELL, MUSIC)

    fun label(t: String): String = when (t) {
        CORNELL -> "코넬"
        MUSIC -> "오선지"
        Template.PLAIN -> "빈 종이"
        else -> Template.label(t)
    }

    // ---- paper ----

    const val PAPER_WHITE = "white"
    const val PAPER_IVORY = "ivory"
    const val PAPER_DARK = "dark"
    val papers = listOf(PAPER_WHITE, PAPER_IVORY, PAPER_DARK)

    fun paperLabel(p: String?): String = when (p) { PAPER_IVORY -> "아이보리"; PAPER_DARK -> "다크"; else -> "흰색" }

    /** ARGB of a paper value (null / unknown = white). */
    fun paperColor(p: String?): Int = when (p) {
        PAPER_IVORY -> 0xFFFBF5E6.toInt()
        PAPER_DARK -> 0xFF26282C.toInt()
        else -> 0xFFFFFFFF.toInt()
    }

    /** Stored value for a paper choice: white is stored as null (the default). */
    fun paperValue(p: String?): String? = p?.takeIf { it != PAPER_WHITE && it in papers }

    fun isDark(p: String?): Boolean = p == PAPER_DARK

    private val paints = ThreadLocal.withInitial { Paint(Paint.ANTI_ALIAS_FLAG) }

    /** See the class comment. Draws in page points. */
    fun draw(c: Canvas, page: PageInfo, pxPerPt: Float): Boolean {
        if (page.isPdf) return false
        val paint = paints.get()!!
        val paper = paperValue(page.paper)
        if (paper != null) {
            paint.style = Paint.Style.FILL
            paint.color = paperColor(paper)
            c.drawRect(0f, 0f, page.w, page.h, paint)
        }
        val dark = isDark(paper)
        return when (page.template) {
            CORNELL -> { drawCornell(c, page, pxPerPt, paint, dark); true }
            MUSIC -> { drawMusic(c, page, pxPerPt, paint, dark); true }
            else -> false
        }
    }

    /** Standard ruled, grid and dot templates, called by [PageDraw] after [draw]. */
    internal fun drawBasic(c: Canvas, page: PageInfo, pxPerPt: Float) {
        if (page.isPdf) return
        val p = paints.get()!!
        val w = page.w; val h = page.h
        val hair = max(0.5f, 0.8f / pxPerPt)
        p.style = Paint.Style.STROKE
        p.strokeWidth = hair
        when (page.template) {
            Template.LINED -> {
                p.color = 0xFFB9C7DA.toInt()
                var y = 72f
                while (y < h - 24f) { c.drawLine(28f, y, w - 28f, y, p); y += 24f }
                p.color = 0xFFF0B4B4.toInt()
                c.drawLine(64f, 36f, 64f, h - 24f, p)
            }
            Template.GRID -> {
                p.color = 0xFFD5DAE1.toInt()
                val step = 14.17f
                var x = step
                while (x < w) { c.drawLine(x, 0f, x, h, p); x += step }
                var y = step
                while (y < h) { c.drawLine(0f, y, w, y, p); y += step }
            }
            Template.DOT -> {
                p.style = Paint.Style.FILL
                p.color = 0xFFAAB2BD.toInt()
                val step = 14.17f
                val r = max(0.6f, 0.9f / pxPerPt)
                var y = step
                while (y < h) {
                    var x = step
                    while (x < w) { c.drawCircle(x, y, r, p); x += step }
                    y += step
                }
            }
        }
    }

    private fun drawCornell(c: Canvas, page: PageInfo, pxPerPt: Float, p: Paint, dark: Boolean) {
        val w = page.w; val h = page.h
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(0.5f, 0.8f / pxPerPt)
        val header = 72f
        val summaryTop = h - h * 0.2f
        val cue = w * 0.3f
        // Ruled lines in the notes and summary areas.
        p.color = if (dark) 0xFF4A5260.toInt() else 0xFFC6D2E2.toInt()
        var y = header + 24f
        while (y < h - 24f) {
            if (y < summaryTop - 4f) c.drawLine(cue + 8f, y, w - 24f, y, p)
            else if (y > summaryTop + 12f) c.drawLine(24f, y, w - 24f, y, p)
            y += 24f
        }
        // Frame: title rule, cue column, summary rule.
        p.color = if (dark) 0xFF7A8597.toInt() else 0xFF8FA3BF.toInt()
        p.strokeWidth = max(0.8f, 1.2f / pxPerPt)
        c.drawLine(24f, header, w - 24f, header, p)
        c.drawLine(cue, header, cue, summaryTop, p)
        c.drawLine(24f, summaryTop, w - 24f, summaryTop, p)
    }

    private fun drawMusic(c: Canvas, page: PageInfo, pxPerPt: Float, p: Paint, dark: Boolean) {
        val w = page.w; val h = page.h
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(0.5f, 0.8f / pxPerPt)
        p.color = if (dark) 0xFF8A93A3.toInt() else 0xFF6F7A8A.toInt()
        val line = 7f          // gap between the five lines of a staff
        val staff = line * 4   // staff height
        val gap = 30f          // space between staves
        val margin = 40f
        var top = 56f
        while (top + staff < h - margin) {
            for (k in 0..4) c.drawLine(margin, top + k * line, w - margin, top + k * line, p)
            c.drawLine(margin, top, margin, top + staff, p)
            c.drawLine(w - margin, top, w - margin, top + staff, p)
            top += staff + gap
        }
    }
}
