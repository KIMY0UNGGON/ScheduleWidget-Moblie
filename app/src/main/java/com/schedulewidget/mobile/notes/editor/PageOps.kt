package com.schedulewidget.mobile.notes.editor

import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.notes.ink.Stroke
import com.schedulewidget.mobile.notes.ink.TextBox

/** Page sizes offered when adding a blank page. */
enum class PageSize(val label: String) {
    SAME("현재 페이지와 같게"), A4_PORTRAIT("A4 세로"), A4_LANDSCAPE("A4 가로"), SQUARE("정사각");

    /** Size in points; [ref] = the page the new one goes next to (for [SAME]). */
    fun size(ref: PageInfo?): Pair<Float, Float> = when (this) {
        SAME -> (ref?.w ?: PageInfo.A4_W) to (ref?.h ?: PageInfo.A4_H)
        A4_PORTRAIT -> PageInfo.A4_W to PageInfo.A4_H
        A4_LANDSCAPE -> PageInfo.A4_H to PageInfo.A4_W
        SQUARE -> PageInfo.A4_W to PageInfo.A4_W
    }
}

/**
 * Pure page-list operations used by the editor (and unit-tested on the JVM): every function returns a new list /
 * value and leaves its inputs alone, so the editor can keep the old list as an undo snapshot.
 */
object PageOps {
    /** [deg] as 0, 90, 180 or 270 (rounded to quarter turns). */
    fun normRot(deg: Int): Int {
        val q = Math.floorMod(Math.round(deg / 90f), 4)
        return q * 90
    }

    fun insert(pages: List<PageInfo>, at: Int, added: List<PageInfo>): List<PageInfo> =
        pages.toMutableList().apply { addAll(at.coerceIn(0, pages.size), added) }

    /** Removes [indices]; null when that would leave no page (a notebook keeps at least one). */
    fun delete(pages: List<PageInfo>, indices: Collection<Int>): List<PageInfo>? {
        val drop = indices.filterTo(HashSet()) { it in pages.indices }
        if (drop.isEmpty()) return pages
        if (drop.size >= pages.size) return null
        return pages.filterIndexed { i, _ -> i !in drop }
    }

