package com.schedulewidget.mobile.notes.editor

import android.graphics.RectF
import com.schedulewidget.mobile.notes.NoteStore
import com.schedulewidget.mobile.notes.ink.InkGeometry
import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.notes.ink.ShapeCorrection
import com.schedulewidget.mobile.notes.ink.Stroke
import com.schedulewidget.mobile.notes.ink.TextBox
import com.schedulewidget.mobile.notes.ink.Tool
import com.schedulewidget.mobile.notes.render.PageDraw
import com.schedulewidget.mobile.record.Recorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

internal enum class InkGesture { NONE, DRAW, ERASE, LASSO, MOVE_SELECTION, TEXT, LINK }

private fun round2(value: Float): Float = Math.round(value * 100f) / 100f
internal fun EditorState.beginInkFeature(sx: Float, sy: Float, pressure: Float, eraser: Boolean, toolOverride: EditorTool?) {
        stopFling()
        downX = sx; downY = sy; lastX = sx; lastY = sy; moved = false
        val i = pageAt(sx, sy)
        downPage = i
        if (linkMode) { gesture = InkGesture.LINK; return }
        val effective = if (eraser) EditorTool.ERASER else toolOverride ?: tool
        if (effective != EditorTool.LASSO && effective != EditorTool.ERASER) selection = null
        when (effective) {
            EditorTool.PEN, EditorTool.HIGHLIGHTER -> {
                if (i < 0) { gesture = InkGesture.NONE; return }
                gesture = InkGesture.DRAW
                livePage = i
                // The stroke takes kind, colour and width from the active pen slot.
                val pen = if (effective == EditorTool.HIGHLIGHTER) highlighterPen else writerPen
                liveTool = pen.tool
                liveColor = pen.color
                liveWidth = pen.width
                liveN = 0
                liveSnapped = false
                liveShaped = false
                liveRaw = null
                val rs = Recorder.state.value
                if (rs.isRecording && rs.id != null) { liveRec = rs.id; liveRecMs = rs.elapsedMs() } else { liveRec = null; liveRecMs = 0 }
                addLivePoint(sx, sy, pressure)
                if (holdSnaps()) armSnap(sx, sy)
            }
            EditorTool.ERASER -> {
                gesture = InkGesture.ERASE
                eraseBefore.clear()
                eraserX = sx; eraserY = sy
                eraseSegment(sx, sy, sx, sy)
                liveVersion++
            }
            EditorTool.LASSO -> {
                val sel = selection
                if (sel != null && selectionScreenRect(sel)?.contains(sx, sy) == true) {
                    gesture = InkGesture.MOVE_SELECTION
                    dragDx = 0f; dragDy = 0f
                    return
                }
                selection = null
                if (i < 0) { gesture = InkGesture.NONE; return }
                gesture = InkGesture.LASSO
                lassoPage = i
                lassoN = 0
                addLassoPoint(sx, sy)
            }
            EditorTool.TEXT -> {
                gesture = InkGesture.TEXT
                textHit = if (i >= 0) textAt(i, sx, sy) else null
                dragDx = 0f; dragDy = 0f
            }
        }
    }

internal fun EditorState.switchInkModeFeature(sx: Float, sy: Float, pressure: Float, eraser: Boolean, toolOverride: EditorTool?) {
    when (gesture) {
        InkGesture.DRAW, InkGesture.ERASE -> endInkFeature()
        else -> cancelInkFeature()
    }
    beginInkFeature(sx, sy, pressure, eraser, toolOverride)
}

