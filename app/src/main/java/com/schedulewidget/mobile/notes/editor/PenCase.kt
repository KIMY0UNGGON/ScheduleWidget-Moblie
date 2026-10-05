package com.schedulewidget.mobile.notes.editor

import com.schedulewidget.mobile.data.PenPreset
import com.schedulewidget.mobile.notes.ink.Tool

// The pen case (Flexcil-style pen slots): pure list logic, no Android or Compose types, so it is unit-tested on
// the JVM. Every operation returns a new list and never breaks the invariants: 1..MAX_SLOTS slots, unique non-blank
// ids, a known kind, an opaque colour and a width inside the kind's range.

/** Default pens, palettes and the list operations of the editor's pen case. */
object NotesPens {
    const val MAX_SLOTS = 12

    /** Kinds in the order of the pen editor's segmented control, with their Korean names. */
    val KINDS: List<Pair<Int, String>> = listOf(
        Tool.PEN to "만년필",
        Tool.BALLPOINT to "볼펜",
        Tool.PENCIL to "연필",
        Tool.BRUSH to "붓펜",
        Tool.HIGHLIGHTER to "형광펜",
    )

    fun kindLabel(tool: Int): String = KINDS.firstOrNull { it.first == tool }?.second ?: "펜"

    fun isKnownKind(tool: Int): Boolean = KINDS.any { it.first == tool }

    // Colours (ARGB). Ink black matches the notes design ink.
    const val BLACK = 0xFF141414.toInt()
    const val BLUE = 0xFF1E5BD8.toInt()
    const val RED = 0xFFE53935.toInt()
    const val GRAPHITE = 0xFF5F6368.toInt()
    const val HL_YELLOW = 0xFFFFE34D.toInt()
    const val HL_GREEN = 0xFF8BE36B.toInt()

    /** Writing colours: neutrals first, then hues. */
    val PALETTE: List<Int> = listOf(
        BLACK, 0xFF4A4A4A.toInt(), 0xFF8A8A8A.toInt(), 0xFFFFFFFF.toInt(),
        BLUE, 0xFF0B2E6B.toInt(), 0xFF00A3D7.toInt(), 0xFF00897B.toInt(),
        0xFF2E9E44.toInt(), RED, 0xFFE91E63.toInt(), 0xFFFF7A00.toInt(),
        0xFFF2B705.toInt(), 0xFF7B3FD1.toInt(), 0xFF7A4A2A.toInt(), 0xFFB08D57.toInt(),
    )

    /** Highlighter colours (drawn translucent by the renderer). */
    val HL_PALETTE: List<Int> = listOf(
        HL_YELLOW, HL_GREEN, 0xFFFF8AB8.toInt(), 0xFF7FD3FF.toInt(),
        0xFFFFB25B.toInt(), 0xFFC6A4FF.toInt(), 0xFF6EE7D2.toInt(), 0xFFBDBDBD.toInt(),
    )

    fun paletteFor(tool: Int): List<Int> = if (tool == Tool.HIGHLIGHTER) HL_PALETTE else PALETTE

    /** Width range in page points for a kind (the editor's slider). */
    fun widthRange(tool: Int): ClosedFloatingPointRange<Float> = when (tool) {
        Tool.HIGHLIGHTER -> 4f..30f
        Tool.BRUSH -> 0.4f..6f
        else -> 0.3f..8f
    }

    /** Width a new pen of this kind starts with (same scale as EditorState.PEN_WIDTHS / HL_WIDTHS). */
    fun defaultWidth(tool: Int): Float = when (tool) {
        Tool.HIGHLIGHTER -> 14f
        Tool.BRUSH -> 1.6f
        Tool.PENCIL -> 1.2f
        Tool.BALLPOINT -> 1.0f
        else -> 1.2f
    }

    fun defaultColor(tool: Int): Int = when (tool) {
        Tool.HIGHLIGHTER -> HL_YELLOW
        Tool.PENCIL -> GRAPHITE
        else -> BLACK
    }

    /** The pen case of a fresh install: fountain black, ballpoint blue / red, pencil gray, highlighter yellow / green. */
    fun defaults(): List<PenPreset> = listOf(
        PenPreset("fountain-black", Tool.PEN, BLACK, 1.2f),
        PenPreset("ballpoint-blue", Tool.BALLPOINT, BLUE, 1.0f),
        PenPreset("ballpoint-red", Tool.BALLPOINT, RED, 1.0f),
        PenPreset("pencil-gray", Tool.PENCIL, GRAPHITE, 1.2f),
        PenPreset("hl-yellow", Tool.HIGHLIGHTER, HL_YELLOW, 14f),
        PenPreset("hl-green", Tool.HIGHLIGHTER, HL_GREEN, 14f),
    )

    /** Repairs one preset: known kind, opaque colour, width in range. */
    fun sanitize(p: PenPreset): PenPreset {
        val tool = if (isKnownKind(p.tool)) p.tool else Tool.PEN
        val r = widthRange(tool)
        val w = if (p.width.isNaN() || p.width <= 0f) defaultWidth(tool) else p.width.coerceIn(r.start, r.endInclusive)
        return PenPreset(p.id, tool, p.color or 0xFF000000.toInt(), w)
    }

    /**
     * The stored list made usable: empty (older settings) = [defaults]; repaired presets; blank or duplicate ids
     * replaced; at most [MAX_SLOTS].
     */
    fun normalize(stored: List<PenPreset>, newId: () -> String = ::randomId): List<PenPreset> {
        if (stored.isEmpty()) return defaults()
        val seen = HashSet<String>()
        return stored.take(MAX_SLOTS).map { raw ->
            val p = sanitize(raw)
            var id = p.id
            while (id.isBlank() || !seen.add(id)) id = newId()
            if (id == p.id) p else p.copy(id = id)
        }
    }

