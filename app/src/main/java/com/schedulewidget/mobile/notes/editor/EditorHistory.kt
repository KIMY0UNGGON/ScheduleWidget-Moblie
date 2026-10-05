package com.schedulewidget.mobile.notes.editor

import com.schedulewidget.mobile.notes.ink.PageInk

internal fun EditorState.commitInkFeature(uid: String, after: PageInk) {
        val before = inkOf(uid)
        if (before == after) return
        push(Edit.Ink(uid, before, after))
        setInk(uid, after)
    }

internal fun EditorState.setInkFeature(uid: String, value: PageInk) {
        ink[uid] = value
        inkVersion++
        dirtyPages += uid
        scheduleSave()
    }

internal fun EditorState.pushFeature(e: Edit) {
        undoStack.addLast(e)
        if (undoStack.size > EditorState.MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        syncUndoFlags()
    }

internal fun EditorState.undoFeature() {
    val e = undoStack.removeLastOrNull() ?: return
    if (gesture != InkGesture.NONE) cancelInk()
    applyFeature(e, forward = false)
        redoStack.addLast(e)
        selection = null
        syncUndoFlags()
    }

internal fun EditorState.redoFeature() {
    val e = redoStack.removeLastOrNull() ?: return
    if (gesture != InkGesture.NONE) cancelInk()
    applyFeature(e, forward = true)
        undoStack.addLast(e)
        selection = null
        syncUndoFlags()
    }

internal fun EditorState.applyFeature(e: Edit, forward: Boolean) {
        when (e) {
            is Edit.Ink -> setInk(e.uid, if (forward) e.after else e.before)
            is Edit.Pages -> setPages(if (forward) e.after else e.before)
            is Edit.Group -> (if (forward) e.edits else e.edits.asReversed()).forEach { applyFeature(it, forward) }
        }
    }

internal fun EditorState.syncUndoFlagsFeature() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }
