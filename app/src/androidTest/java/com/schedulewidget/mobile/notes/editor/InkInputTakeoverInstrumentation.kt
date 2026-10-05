package com.schedulewidget.mobile.notes.editor

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.MonotonicFrameClock
import com.schedulewidget.mobile.notes.NoteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.File

/** Covers stylus arrival while finger-draw is already writing. */
class InkInputTakeoverInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val root = File(targetContext.cacheDir, "ink-takeover-${System.nanoTime()}")
        val files = File(root, "files").apply { mkdirs() }
        val scratch = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
        }
        val frameClock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                delay(16)
                return onFrame(System.nanoTime())
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + frameClock)
        val results = mutableListOf<String>()
        var result: String
        try {
            runBlocking {
                val meta = NoteStore.createBlank(scratch, "Stylus takeover", "lined")
                val note = checkNotNull(NoteStore.load(scratch, meta.id))
                val state = withContext(Dispatchers.Main) { EditorState(scratch, note, emptyMap(), 1f, scope) }
                withContext(Dispatchers.Main) {
                    state.setViewport(600f, 500f)
                    state.fingerDraws = true
                    val input = InkInput(state, exponentialDecay<Offset>())
                    val base = SystemClock.uptimeMillis()

                    fun pointer(id: Int, tool: Int) = MotionEvent.PointerProperties().apply { this.id = id; toolType = tool }
                    fun coords(x: Float, y: Float) = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
                    fun one(action: Int, time: Long, tool: Int, x: Float, y: Float, buttons: Int = 0): MotionEvent {
                        val source = if (tool == MotionEvent.TOOL_TYPE_FINGER) InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_STYLUS
                        return MotionEvent.obtain(base, base + time, action, 1, arrayOf(pointer(0, tool)), arrayOf(coords(x, y)), 0, buttons, 1f, 1f, 0, 0, source, 0)
                    }
                    fun two(action: Int, time: Long, fingerX: Float, fingerY: Float, stylusX: Float, stylusY: Float): MotionEvent {
                        return MotionEvent.obtain(base, base + time, action, 2,
                            arrayOf(pointer(0, MotionEvent.TOOL_TYPE_FINGER), pointer(1, MotionEvent.TOOL_TYPE_STYLUS)),
                            arrayOf(coords(fingerX, fingerY), coords(stylusX, stylusY)),
                            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
                    }
                    fun twoFinger(action: Int, time: Long, x0: Float, y0: Float, x1: Float, y1: Float): MotionEvent {
                        return MotionEvent.obtain(base, base + time, action, 2,
                            arrayOf(pointer(0, MotionEvent.TOOL_TYPE_FINGER), pointer(1, MotionEvent.TOOL_TYPE_FINGER)),
                            arrayOf(coords(x0, y0), coords(x1, y1)),
                            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                    }

                    one(MotionEvent.ACTION_DOWN, 10, MotionEvent.TOOL_TYPE_FINGER, 180f, 180f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    one(MotionEvent.ACTION_MOVE, 20, MotionEvent.TOOL_TYPE_FINGER, 230f, 220f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    two(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 30, 230f, 220f, 260f, 250f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    check(state.livePage == 0 && state.gesture == InkGesture.DRAW)
                    two(MotionEvent.ACTION_MOVE, 40, 230f, 220f, 300f, 280f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    two(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 50, 230f, 220f, 300f, 280f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    one(MotionEvent.ACTION_UP, 60, MotionEvent.TOOL_TYPE_FINGER, 230f, 220f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    check(state.inkOf(note.pages.single().uid).strokes.size == 2)
                    results += "PASS: stylus takes over a finger-draw gesture and commits both ink segments"

                    one(MotionEvent.ACTION_HOVER_MOVE, 100, MotionEvent.TOOL_TYPE_STYLUS, 260f, 250f, MotionEvent.BUTTON_STYLUS_PRIMARY)
                        .let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    check(state.displayTool == EditorTool.ERASER)
                    one(MotionEvent.ACTION_DOWN, 120, MotionEvent.TOOL_TYPE_FINGER, 300f, 300f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    one(MotionEvent.ACTION_UP, 130, MotionEvent.TOOL_TYPE_FINGER, 300f, 300f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    check(state.displayTool == EditorTool.ERASER) { "a finger event released the held stylus button" }
                    one(MotionEvent.ACTION_HOVER_MOVE, 500, MotionEvent.TOOL_TYPE_STYLUS, 260f, 250f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    check(state.displayTool == EditorTool.PEN)
                    results += "PASS: finger events do not release or count as clicks for a held stylus button"
                    one(MotionEvent.ACTION_HOVER_EXIT, 600, MotionEvent.TOOL_TYPE_STYLUS, 260f, 250f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    state.fingerDraws = false

                    state.setViewport(600f, 1500f)
                    withContext(Dispatchers.IO) { NoteStore.writeMutex.lock() }
                    try {
                        state.beginInk(200f, 400f, 1f, eraser = false)
                        state.moveInk(230f, 420f, 1f)
                        state.endInk()
                        one(MotionEvent.ACTION_DOWN, 1500, MotionEvent.TOOL_TYPE_FINGER, 300f, 700f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                        one(MotionEvent.ACTION_MOVE, 1550, MotionEvent.TOOL_TYPE_FINGER, 300f, 600f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                        val beforeSave = state.endPullOffsetPx
                        check(beforeSave < 0f)
                        delay(EditorState.AUTOSAVE_MS + 100)
                        check(NoteStore.pendingSaves[note.id]?.isActive == true)
                        one(MotionEvent.ACTION_MOVE, 2700, MotionEvent.TOOL_TYPE_FINGER, 300f, 550f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                        check(state.endPullOffsetPx < beforeSave) { "autosave reset the active end pull" }
                        one(MotionEvent.ACTION_UP, 2710, MotionEvent.TOOL_TYPE_FINGER, 300f, 550f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                        check(NoteStore.pendingSaves[note.id]?.isActive == true)
                        check(state.note.pages.size == 2) { "page pull did not append while its save was active" }
                        results += "PASS: a pull released during autosave appends one page without losing the pending ink"
                    } finally {
                        withContext(Dispatchers.IO) { NoteStore.writeMutex.unlock() }
                    }
                    state.saveNow(reportError = false)?.await()
                    check(withContext(Dispatchers.IO) { NoteStore.load(scratch, note.id)?.pages?.size } == 2)
                    results += "PASS: the new page and its pending ink persist after the queued save completes"

                    one(MotionEvent.ACTION_DOWN, 3000, MotionEvent.TOOL_TYPE_FINGER, 300f, 700f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    one(MotionEvent.ACTION_MOVE, 3050, MotionEvent.TOOL_TYPE_FINGER, 300f, 600f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    check(state.endPullOffsetPx < 0f)
                    two(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 3060, 300f, 600f, 260f, 350f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    check(state.endPullOffsetPx == 0f && state.endPullJob == null) { "stylus began writing against a translated page" }
                    two(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 3070, 300f, 600f, 260f, 350f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    one(MotionEvent.ACTION_UP, 3080, MotionEvent.TOOL_TYPE_FINGER, 300f, 600f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    results += "PASS: stylus contact snaps an end-pull offset before recording ink coordinates"

                    state.setViewport(600f, 500f)
                    state.panBy(0f, -100000f)
                    one(MotionEvent.ACTION_DOWN, 4000, MotionEvent.TOOL_TYPE_FINGER, 300f, 400f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    one(MotionEvent.ACTION_MOVE, 4050, MotionEvent.TOOL_TYPE_FINGER, 300f, 300f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    check(state.endPullOffsetPx < 0f)
                    twoFinger(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 4060, 300f, 300f, 320f, 320f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    check(state.endPullJob?.isActive == true)
                    twoFinger(MotionEvent.ACTION_MOVE, 4070, 300f, 300f, 325f, 325f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    check(state.endPullOffsetPx != 0f) { "a multi-touch move overwrote the spring offset" }
                    twoFinger(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 4080, 300f, 300f, 325f, 325f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    one(MotionEvent.ACTION_UP, 4090, MotionEvent.TOOL_TYPE_FINGER, 300f, 300f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    results += "PASS: multi-touch moves leave the end-pull spring as the sole offset writer"

                    state.addPageAtEnd()
                    state.selectTool(EditorTool.LASSO)
                    one(MotionEvent.ACTION_DOWN, 5000, MotionEvent.TOOL_TYPE_STYLUS, 200f, 200f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    one(MotionEvent.ACTION_MOVE, 5010, MotionEvent.TOOL_TYPE_STYLUS, 300f, 220f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    one(MotionEvent.ACTION_MOVE, 5020, MotionEvent.TOOL_TYPE_STYLUS, 400f, 240f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    check(state.gesture == InkGesture.LASSO && state.lassoPage == 2)
                    state.undo()
                    check(state.note.pages.size == 2 && state.gesture == InkGesture.NONE)
                    one(MotionEvent.ACTION_UP, 5030, MotionEvent.TOOL_TYPE_STYLUS, 400f, 240f).let { ev -> try { input.onTouch(ev) } finally { ev.recycle() } }
                    results += "PASS: undoing a page change cancels the lasso before its page index becomes stale"

                    state.saveNow(final = true, reportError = false)?.await()
                }
                result = results.joinToString("\n")
            }
        } catch (e: Throwable) {
            result = "FAIL: ${e.stackTraceToString()}"
        } finally {
            scope.cancel()
            runBlocking { NoteStore.writeMutex.lock(); try { root.deleteRecursively() } finally { NoteStore.writeMutex.unlock() } }
        }
        finish(if (result.startsWith("PASS:")) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString(Instrumentation.REPORT_KEY_STREAMRESULT, "$result\n")
        })
    }
}
