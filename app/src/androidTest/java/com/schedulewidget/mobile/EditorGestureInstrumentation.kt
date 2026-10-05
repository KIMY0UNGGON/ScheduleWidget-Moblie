package com.schedulewidget.mobile

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
import com.schedulewidget.mobile.notes.editor.EditorState
import com.schedulewidget.mobile.notes.editor.EditorTool
import com.schedulewidget.mobile.notes.editor.InkInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.File

/** Real Android MotionEvents exercise the ink input adapter, beyond the pure gesture reducer tests. */
class EditorGestureInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val root = File(targetContext.cacheDir, "editor-gestures-${System.nanoTime()}")
        val files = File(root, "files").apply { mkdirs() }
        val scratch = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
        }
        val results = ArrayList<String>()
        var failures = 0
        val frameClock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                delay(16)
                return onFrame(System.nanoTime())
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + frameClock)
        try {
            runBlocking {
                val meta = NoteStore.createBlank(scratch, "Gesture checks", "lined")
                val note = checkNotNull(NoteStore.load(scratch, meta.id))
                val state = withContext(Dispatchers.Main) { EditorState(scratch, note, emptyMap(), 1f, scope) }
                withContext(Dispatchers.Main) {
                    state.setViewport(600f, 500f)
                    val input = InkInput(state, exponentialDecay<Offset>())
                    val base = SystemClock.uptimeMillis()
                    fun event(action: Int, time: Long, buttons: Int = 0, tool: Int = MotionEvent.TOOL_TYPE_STYLUS,
                              x: Float = 200f, y: Float = 200f): MotionEvent {
                        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
                        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
                        return MotionEvent.obtain(base, base + time, action, 1, properties, coords, 0, buttons,
                            1f, 1f, 0, 0, if (tool == MotionEvent.TOOL_TYPE_FINGER) InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_STYLUS, 0)
                    }
                    fun hover(time: Long, pressed: Boolean) = event(MotionEvent.ACTION_HOVER_MOVE, time,
                        if (pressed) MotionEvent.BUTTON_STYLUS_PRIMARY else 0).let { e -> try { input.onHover(e) } finally { e.recycle() } }
                    fun touch(action: Int, time: Long, pressed: Boolean = false, tool: Int = MotionEvent.TOOL_TYPE_STYLUS,
                              x: Float = 200f, y: Float = 200f) = event(action, time,
                        if (pressed) MotionEvent.BUTTON_STYLUS_PRIMARY else 0, tool, x, y).let { e ->
                            try { input.onTouch(e) } finally { e.recycle() }
                        }
                    val pen = state.writerPen
                    hover(10, true)
                    check(state.displayTool == EditorTool.ERASER && state.tool == EditorTool.PEN)
                    hover(500, false)
                    check(state.displayTool == EditorTool.PEN && state.writerPen == pen)
                    results += "PASS: held-before-contact eraser returns to the unchanged pen preset"
                    hover(1000, true); hover(1030, false); hover(1100, true); hover(1130, false)
                    check(state.displayTool == EditorTool.ERASER)
                    hover(2000, true); hover(2030, false); hover(2100, true); hover(2130, false)
                    check(state.displayTool == EditorTool.PEN && state.writerPen == pen)
                    results += "PASS: real hover button edges double-toggle the visible pen/eraser mode"
                    touch(MotionEvent.ACTION_DOWN, 4000)
                    touch(MotionEvent.ACTION_MOVE, 4050, x = 260f, y = 225f)
                    touch(MotionEvent.ACTION_MOVE, 4100, true, x = 290f, y = 250f)
                    check(state.displayTool == EditorTool.ERASER)
                    touch(MotionEvent.ACTION_MOVE, 4600, x = 315f, y = 275f)
                    check(state.displayTool == EditorTool.PEN)
                    touch(MotionEvent.ACTION_MOVE, 4650, x = 350f, y = 300f)
                    touch(MotionEvent.ACTION_UP, 4700, x = 350f, y = 300f)
                    check(state.inkOf(note.pages.single().uid).strokes.size >= 2)
                    results += "PASS: button changes during contact split ink segments and restore drawing on release"
                    // The default page often fits on a phone/tablet; pulling still needs to append there.
                    state.setViewport(600f, 1500f)
                    state.panBy(0f, -100000f)
                    touch(MotionEvent.ACTION_DOWN, 6000, tool = MotionEvent.TOOL_TYPE_FINGER, x = 300f, y = 400f)
                    touch(MotionEvent.ACTION_MOVE, 6050, tool = MotionEvent.TOOL_TYPE_FINGER, x = 300f, y = 350f)
                    touch(MotionEvent.ACTION_UP, 6100, tool = MotionEvent.TOOL_TYPE_FINGER, x = 300f, y = 350f)
                    check(state.note.pages.size == 1)
                    touch(MotionEvent.ACTION_DOWN, 7000, tool = MotionEvent.TOOL_TYPE_FINGER, x = 300f, y = 400f)
                    touch(MotionEvent.ACTION_MOVE, 7050, tool = MotionEvent.TOOL_TYPE_FINGER, x = 300f, y = 180f)
                    touch(MotionEvent.ACTION_UP, 7100, tool = MotionEvent.TOOL_TYPE_FINGER, x = 300f, y = 180f)
                    check(state.note.pages.size == 2)
                    check(state.note.pages.last().template == "lined")
                    results += "PASS: short last-page pull adds nothing; a deliberate pull adds one matching page"
                }
                withContext(Dispatchers.Main) { state.saveNow(final = true) }?.await()
                check(NoteStore.load(scratch, meta.id)?.pages?.size == 2)
                results += "PASS: appended page persists through the normal editor save path"
            }
        } catch (e: Throwable) { failures++; results += "FAIL: ${e.stackTraceToString()}" }
        finally { scope.cancel(); runBlocking { NoteStore.writeMutex.lock(); try { root.deleteRecursively() } finally { NoteStore.writeMutex.unlock() } } }
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\n${results.joinToString("\n")}\nFailures: $failures\n")
        })
    }
}