internal fun EditorState.moveInkFeature(sx: Float, sy: Float, pressure: Float) {
        if (!moved && InkGeometry.dist(sx, sy, downX, downY) > touchSlop) moved = true
        when (gesture) {
            InkGesture.DRAW -> {
                if (liveSnapped) {
                    // Straight highlighter line: only the end point follows the pen.
                    val i = livePage
                    livePts[3] = round2(toPageX(i, sx)); livePts[4] = round2(toPageY(i, sy))
                    liveVersion++
                } else if (liveShaped) {
                    // Shape-corrected pen stroke: hand jitter keeps the shape; moving on brings back the stroke as
                    // written and writing continues from it (hold again to re-correct).
                    val raw = liveRaw
                    if (raw != null && InkGeometry.dist(sx, sy, snapAnchorX, snapAnchorY) > 6f * density) {
                        if (raw.size > livePts.size) livePts = raw.copyOf(raw.size * 2) else raw.copyInto(livePts)
                        liveN = raw.size / 3
                        liveShaped = false
                        liveRaw = null
                        addLivePoint(sx, sy, pressure)
                        liveVersion++
                        armSnap(sx, sy)
                    }
                } else {
                    addLivePoint(sx, sy, pressure)
                    if (holdSnaps() && InkGeometry.dist(sx, sy, snapAnchorX, snapAnchorY) > 6f * density) armSnap(sx, sy)
                }
            }
            InkGesture.ERASE -> {
                eraseSegment(lastX, lastY, sx, sy)
                eraserX = sx; eraserY = sy
                liveVersion++
            }
            InkGesture.LASSO -> addLassoPoint(sx, sy)
            InkGesture.MOVE_SELECTION -> {
                val sel = selection ?: return
                val i = pageIndex(sel.uid)
                if (i < 0) return
                dragDx = (sx - downX) / pxPerPt(i); dragDy = (sy - downY) / pxPerPt(i)
            }
            InkGesture.TEXT -> {
                val hit = textHit
                if (hit != null && moved && downPage >= 0) {
                    dragTextId = hit.id
                    dragTextUid = note.pages[downPage].uid
                    dragDx = (sx - downX) / pxPerPt(downPage); dragDy = (sy - downY) / pxPerPt(downPage)
                }
            }
            else -> {}
        }
        lastX = sx; lastY = sy
    }

internal fun EditorState.endInkFeature() {
        snapJob?.cancel()
        when (gesture) {
            InkGesture.DRAW -> commitLive()
            InkGesture.ERASE -> {
                val edits = eraseBefore.map { (uid, before) -> Edit.Ink(uid, before, inkOf(uid)) }
                if (edits.isNotEmpty()) push(if (edits.size == 1) edits[0] else Edit.Group(edits))
                eraseBefore.clear()
                eraserX = Float.NaN; eraserY = Float.NaN
                liveVersion++
            }
            InkGesture.LASSO -> finishLasso()
            InkGesture.MOVE_SELECTION -> finishSelectionMove()
            InkGesture.TEXT -> finishText()
            InkGesture.LINK -> if (!moved && downPage >= 0) playAt(downPage, downX, downY)
            InkGesture.NONE -> {}
        }
        gesture = InkGesture.NONE
    }

    /** Drops the InkGesture (palm detected, second finger for zooming): erasing is rolled back. */
internal fun EditorState.cancelInkFeature() {
        snapJob?.cancel()
        if (gesture == InkGesture.ERASE) {
            eraseBefore.forEach { (uid, before) -> setInk(uid, before) }
            eraseBefore.clear()
            eraserX = Float.NaN; eraserY = Float.NaN
        }
        livePage = -1; liveN = 0; lassoN = 0; lassoPage = -1
        liveSnapped = false; liveShaped = false; liveRaw = null
        dragDx = 0f; dragDy = 0f; dragTextId = -1; dragTextUid = null
        gesture = InkGesture.NONE
        liveVersion++
    }

    /** How long the current ink InkGesture has gone, for "a second finger came down: was this a zoom?". */
internal fun EditorState.inkGestureIsYoungFeature(): Boolean = !moved || (gesture == InkGesture.DRAW && liveN < 6)

    /** A finger tap while the finger navigates (it doesn't draw). */
internal fun EditorState.onFingerTapFeature(sx: Float, sy: Float) {
        val i = pageAt(sx, sy)
        when {
            linkMode -> if (i >= 0) playAt(i, sx, sy)
            tool == EditorTool.TEXT -> if (i >= 0) {
                val hit = textAt(i, sx, sy)
                textEdit = if (hit != null) TextEdit(note.pages[i].uid, hit, hit.x, hit.y)
                else TextEdit(note.pages[i].uid, null, toPageX(i, sx), toPageY(i, sy))
            }
            tool == EditorTool.LASSO -> {
                val sel = selection
                if (sel != null && selectionScreenRect(sel)?.contains(sx, sy) != true) selection = null
            }
        }
    }

