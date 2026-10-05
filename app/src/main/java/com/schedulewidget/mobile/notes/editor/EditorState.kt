package com.schedulewidget.mobile.notes.editor

import android.content.Context
import android.graphics.RectF
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.schedulewidget.mobile.notes.ink.InkGeometry
import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.notes.ink.StoredNote
import com.schedulewidget.mobile.notes.ink.TextBox
import com.schedulewidget.mobile.notes.ink.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlin.math.max

enum class EditorTool { PEN, HIGHLIGHTER, ERASER, LASSO, TEXT }

/** One undoable change. Page ink is immutable, so snapshots before/after are cheap. */
sealed interface Edit {
    class Ink(val uid: String, val before: PageInk, val after: PageInk) : Edit
    class Pages(val before: List<PageInfo>, val after: List<PageInfo>) : Edit
    class Group(val edits: List<Edit>) : Edit
}

/** Lassoed strokes / text boxes on page [uid]; [bounds] in page points. */
class Selection(val uid: String, val strokeIds: Set<Long>, val textIds: Set<Long>, val bounds: RectF) {
    val ids: Set<Long> get() = strokeIds + textIds
}

/** A text box being created ([box] null) or edited, at ([x], [y]) on page [uid]. */
class TextEdit(val uid: String, val box: TextBox?, val x: Float, val y: Float)

/**
 * Everything the notebook editor shows and edits: pages and ink (with undo/redo and debounced autosave), the
 * viewport (zoom + pan over the vertically stacked pages), the current tool, and the in-progress gesture.
 * Screen coordinates are pixels of the page area; "content" units are pixels at zoom 1 (pages fit the width).
 */
@Stable
class EditorState(
    internal val context: Context,
    initial: StoredNote,
    inkMap: Map<String, PageInk>,
    val density: Float,
    internal val scope: CoroutineScope,
) {
    val noteId: String = initial.id
    var note by mutableStateOf(initial)
        private set
    internal val ink = HashMap(inkMap)
    /** Bumped on every ink change (the page layer redraws). */
    var inkVersion by mutableIntStateOf(0)
        internal set

    fun inkOf(uid: String): PageInk = ink[uid] ?: PageInk.EMPTY

    // ---- tools ----
    var tool by mutableStateOf(EditorTool.PEN)
    var inputToolOverride by mutableStateOf<EditorTool?>(null)
        internal set
    val displayTool: EditorTool get() = inputToolOverride ?: tool

    // Pen case (NotesSettings.pens): the slots of the tool strip. [tool] is PEN while a writing slot is active and
    // HIGHLIGHTER while a highlighter slot is. The tool strip persists changes (debounced).
    private val storedNotes = com.schedulewidget.mobile.data.Repository.get(context).data.value.notes
    var pens by mutableStateOf(NotesPens.normalize(storedNotes.pens))
        private set
    /** Id of the active pen slot. */
    var penId by mutableStateOf(NotesPens.resolveSelected(pens, storedNotes.selectedPen))
        private set
    /** Last active writing / highlighter slot, so switching to the eraser and back keeps each family's pen. */
    private var lastWriterId = pens.firstOrNull { it.id == penId && it.tool != Tool.HIGHLIGHTER }?.id
    private var lastHighlighterId = pens.firstOrNull { it.id == penId && it.tool == Tool.HIGHLIGHTER }?.id
    /** The slot active before [penId] (quick "previous pen" switch). */
    var previousPenId: String? = null
        private set
    /** Lets the input layer drop a sticky barrel override after a manual preset selection. */
    var penSelectionRevision = 0
        private set

    val activePen: com.schedulewidget.mobile.data.PenPreset
        get() = pens.firstOrNull { it.id == penId } ?: pens.first()

    /** The writing pen used when [tool] is PEN (the active slot, or the last writing slot while a highlighter is active). */
    val writerPen: com.schedulewidget.mobile.data.PenPreset
        get() = activePen.takeIf { it.tool != Tool.HIGHLIGHTER }
            ?: pens.firstOrNull { it.id == lastWriterId && it.tool != Tool.HIGHLIGHTER }
            ?: pens.firstOrNull { it.tool != Tool.HIGHLIGHTER }
            ?: com.schedulewidget.mobile.data.PenPreset("", Tool.PEN, NotesPens.BLACK, NotesPens.defaultWidth(Tool.PEN))

    /** The highlighter used when [tool] is HIGHLIGHTER. */
    val highlighterPen: com.schedulewidget.mobile.data.PenPreset
        get() = activePen.takeIf { it.tool == Tool.HIGHLIGHTER }
            ?: pens.firstOrNull { it.id == lastHighlighterId && it.tool == Tool.HIGHLIGHTER }
            ?: pens.firstOrNull { it.tool == Tool.HIGHLIGHTER }
            ?: com.schedulewidget.mobile.data.PenPreset("", Tool.HIGHLIGHTER, NotesPens.HL_YELLOW, NotesPens.defaultWidth(Tool.HIGHLIGHTER))

    /** Makes slot [id] the active pen and switches to its tool (leaves link mode, drops a lasso selection). */
    fun selectPen(id: String) {
        val p = pens.firstOrNull { it.id == id } ?: return
        penSelectionRevision++
        inputToolOverride = null
        if (id != penId) previousPenId = penId
        penId = id
        if (p.tool == Tool.HIGHLIGHTER) lastHighlighterId = id else lastWriterId = id
        tool = if (p.tool == Tool.HIGHLIGHTER) EditorTool.HIGHLIGHTER else EditorTool.PEN
        linkMode = false
        clearSelection()
    }

    fun selectTool(next: EditorTool) {
        penSelectionRevision++
        inputToolOverride = null
        tool = next
        linkMode = false
    }

    /** Back to the slot used before the current one (no-op when there is none). */
    fun selectPreviousPen() { previousPenId?.let { if (pens.any { p -> p.id == it }) selectPen(it) } }

    /** Replaces the whole case (after an edit in the pen editor); keeps a valid active slot. */
    fun updatePens(next: List<com.schedulewidget.mobile.data.PenPreset>, select: String? = null) {
        val list = next.ifEmpty { NotesPens.defaults() }
        pens = list
        val want = select ?: penId
        val id = NotesPens.resolveSelected(list, want)
        if (select != null || id != penId) selectPen(id) else {
            // The active slot may have changed kind (pen <-> highlighter).
            val p = activePen
            if (tool == EditorTool.PEN || tool == EditorTool.HIGHLIGHTER) {
                val nextTool = if (p.tool == Tool.HIGHLIGHTER) EditorTool.HIGHLIGHTER else EditorTool.PEN
                if (nextTool != tool) {
                    penSelectionRevision++
                    inputToolOverride = null
                    tool = nextTool
                }
            }
            if (p.tool == Tool.HIGHLIGHTER) lastHighlighterId = p.id else lastWriterId = p.id
        }
    }

    // Compatibility accessors of the single-pen era: they read / edit the writing pen and the highlighter in use.
    var penColor: Int
        get() = writerPen.color
        set(v) { editPen(writerPen.id) { it.copy(color = v) } }
    var penWidth: Float
        get() = writerPen.width
        set(v) { editPen(writerPen.id) { it.copy(width = v) } }
    var hlColor: Int
        get() = highlighterPen.color
        set(v) { editPen(highlighterPen.id) { it.copy(color = v) } }
    var hlWidth: Float
        get() = highlighterPen.width
        set(v) { editPen(highlighterPen.id) { it.copy(width = v) } }

    private fun editPen(id: String, f: (com.schedulewidget.mobile.data.PenPreset) -> com.schedulewidget.mobile.data.PenPreset) {
        val p = pens.firstOrNull { it.id == id } ?: return
        pens = NotesPens.replace(pens, f(p))
    }

    /** Colour of new text boxes: chosen in the text tool's options, else the writing pen's colour. */
    var textColorChoice by mutableStateOf<Int?>(null)
    val textColor: Int get() = textColorChoice ?: penColor

    var eraserPartial by mutableStateOf(false)
    /** 녹음 연동: taps play the recording linked to the tapped stroke instead of writing. */
    var linkMode by mutableStateOf(false)
    /** NotesSettings.fingerDraws. */
    var fingerDraws = false
    /** Called with (recording id, offset ms) when a linked stroke is tapped in [linkMode]. */
    var onPlayLink: (String, Long) -> Unit = { _, _ -> }
    /** Called for short messages (Toast). */
    var onMessage: (String) -> Unit = {}
    /** Called when a fire-and-forget save fails; explicit save callers handle the returned error themselves. */
    var onSaveError: (Throwable) -> Unit = { onMessage("노트를 저장하지 못했어요. 저장 공간을 확인한 뒤 다시 시도해 주세요") }

    var selection by mutableStateOf<Selection?>(null)
        internal set
    var textEdit by mutableStateOf<TextEdit?>(null)

    // ---- undo ----
    internal val undoStack = ArrayDeque<Edit>()
    internal val redoStack = ArrayDeque<Edit>()
    var canUndo by mutableStateOf(false)
        internal set
    var canRedo by mutableStateOf(false)
        internal set

    // ---- viewport ----
    var viewW by mutableFloatStateOf(0f)
        internal set
    var viewH by mutableFloatStateOf(0f)
        internal set
    var scale by mutableFloatStateOf(1f)
        internal set
    var ox by mutableFloatStateOf(0f)
        internal set
    var oy by mutableFloatStateOf(0f)
        internal set
    var endPullOffsetPx by mutableFloatStateOf(0f)
        internal set
    internal var endPullJob: Job? = null
    var saveInProgress by mutableStateOf(false)
        internal set

    /** Vertical page layout in content units. */
    class Layout(val margin: Float, val fitW: Float, val tops: FloatArray, val heights: FloatArray, val totalH: Float, val contentW: Float)

    internal var layoutCache: Layout? = null
    internal var layoutKey: Pair<List<PageInfo>, Float>? = null

    internal var flingJob: Job? = null

    fun layout(): Layout = layoutFeature()
    fun k(i: Int): Float = kFeature(i)
    fun pxPerPt(i: Int): Float = pxPerPtFeature(i)
    fun setViewport(w: Float, h: Float) = setViewportFeature(w, h)
    fun pageAt(sx: Float, sy: Float, slackPx: Float = 0f): Int = pageAtFeature(sx, sy, slackPx)
    fun toPageX(i: Int, sx: Float): Float = toPageXFeature(i, sx)
    fun toPageY(i: Int, sy: Float): Float = toPageYFeature(i, sy)
    fun toScreenX(i: Int, px: Float): Float = toScreenXFeature(i, px)
    fun toScreenY(i: Int, py: Float): Float = toScreenYFeature(i, py)
    fun visibleRange(): IntRange = visibleRangeFeature()
    val currentPage: Int
        get() = if (viewH <= 0f) 0 else pageAtContentYFeature(oy + viewH / scale / 2f).coerceIn(0, max(0, note.pages.size - 1))
    internal val canAppendPagePull: Boolean
        get() = !saveInProgress && viewH > 0f && note.pages.isNotEmpty()
    internal val canPullAddPage: Boolean
        get() {
            if (!canAppendPagePull) return false
            val l = layout()
            val visibleHeight = viewH / scale
            val last = note.pages.lastIndex
            return (l.totalH <= visibleHeight || oy >= l.totalH - visibleHeight - 1f) &&
                oy + visibleHeight >= l.tops[last] + l.heights[last] - 1f
        }
    fun panBy(dx: Float, dy: Float) = panByFeature(dx, dy)
    fun zoomBy(factor: Float, fx: Float, fy: Float) = zoomByFeature(factor, fx, fy)
    fun goToPage(i: Int) = goToPageFeature(i)
    fun stopFling() = stopFlingFeature()
    fun fling(vx: Float, vy: Float, decay: DecayAnimationSpec<Offset>) = flingFeature(vx, vy, decay)
    // ---- ink gestures (stylus, or finger when it draws) ----

    internal var gesture = InkGesture.NONE

    /** The stroke being written, in page points of page [livePage]. Read by the overlay layer. */
    var livePage = -1
        internal set
    var liveTool = Tool.PEN
        internal set
    var liveColor = 0
        internal set
    var liveWidth = 0f
        internal set
    var livePts = FloatArray(3 * 512)
        internal set
    var liveN = 0
        internal set
    var liveVersion by mutableIntStateOf(0)
        internal set
    internal var liveRec: String? = null
    internal var liveRecMs = 0L
    internal var liveSnapped = false
    internal var snapJob: Job? = null
    internal var snapAnchorX = 0f
    internal var snapAnchorY = 0f

    /** Lasso outline in page points of [lassoPage] (x, y pairs). */
    var lassoPts = FloatArray(256)
        internal set
    var lassoN = 0
        internal set
    var lassoPage = -1
        internal set

    /** Eraser circle on screen while erasing (NaN = none). */
    var eraserX = Float.NaN
        internal set
    var eraserY = Float.NaN
        internal set
    val eraserRadiusPx: Float get() = ERASER_RADIUS_DP * density

    /** Drag offset (page points) of the selection / text box being moved; drawn by the page layer. */
    var dragDx by mutableFloatStateOf(0f)
        internal set
    var dragDy by mutableFloatStateOf(0f)
        internal set
    var dragTextId: Long = -1
        internal set
    var dragTextUid: String? = null
        internal set

    internal var downX = 0f
    internal var downY = 0f
    internal var downPage = -1
    internal var lastX = 0f
    internal var lastY = 0f
    internal var moved = false
    internal val eraseBefore = HashMap<String, PageInk>()
    internal var textHit: TextBox? = null

    internal val touchSlop: Float get() = 8f * density

    fun beginInk(sx: Float, sy: Float, pressure: Float, eraser: Boolean) = beginInkFeature(sx, sy, pressure, eraser, null)
    internal fun beginInkWithTool(sx: Float, sy: Float, pressure: Float, eraser: Boolean, toolOverride: EditorTool?) =
        beginInkFeature(sx, sy, pressure, eraser, toolOverride)
    internal fun switchInkMode(sx: Float, sy: Float, pressure: Float, eraser: Boolean, toolOverride: EditorTool?) =
        switchInkModeFeature(sx, sy, pressure, eraser, toolOverride)
    fun moveInk(sx: Float, sy: Float, pressure: Float) = moveInkFeature(sx, sy, pressure)
    fun endInk() = endInkFeature()
    fun cancelInk() = cancelInkFeature()
    val inkGestureIsYoung: Boolean get() = inkGestureIsYoungFeature()
    fun onFingerTap(sx: Float, sy: Float) = onFingerTapFeature(sx, sy)
    fun selectionScreenRect(sel: Selection): RectF? = selectionScreenRectFeature(sel)
    fun clearSelection() = clearSelectionFeature()
    fun deleteSelection() = deleteSelectionFeature()
    fun recolorSelection(color: Int) = recolorSelectionFeature(color)
    fun duplicateSelection() = duplicateSelectionFeature()
    fun applyText(edit: TextEdit, text: String, size: Float) = applyTextFeature(edit, text, size)
    // ---- pages ----

    fun pageIndex(uid: String): Int = note.pages.indexOfFirst { it.uid == uid }

    /** Inserts a blank page with [template] after page [index] (same size as that page). */
    fun addBlankAfter(index: Int, template: String) = addBlankAfterFeature(index, template)
    fun duplicatePage(index: Int) = duplicatePageFeature(index)
    fun deletePage(index: Int) = deletePageFeature(index)
    fun movePage(from: Int, to: Int) = movePageFeature(from, to)
    val endSpace: Float get() = 72f * density
    fun insertPages(at: Int, added: List<Pair<PageInfo, PageInk>>) = insertPagesFeature(at, added)
    fun insertBlank(at: Int, template: String, size: PageSize, paper: String?, ref: Int = at - 1) = insertBlankFeature(at, template, size, paper, ref)
    fun addPageAtEnd() = addPageAtEndFeature()
    fun duplicatePages(indices: Collection<Int>) = duplicatePagesFeature(indices)
    fun deletePages(indices: Collection<Int>): Boolean = deletePagesFeature(indices)
    fun movePagesTo(indices: Collection<Int>, toStart: Boolean) = movePagesToFeature(indices, toStart)
    fun rotatePages(indices: Collection<Int>, quarterTurns: Int = 1) = rotatePagesFeature(indices, quarterTurns)
    fun setBackground(indices: Collection<Int>, template: String?, paper: String?, setPaper: Boolean): Int = setBackgroundFeature(indices, template, paper, setPaper)
    fun clearPages(indices: Collection<Int>) = clearPagesFeature(indices)
    internal fun changePages(after: List<PageInfo>) {
        push(Edit.Pages(note.pages, after))
        setPages(after)
    }

    internal fun setPages(pages: List<PageInfo>) {
        if (gesture != InkGesture.NONE) cancelInk()
        note = note.copy(pages = pages)
        pagesDirty = true
        clampViewportFeature()
        scheduleSave()
    }

    // ---- undo / redo ----

    internal fun commitInk(uid: String, after: PageInk) = commitInkFeature(uid, after)
    internal fun setInk(uid: String, value: PageInk) = setInkFeature(uid, value)
    internal fun push(e: Edit) = pushFeature(e)
    fun undo() = undoFeature()
    fun redo() = redoFeature()
    internal fun apply(e: Edit, forward: Boolean) = applyFeature(e, forward)
    internal fun syncUndoFlags() = syncUndoFlagsFeature()
    // ---- autosave ----

    internal val dirtyPages = HashSet<String>()
    internal var pagesDirty = false
    /** Saved since the library thumbnail was last refreshed. */
    @Volatile internal var thumbDirty = false
    internal var saveJob: Job? = null
    internal var saveRevision = 0L

    internal fun scheduleSave() = scheduleSaveFeature()
    fun saveNow(final: Boolean = false, thumbnail: Boolean = final, reportError: Boolean = true): Deferred<Unit>? =
        saveNowFeature(final, thumbnail, reportError)
    // ---- detail tiles ----

    /** Tiles needed for sharp PDF pages at the current zoom (none when the cached page bitmaps suffice). */
    fun tileRequests(renderer: PageRenderer): List<PageRenderer.TileRequest> = tileRequestsFeature(renderer)
    private var idCounter = System.currentTimeMillis() * 1000

    internal fun newId(): Long = ++idCounter

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 5f
        const val ERASER_RADIUS_DP = 10f
        const val LINK_HIT_DP = 14f
        const val SNAP_HOLD_MS = 550L
        const val AUTOSAVE_MS = 1000L
        const val MAX_UNDO = 200

        val PEN_COLORS = listOf(
            0xFF000000.toInt(), 0xFF1E5BD8.toInt(), 0xFFE53935.toInt(), 0xFF2E9E44.toInt(),
        )
        val HL_COLORS = listOf(
            0xFFFFE34D.toInt(), 0xFF8BE36B.toInt(), 0xFFFF8AB8.toInt(), 0xFF7FD3FF.toInt(),
        )
        /** Pen widths in points. */
        val PEN_WIDTHS = listOf(0.8f, 1.5f, 2.6f, 4.2f)
        val HL_WIDTHS = listOf(8f, 14f, 22f)

        }
}
