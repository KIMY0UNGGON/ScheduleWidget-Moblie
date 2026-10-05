package com.schedulewidget.mobile.notes.editor

import com.schedulewidget.mobile.notes.NoteStore
import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.notes.render.PageTemplates

internal fun EditorState.addBlankAfterFeature(index: Int, template: String) {
        val pages = note.pages
        val ref = pages.getOrNull(index)
        val page = PageInfo(
            NoteStore.newPageUid(), PageInfo.KIND_BLANK, template = template,
            w = ref?.w ?: PageInfo.A4_W, h = ref?.h ?: PageInfo.A4_H,
        )
        val at = (index + 1).coerceIn(0, pages.size)
        changePages(pages.toMutableList().apply { add(at, page) })
        goToPage(at)
    }

internal fun EditorState.duplicatePageFeature(index: Int) {
        val pages = note.pages
        val src = pages.getOrNull(index) ?: return
        val copy = src.copy(uid = NoteStore.newPageUid())
        val after = pages.toMutableList().apply { add(index + 1, copy) }
        val srcInk = inkOf(src.uid)
        val edits = ArrayList<Edit>()
        if (!srcInk.isEmpty) { edits += Edit.Ink(copy.uid, PageInk.EMPTY, srcInk); setInk(copy.uid, srcInk) }
        edits += Edit.Pages(pages, after)
        setPages(after)
        push(Edit.Group(edits))
    }

internal fun EditorState.deletePageFeature(index: Int) {
        val pages = note.pages
        if (pages.size <= 1) { onMessage("마지막 페이지는 지울 수 없어요"); return }
        if (index !in pages.indices) return
        if (selection?.uid == pages[index].uid) selection = null
        // The page's ink stays in memory (and on disk until the editor closes) so undo can bring it back.
        changePages(pages.toMutableList().apply { removeAt(index) })
    }

internal fun EditorState.movePageFeature(from: Int, to: Int) {
        val pages = note.pages
        if (from !in pages.indices || to !in pages.indices || from == to) return
        changePages(pages.toMutableList().apply { add(to, removeAt(from)) })
    }

    /**
     * Inserts [added] pages (each with its handwriting) at position [at] as one undo step and shows the first of
     * them. Their PDF sources must already be in the notebook folder.
     */
internal fun EditorState.insertPagesFeature(at: Int, added: List<Pair<PageInfo, PageInk>>) {
        if (added.isEmpty()) return
        val before = note.pages
        val pos = at.coerceIn(0, before.size)
        val after = PageOps.insert(before, pos, added.map { it.first })
        val edits = ArrayList<Edit>()
        for ((p, pageInk) in added) {
            if (pageInk.isEmpty) continue
            edits += Edit.Ink(p.uid, inkOf(p.uid), pageInk)
            setInk(p.uid, pageInk)
        }
        edits += Edit.Pages(before, after)
        setPages(after)
        push(if (edits.size == 1) edits[0] else Edit.Group(edits))
        goToPage(pos)
    }

    /** A blank page at [at]: [template], [size] (relative to page [ref]) and [paper] colour. */
internal fun EditorState.insertBlankFeature(at: Int, template: String, size: PageSize, paper: String?, ref: Int = at - 1) {
        val (w, h) = size.size(note.pages.getOrNull(ref) ?: note.pages.getOrNull(at))
        val page = PageInfo(
            NoteStore.newPageUid(), PageInfo.KIND_BLANK, template = template, w = w, h = h,
            paper = PageTemplates.paperValue(paper),
        )
        insertPages(at, listOf(page to PageInk.EMPTY))
    }

    /** "+" after the last page: same size and paper style as the current notebook. */
internal fun EditorState.addPageAtEndFeature() {
        val last = note.pages.lastOrNull()
        val style = note.pages.lastOrNull { !it.isPdf }
        val page = when {
            last == null -> PageInfo(NoteStore.newPageUid(), PageInfo.KIND_BLANK)
            last.isPdf -> PageInfo(
                NoteStore.newPageUid(), PageInfo.KIND_BLANK,
                template = style?.template ?: com.schedulewidget.mobile.notes.ink.Template.PLAIN,
                w = last.w, h = last.h, paper = style?.paper,
            )
            else -> last.copy(uid = NoteStore.newPageUid(), rot = 0)
        }
        insertPages(note.pages.size, listOf(page to PageInk.EMPTY))
    }

    /** Copies of pages [indices] (with their handwriting) right after the last of them. */