private fun EditorState.addLivePoint(sx: Float, sy: Float, pressure: Float) {
        val i = livePage
        val px = round2(toPageX(i, sx)); val py = round2(toPageY(i, sy))
        if (liveN > 0) {
            val lx = livePts[(liveN - 1) * 3]; val ly = livePts[(liveN - 1) * 3 + 1]
            // Skip sub-pixel steps (the S Pen reports at 240 Hz+); keep the higher pressure.
            if (InkGeometry.dist(px, py, lx, ly) * pxPerPt(i) < 0.6f) {
                livePts[(liveN - 1) * 3 + 2] = max(livePts[(liveN - 1) * 3 + 2], pressure)
                return
            }
        }
        if ((liveN + 1) * 3 > livePts.size) livePts = livePts.copyOf(livePts.size * 2)
        livePts[liveN * 3] = px
        livePts[liveN * 3 + 1] = py
        livePts[liveN * 3 + 2] = pressure.coerceIn(0f, 1f)
        liveN++
        liveVersion++
    }

/** Holding the pen still snaps the stroke: always for the highlighter, for other pens when 도형 보정 is on. */
private fun EditorState.holdSnaps(): Boolean = liveTool == Tool.HIGHLIGHTER || shapeCorrection

private fun EditorState.armSnap(sx: Float, sy: Float) {
        snapAnchorX = sx; snapAnchorY = sy
        snapJob?.cancel()
        snapJob = scope.launch {
            delay(EditorState.SNAP_HOLD_MS)
            if (gesture != InkGesture.DRAW || livePage < 0 || liveN < 2 || liveSnapped || liveShaped) return@launch
            if (liveTool == Tool.HIGHLIGHTER) {
                val ex = livePts[(liveN - 1) * 3]; val ey = livePts[(liveN - 1) * 3 + 1]
                if (InkGeometry.dist(ex, ey, livePts[0], livePts[1]) * pxPerPt(livePage) < 12f * density) return@launch
                livePts[3] = ex; livePts[4] = ey; livePts[5] = 1f
                liveN = 2
                liveSnapped = true
                liveVersion++
            } else if (shapeCorrection) {
                val minSize = EditorState.SHAPE_MIN_DP * density / pxPerPt(livePage)
                val shaped = ShapeCorrection.correct(livePts, liveN, minSize) ?: return@launch
                liveRaw = livePts.copyOf(liveN * 3)
                livePts = shaped.copyOf(shaped.size * 2)
                liveN = shaped.size / 3
                liveShaped = true
                liveVersion++
            }
        }
    }

private fun EditorState.commitLive() {
        val i = livePage
        if (i < 0 || i >= note.pages.size || liveN == 0) { livePage = -1; liveVersion++; return }
        val uid = note.pages[i].uid
        val s = Stroke(
            id = newId(), tool = liveTool, color = liveColor, width = liveWidth,
            pts = livePts.copyOf(liveN * 3), rec = liveRec, recMs = liveRecMs,
        )
        val cur = inkOf(uid)
        commitInk(uid, cur.copy(strokes = cur.strokes + s))
        livePage = -1
        liveN = 0
        liveShaped = false
        liveRaw = null
        liveVersion++
    }

private fun EditorState.eraseSegment(ax: Float, ay: Float, bx: Float, by: Float) {
        val i = pageAt(bx, by, slackPx = eraserRadiusPx)
        if (i < 0) return
        val uid = note.pages[i].uid
        val r = eraserRadiusPx / pxPerPt(i)
        val pax = toPageX(i, ax); val pay = toPageY(i, ay); val pbx = toPageX(i, bx); val pby = toPageY(i, by)
        val cur = inkOf(uid)
        var strokes: List<Stroke>? = null
        if (eraserPartial) {
            var changed = false
            val out = ArrayList<Stroke>(cur.strokes.size)
            for (s in cur.strokes) {
                val pieces = InkGeometry.eraseSplit(s, pax, pay, pbx, pby, r, ::newId)
                if (pieces == null) out += s else { out += pieces; changed = true }
            }
            if (changed) strokes = out
        } else {
            strokes = InkGeometry.eraseWhole(cur.strokes, pax, pay, pbx, pby, r)
        }
        // The stroke eraser also removes text boxes it touches.
        val texts = if (eraserPartial) null else cur.texts.filterNot { t ->
            val b = PageDraw.textBounds(t)
            b.inset(-r, -r)
            b.contains(pbx, pby)
        }.takeIf { it.size != cur.texts.size }
        if (strokes == null && texts == null) return
        if (uid !in eraseBefore) eraseBefore[uid] = cur
        setInk(uid, cur.copy(strokes = strokes ?: cur.strokes, texts = texts ?: cur.texts))
    }