    fun move(pages: List<PageInfo>, from: Int, to: Int): List<PageInfo> {
        if (from !in pages.indices || to !in pages.indices || from == to) return pages
        return pages.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Moves [indices] (keeping their order) to the start ([toStart]) or the end. */
    fun moveTo(pages: List<PageInfo>, indices: Collection<Int>, toStart: Boolean): List<PageInfo> {
        val set = indices.filterTo(HashSet()) { it in pages.indices }
        val picked = pages.filterIndexed { i, _ -> i in set }
        val rest = pages.filterIndexed { i, _ -> i !in set }
        return if (toStart) picked + rest else rest + picked
    }

    /** Copies of [indices] (each with a new uid from [newUid]) inserted right after the last of them. */
    fun duplicate(pages: List<PageInfo>, indices: Collection<Int>, newUid: () -> String): Pair<List<PageInfo>, List<Pair<PageInfo, PageInfo>>> {
        val sorted = indices.filter { it in pages.indices }.distinct().sorted()
        if (sorted.isEmpty()) return pages to emptyList()
        val copies = sorted.map { pages[it] to pages[it].copy(uid = newUid()) }
        return insert(pages, sorted.last() + 1, copies.map { it.second }) to copies
    }

    /**
     * [page] turned by [quarterTurns] × 90° clockwise. The shown size swaps on odd turns; a PDF page remembers the
     * turn in [PageInfo.rot] (its source is rendered rotated), a blank page just changes orientation.
     */
    fun rotate(page: PageInfo, quarterTurns: Int): PageInfo {
        val q = Math.floorMod(quarterTurns, 4)
        if (q == 0) return page
        val swap = q % 2 == 1
        val w = if (swap) page.h else page.w
        val h = if (swap) page.w else page.h
        return if (page.isPdf) page.copy(w = w, h = h, rot = normRot(page.rot + q * 90))
        else page.copy(w = w, h = h, rot = 0)
    }

    /** Ink of a [w] × [h] page turned with it by [quarterTurns] × 90° clockwise (text boxes keep reading upright). */
    fun rotateInk(ink: PageInk, w: Float, h: Float, quarterTurns: Int): PageInk {
        val q = Math.floorMod(quarterTurns, 4)
        if (q == 0 || ink.isEmpty) return ink
        val strokes = ink.strokes.map { s -> rotateStroke(s, w, h, q) }
        val texts = ink.texts.map { t -> rotateText(t, w, h, q) }
        return ink.copy(strokes = strokes, texts = texts)
    }

    /** Point ([x], [y]) of a [w] × [h] page after [q] clockwise quarter turns. */
    fun rotatePoint(x: Float, y: Float, w: Float, h: Float, q: Int): Pair<Float, Float> = when (Math.floorMod(q, 4)) {
        1 -> (h - y) to x
        2 -> (w - x) to (h - y)
        3 -> y to (w - x)
        else -> x to y
    }

    private fun rotateStroke(s: Stroke, w: Float, h: Float, q: Int): Stroke {
        val pts = s.pts.copyOf()
        var i = 0
        while (i + 1 < pts.size) {
            val (nx, ny) = rotatePoint(pts[i], pts[i + 1], w, h, q)
            pts[i] = nx; pts[i + 1] = ny
            i += 3
        }
        return s.copy(pts = pts)
    }

    private fun rotateText(t: TextBox, w: Float, h: Float, q: Int): TextBox {
        // Rotate the box centre (size estimated without font metrics) and keep the box upright and on the page.
        val lines = t.text.split('\n')
        val bw = (lines.maxOf { it.length } * t.size * 0.6f).coerceAtLeast(t.size)
        val bh = lines.size * t.size * 1.3f
        val (cx, cy) = rotatePoint(t.x + bw / 2f, t.y + bh / 2f, w, h, q)
        val nw = if (q % 2 == 1) h else w
        val nh = if (q % 2 == 1) w else h
        val x = (cx - bw / 2f).coerceIn(0f, (nw - bw).coerceAtLeast(0f))
        val y = (cy - bh / 2f).coerceIn(0f, (nh - bh).coerceAtLeast(0f))
        return t.copy(x = x, y = y)
    }

    /**
     * Pages chosen by [text] among [count] pages: "" / "전체" / "all" = every page; otherwise comma-separated page
     * numbers and ranges ("1-3,5", "7-" = 7 to the end, "-3" = 1 to 3). Returns 0-based indices in the given order
     * without duplicates, or null when [text] is malformed or names a page that doesn't exist.
     */
    fun parseRange(text: String, count: Int): List<Int>? {
        val t = text.trim().replace('~', '-').replace('–', '-').replace('，', ',')
            .replace(Regex("\\s*([-,])\\s*"), "$1").replace(Regex("\\s+"), ",")
        if (t.isEmpty() || t == "전체" || t.equals("all", ignoreCase = true)) return (0 until count).toList()
        val out = LinkedHashSet<Int>()
        for (part in t.split(',')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val dash = p.indexOf('-')
            if (dash < 0) {
                val n = p.toIntOrNull() ?: return null
                if (n !in 1..count) return null
                out += n - 1
            } else {
                val a = p.substring(0, dash).trim().let { if (it.isEmpty()) 1 else it.toIntOrNull() ?: return null }
                val b = p.substring(dash + 1).trim().let { if (it.isEmpty()) count else it.toIntOrNull() ?: return null }
                if (a !in 1..count || b !in 1..count) return null
                val range = if (a <= b) a..b else a downTo b
                for (n in range) out += n - 1
            }
        }
        return out.toList().ifEmpty { null }
    }
}