internal fun EditorState.duplicatePagesFeature(indices: Collection<Int>) {
        val before = note.pages
        val (after, copies) = PageOps.duplicate(before, indices, NoteStore::newPageUid)
        if (copies.isEmpty()) return
        val edits = ArrayList<Edit>()
        for ((src, copy) in copies) {
            val srcInk = inkOf(src.uid)
            if (srcInk.isEmpty) continue
            edits += Edit.Ink(copy.uid, PageInk.EMPTY, srcInk)
            setInk(copy.uid, srcInk)
        }
        edits += Edit.Pages(before, after)
        setPages(after)
        push(if (edits.size == 1) edits[0] else Edit.Group(edits))
    }

    /** Deletes pages [indices] (one undo step); the notebook keeps at least one page. Returns false when refused. */
internal fun EditorState.deletePagesFeature(indices: Collection<Int>): Boolean {
        val before = note.pages
        val after = PageOps.delete(before, indices)
        if (after == null) { onMessage("모든 페이지를 지울 수는 없어요. 한 페이지는 남겨 주세요"); return false }
        if (after.size == before.size) return true
        val gone = before.filterIndexed { i, _ -> i in indices }.mapTo(HashSet()) { it.uid }
        if (selection?.uid in gone) selection = null
        if (textEdit?.uid in gone) textEdit = null
        // Ink of deleted pages stays in memory (and on disk until the editor closes) so undo can bring it back.
        changePages(after)
        return true
    }

    /** Moves pages [indices] (in their order) to the start or the end of the notebook. */
internal fun EditorState.movePagesToFeature(indices: Collection<Int>, toStart: Boolean) {
        val before = note.pages
        val after = PageOps.moveTo(before, indices, toStart)
        if (after == before) return
        changePages(after)
    }

    /** Turns pages [indices] by [quarterTurns] × 90° clockwise, handwriting included (one undo step). */
internal fun EditorState.rotatePagesFeature(indices: Collection<Int>, quarterTurns: Int = 1) {
        val before = note.pages
        val set = indices.filterTo(HashSet()) { it in before.indices }
        if (set.isEmpty() || Math.floorMod(quarterTurns, 4) == 0) return
        val edits = ArrayList<Edit>()
        val after = before.mapIndexed { i, p ->
            if (i !in set) return@mapIndexed p
            val old = inkOf(p.uid)
            val turned = PageOps.rotateInk(old, p.w, p.h, quarterTurns)
            if (turned !== old) { edits += Edit.Ink(p.uid, old, turned); setInk(p.uid, turned) }
            PageOps.rotate(p, quarterTurns)
        }
        selection = null
        edits += Edit.Pages(before, after)
        setPages(after)
        push(if (edits.size == 1) edits[0] else Edit.Group(edits))
    }

    /**
     * New [template] and/or [paper] for the blank pages among [indices] (PDF pages keep their look). Returns the
     * number of pages changed.
     */
internal fun EditorState.setBackgroundFeature(indices: Collection<Int>, template: String?, paper: String?, setPaper: Boolean): Int {
        val before = note.pages
        var n = 0
        val after = before.mapIndexed { i, p ->
            if (i !in indices || p.isPdf) return@mapIndexed p
            n++
            p.copy(
                template = template ?: p.template,
                paper = if (setPaper) PageTemplates.paperValue(paper) else p.paper,
            )
        }
        if (n == 0) { onMessage("PDF 페이지는 배경을 바꿀 수 없어요"); return 0 }
        if (after != before) changePages(after)
        return n
    }

    /** Erases all handwriting and text of pages [indices] (one undo step). */
internal fun EditorState.clearPagesFeature(indices: Collection<Int>) {
        val edits = ArrayList<Edit>()
        for (i in indices) {
            val uid = note.pages.getOrNull(i)?.uid ?: continue
            val old = inkOf(uid)
            if (old.isEmpty) continue
            edits += Edit.Ink(uid, old, PageInk.EMPTY)
            setInk(uid, PageInk.EMPTY)
        }
        if (edits.isEmpty()) { onMessage("지울 필기가 없어요"); return }
        if (selection?.uid in edits.map { (it as Edit.Ink).uid }) selection = null
        push(if (edits.size == 1) edits[0] else Edit.Group(edits))
    }