private fun EditorState.addLassoPoint(sx: Float, sy: Float) {
        val i = lassoPage
        if (i < 0) return
        if ((lassoN + 1) * 2 > lassoPts.size) lassoPts = lassoPts.copyOf(lassoPts.size * 2)
        lassoPts[lassoN * 2] = toPageX(i, sx)
        lassoPts[lassoN * 2 + 1] = toPageY(i, sy)
        lassoN++
        liveVersion++
    }

private fun EditorState.finishLasso() {
        val i = lassoPage
        val poly = lassoPts.copyOf(lassoN * 2)
        lassoN = 0; lassoPage = -1
        liveVersion++
        if (i < 0 || poly.size < 6) return
        val uid = note.pages[i].uid
        val cur = inkOf(uid)
        val ids = InkGeometry.lassoSelect(cur.strokes, poly)
        val tids = cur.texts.filter { t ->
            val b = PageDraw.textBounds(t)
            InkGeometry.pointInPolygon(b.centerX(), b.centerY(), poly)
        }.mapTo(HashSet()) { it.id }
        if (ids.isEmpty() && tids.isEmpty()) return
        selection = makeSelection(uid, ids, tids)
    }

private fun EditorState.makeSelection(uid: String, ids: Set<Long>, tids: Set<Long>): Selection? {
        val cur = inkOf(uid)
        val strokes = cur.strokes.filter { it.id in ids }
        val texts = cur.texts.filter { it.id in tids }
        if (strokes.isEmpty() && texts.isEmpty()) return null
        val r = InkGeometry.unionBounds(strokes)?.let { RectF(it[0], it[1], it[2], it[3]) }
        val b = texts.fold(r) { acc, t -> val tb = PageDraw.textBounds(t); acc?.apply { union(tb) } ?: tb } ?: return null
        return Selection(uid, strokes.mapTo(HashSet()) { it.id }, texts.mapTo(HashSet()) { it.id }, b)
    }

    /** The selection's box on screen (padded), or null when its page is gone. */
internal fun EditorState.selectionScreenRectFeature(sel: Selection): RectF? {
        val i = pageIndex(sel.uid)
        if (i < 0) return null
        val pad = 12f * density
        return RectF(
            toScreenX(i, sel.bounds.left + dragDx) - pad, toScreenY(i, sel.bounds.top + dragDy) - pad,
            toScreenX(i, sel.bounds.right + dragDx) + pad, toScreenY(i, sel.bounds.bottom + dragDy) + pad,
        )
    }

private fun EditorState.finishSelectionMove() {
        val sel = selection ?: return
        val dx = dragDx; val dy = dragDy
        dragDx = 0f; dragDy = 0f
        if (abs(dx) < 0.01f && abs(dy) < 0.01f) return
        val cur = inkOf(sel.uid)
        val next = cur.copy(
            strokes = cur.strokes.map { if (it.id in sel.strokeIds) InkGeometry.translate(it, dx, dy) else it },
            texts = cur.texts.map { if (it.id in sel.textIds) it.copy(x = it.x + dx, y = it.y + dy) else it },
        )
        commitInk(sel.uid, next)
        selection = Selection(sel.uid, sel.strokeIds, sel.textIds, RectF(sel.bounds).apply { offset(dx, dy) })
    }

internal fun EditorState.clearSelectionFeature() { selection = null }