    /** The id to select: [selectedId] when it exists, else the first slot. */
    fun resolveSelected(pens: List<PenPreset>, selectedId: String): String =
        pens.firstOrNull { it.id == selectedId }?.id ?: pens.firstOrNull()?.id.orEmpty()

    /** What is persisted: an untouched default case is stored as empty, so it keeps following future defaults. */
    fun forStorage(pens: List<PenPreset>): List<PenPreset> = if (pens == defaults()) emptyList() else pens

    fun canAdd(pens: List<PenPreset>): Boolean = pens.size < MAX_SLOTS

    fun canRemove(pens: List<PenPreset>): Boolean = pens.size > 1

    /**
     * A new pen of [tool]: the first colour of that kind's palette no pen of the same family uses yet, default
     * width. Returns null when the case is full.
     */
    fun newPen(pens: List<PenPreset>, tool: Int, newId: () -> String = ::randomId): PenPreset? {
        if (!canAdd(pens)) return null
        val hl = tool == Tool.HIGHLIGHTER
        val used = pens.filter { (it.tool == Tool.HIGHLIGHTER) == hl }.map { it.color }.toSet()
        val palette = paletteFor(tool)
        val color = palette.firstOrNull { it !in used } ?: defaultColor(tool)
        return PenPreset(uniqueId(pens, newId), tool, color, defaultWidth(tool))
    }

    /** Inserts [pen] right after [afterId] (or at the end). Unchanged when full. */
    fun insertAfter(pens: List<PenPreset>, afterId: String?, pen: PenPreset): List<PenPreset> {
        if (!canAdd(pens)) return pens
        val at = pens.indexOfFirst { it.id == afterId }
        val list = pens.toMutableList()
        list.add(if (at < 0) list.size else at + 1, sanitize(pen))
        return list
    }

    /** A copy of [id] placed right after it with a fresh id; null when full or [id] is unknown. */
    fun duplicate(pens: List<PenPreset>, id: String, newId: () -> String = ::randomId): Pair<List<PenPreset>, PenPreset>? {
        if (!canAdd(pens)) return null
        val src = pens.firstOrNull { it.id == id } ?: return null
        val copy = src.copy(id = uniqueId(pens, newId))
        return insertAfter(pens, id, copy) to copy
    }

    /** Removes [id]; the last remaining slot is never removed. */
    fun remove(pens: List<PenPreset>, id: String): List<PenPreset> =
        if (!canRemove(pens)) pens else pens.filterNot { it.id == id }.ifEmpty { pens }

    /** The slot to select after removing [id] from [before]: its right neighbour, else its left one. */
    fun selectionAfterRemove(before: List<PenPreset>, id: String): String? {
        val i = before.indexOfFirst { it.id == id }
        if (i < 0) return null
        return before.getOrNull(i + 1)?.id ?: before.getOrNull(i - 1)?.id
    }

    /** Moves [id] by [delta] slots (negative = left), clamped to the ends. */
    fun move(pens: List<PenPreset>, id: String, delta: Int): List<PenPreset> {
        val from = pens.indexOfFirst { it.id == id }
        if (from < 0) return pens
        val to = (from + delta).coerceIn(0, pens.size - 1)
        if (to == from) return pens
        val list = pens.toMutableList()
        list.add(to, list.removeAt(from))
        return list
    }

    /** Replaces the preset with the same id (sanitized). */
    fun replace(pens: List<PenPreset>, pen: PenPreset): List<PenPreset> {
        val p = sanitize(pen)
        return pens.map { if (it.id == p.id) p else it }
    }

    /**
     * [p] switched to [tool]: between writing kinds colour and width carry over (width clamped); to or from the
     * highlighter colour and width become the new kind's defaults (highlighter colours are not writing colours).
     */
    fun withKind(p: PenPreset, tool: Int): PenPreset {
        if (p.tool == tool) return p
        val crossesFamily = (p.tool == Tool.HIGHLIGHTER) != (tool == Tool.HIGHLIGHTER)
        if (crossesFamily) return PenPreset(p.id, tool, defaultColor(tool), defaultWidth(tool))
        val r = widthRange(tool)
        return PenPreset(p.id, tool, p.color, p.width.coerceIn(r.start, r.endInclusive))
    }

    /** "기본값으로": a default-case pen gets its original look back, others their kind's default colour and width. */
    fun reset(p: PenPreset): PenPreset =
        defaults().firstOrNull { it.id == p.id } ?: PenPreset(p.id, p.tool, defaultColor(p.tool), defaultWidth(p.tool))

    private fun uniqueId(pens: List<PenPreset>, newId: () -> String): String {
        var id = newId()
        while (id.isBlank() || pens.any { it.id == id }) id = newId()
        return id
    }

    private fun randomId(): String = "pen-" + java.util.UUID.randomUUID().toString().take(8)

    // ---- colour helpers (hex field of the colour picker) ----

    /** "#RRGGBB" of an ARGB colour. */
    fun toHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    /** Parses "#RGB", "RRGGBB" or "#RRGGBB" (case-insensitive) into an opaque ARGB colour; null when invalid. */
    fun parseHex(text: String): Int? {
        val s = text.trim().removePrefix("#")
        val full = when (s.length) {
            3 -> s.map { "$it$it" }.joinToString("")
            6 -> s
            else -> return null
        }
        if (!full.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return (full.toLong(16).toInt()) or 0xFF000000.toInt()
    }
}
