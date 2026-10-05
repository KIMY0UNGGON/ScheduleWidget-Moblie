package com.schedulewidget.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.notes.NoteStore
import kotlinx.coroutines.runBlocking

/** Tests the real editor's Activity/View key path after Android's system-level key interception. */
class EditorKeyUiInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        if (android.os.Build.VERSION.SDK_INT < 34) {
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "SKIP: stylus key codes require API 34+\n") })
            return
        }
        var failure: Throwable? = null
        var activity: Activity? = null
        var noteId: String? = null
        val repo = Repository.get(targetContext)
        val original = repo.data.value
        try {
            repo.update { it.copy(notes = it.notes.copy(enabled = true)) }
            val note = NoteStore.createBlank(targetContext, "Key bridge test", "plain")
            noteId = note.id
            activity = startActivitySync(
                Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_ROUTE, "Notes"),
            )
            await("test note in library") { nodes().any { it.text?.toString() == "Key bridge test" } }
            click(nodes().first { it.text?.toString() == "Key bridge test" })
            await("editor") { nodes().any { it.contentDescription?.toString() == "지우개" } }
            val editor = checkNotNull(activity)
            check(!eraserSelected()) { "Unexpected initial eraser mode" }

            fun key(action: Int, time: Long) {
                runOnMainSync {
                    // Activity is deliberately typed as the public platform API. The system consumes these codes
                    // on AOSP; dispatch here proves how the app handles them when a device delivers them.
                    editor.dispatchKeyEvent(KeyEvent(time, time, action, KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY, 0))
                }
            }
            fun doubleClick() {
                val base = SystemClock.uptimeMillis()
                key(KeyEvent.ACTION_DOWN, base)
                key(KeyEvent.ACTION_UP, base + 20)
                key(KeyEvent.ACTION_DOWN, base + 100)
                key(KeyEvent.ACTION_UP, base + 120)
            }
            doubleClick()
            await("eraser selected after double key press") { eraserSelected() }
            doubleClick()
            await("pen restored after second double key press") { !eraserSelected() }

            val hold = SystemClock.uptimeMillis() + 1000
            key(KeyEvent.ACTION_DOWN, hold)
            await("temporary eraser hold") { eraserSelected() }
            click(nodes().first { it.contentDescription?.toString() == "페이지" })
            await("page sheet") { nodes().none { it.contentDescription?.toString() == "지우개" } }
            key(KeyEvent.ACTION_UP, hold + 1000)
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            await("editor after closing page sheet") { nodes().any { it.contentDescription?.toString() == "지우개" } }
            await("hold cleared across modal") { !eraserSelected() }
        } catch (e: Throwable) {
            failure = e
        } finally {
            activity?.let { current -> runOnMainSync { current.finish() } }
            noteId?.let { id -> runBlocking { NoteStore.delete(targetContext, id) } }
            repo.update { original }
        }
        finish(
            if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply {
                putString("stream", failure?.stackTraceToString() ?: "PASS: real editor Activity key routing toggles pen/eraser and clears a hold across a modal\n")
            },
        )
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            waitForIdleSync()
            if (condition()) return
            Thread.sleep(80)
        }
        error("Timed out waiting for $label")
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            out += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        uiAutomation.rootInActiveWindow?.let(::walk)
        return out
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "No clickable parent" }
    }

    private fun eraserSelected(): Boolean {
        var node = nodes().firstOrNull { it.contentDescription?.toString() == "지우개" }
        repeat(3) {
            // Compose maps RadioButton selection to checked in Android accessibility.
            if (node?.isSelected == true || node?.isChecked == true) return true
            node = node?.parent
        }
        return false
    }
}