internal fun EditorState.deleteSelectionFeature() {
        val sel = selection ?: return
        val cur = inkOf(sel.uid)
        commitInk(sel.uid, cur.copy(
            strokes = cur.strokes.filterNot { it.id in sel.strokeIds },
            texts = cur.texts.filterNot { it.id in sel.textIds },
        ))
        selection = null
    }

internal fun EditorState.recolorSelectionFeature(color: Int) {
        val sel = selection ?: return
        val cur = inkOf(sel.uid)
        commitInk(sel.uid, cur.copy(
            // Highlighter strokes keep their own (translucent) look; only the hue changes.
            strokes = cur.strokes.map { if (it.id in sel.strokeIds) it.copy(color = color) else it },
            texts = cur.texts.map { if (it.id in sel.textIds) it.copy(color = color) else it },
        ))
    }

    /** Copies the selection a little down-right and selects the copy. */
internal fun EditorState.duplicateSelectionFeature() {
        val sel = selection ?: return
        val cur = inkOf(sel.uid)
        val d = 12f
        val newStrokes = cur.strokes.filter { it.id in sel.strokeIds }.map { InkGeometry.translate(it, d, d).copy(id = newId()) }
        val newTexts = cur.texts.filter { it.id in sel.textIds }.map { it.copy(id = newId(), x = it.x + d, y = it.y + d) }
        commitInk(sel.uid, cur.copy(strokes = cur.strokes + newStrokes, texts = cur.texts + newTexts))
        selection = Selection(sel.uid, newStrokes.mapTo(HashSet()) { it.id }, newTexts.mapTo(HashSet()) { it.id },
            RectF(sel.bounds).apply { offset(d, d) })
    }

    // ---- text ----

private fun EditorState.textAt(i: Int, sx: Float, sy: Float): TextBox? {
        val px = toPageX(i, sx); val py = toPageY(i, sy)
        val pad = 6f * density / pxPerPt(i)
        return inkOf(note.pages[i].uid).texts.lastOrNull { t ->
            PageDraw.textBounds(t).apply { inset(-pad, -pad) }.contains(px, py)
        }
    }

private fun EditorState.finishText() {
        val i = downPage
        val hit = textHit
        textHit = null
        if (i < 0) return
        val uid = note.pages[i].uid
        if (hit != null && moved && dragTextId == hit.id) {
            val dx = dragDx; val dy = dragDy
            dragDx = 0f; dragDy = 0f; dragTextId = -1; dragTextUid = null
            val cur = inkOf(uid)
            commitInk(uid, cur.copy(texts = cur.texts.map { if (it.id == hit.id) it.copy(x = it.x + dx, y = it.y + dy) else it }))
            return
        }
        if (moved) return
        textEdit = if (hit != null) TextEdit(uid, hit, hit.x, hit.y)
        else TextEdit(uid, null, toPageX(i, downX), toPageY(i, downY))
    }

    /** Result of the text dialog: blank text deletes the box. */
internal fun EditorState.applyTextFeature(edit: TextEdit, text: String, size: Float) {
        textEdit = null
        val cur = inkOf(edit.uid)
        val box = edit.box
        val next = when {
            box == null && text.isBlank() -> return
            box == null -> cur.copy(texts = cur.texts + TextBox(newId(), edit.x, edit.y, text.trimEnd(), textColor, size))
            text.isBlank() -> cur.copy(texts = cur.texts.filterNot { it.id == box.id })
            else -> cur.copy(texts = cur.texts.map { if (it.id == box.id) it.copy(text = text.trimEnd(), size = size) else it })
        }
        commitInk(edit.uid, next)
    }

    // ---- recording link ----

private fun EditorState.playAt(i: Int, sx: Float, sy: Float) {
        val px = toPageX(i, sx); val py = toPageY(i, sy)
        val r = EditorState.LINK_HIT_DP * density / pxPerPt(i)
        val s = inkOf(note.pages[i].uid).strokes.lastOrNull { it.rec != null && InkGeometry.hits(it, px, py, r) }
        if (s == null) { onMessage("녹음과 연결된 필기가 아니에요"); return }
        onPlayLink(s.rec!!, (s.recMs - 3000L).coerceAtLeast(0L))
    }
