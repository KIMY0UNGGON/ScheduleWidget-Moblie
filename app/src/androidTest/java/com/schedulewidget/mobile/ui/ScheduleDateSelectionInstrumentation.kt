package com.schedulewidget.mobile.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.schedulewidget.mobile.MainActivity
import com.schedulewidget.mobile.data.Repository
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

/** Checks the actual monthly grid and callbacks, including leap dates and inclusive ranges. */
class ScheduleDateSelectionInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val repo = Repository.get(targetContext)
        val original = repo.data.value
        var activity: MainActivity? = null
        val picked = AtomicReference<Pair<LocalDate, LocalDate>?>(null)
        val results = mutableListOf<String>()
        var failure: Throwable? = null
        try {
            repo.update { it.copy(floatingPet = false, miniCharacterVisible = false) }
            val host = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_ROUTE, Route.Mini.name)) as MainActivity
            activity = host
            fun show(initial: LocalDate, end: LocalDate? = null) {
                picked.set(null)
                val generation = System.nanoTime()
                runOnMainSync {
                    host.setContent {
                        AppTheme { key(generation) {
                            var open by remember { mutableStateOf(true) }
                            if (open) {
                                if (end == null) DatePickDialog(initial, onDismiss = { open = false }) { picked.set(it to it) }
                                else DateRangePickDialog(initial, end, onDismiss = { open = false }) { from, to -> picked.set(from to to) }
                            }
                        } }
                    }
                }
                await("monthly calendar") { nodes().any { it.text?.toString() == "확인" } && dayNode(28) != null }
                check(nodes().none { it.isEditable }) { "Date selection opened text inputs instead of a monthly calendar" }
                check(nodes().none { Regex("input mode|입력 모드", RegexOption.IGNORE_CASE).containsMatchIn(it.contentDescription?.toString().orEmpty()) }) {
                    "Calendar still exposes the typed-date mode toggle"
                }
                check(dayNode(29) != null) { "Initial February 2028 calendar must include leap day" }
            }
            show(LocalDate.of(2028, 2, 28))
            click(checkNotNull(dayNode(29)))
            clickText("확인")
            await("selected leap date") { picked.get() != null }
            check(picked.get() == (LocalDate.of(2028, 2, 29) to LocalDate.of(2028, 2, 29)))
            results += "PASS: monthly grid selects leap day without typed input or timezone date shift"

            show(LocalDate.of(2028, 2, 29))
            click(checkNotNull(dayNode(1)))
            clickText("취소")
            await("cancel closes picker") { nodes().none { it.text?.toString() == "확인" } }
            check(picked.get() == null)
            results += "PASS: reopening shows the selected month; cancel does not change the date"

            show(LocalDate.of(2028, 2, 5), LocalDate.of(2028, 2, 8))
            click(checkNotNull(dayNode(10)))
            click(checkNotNull(dayNode(13)))
            clickText("확인")
            await("selected inclusive range") { picked.get() != null }
            check(picked.get() == (LocalDate.of(2028, 2, 10) to LocalDate.of(2028, 2, 13))) { "Unexpected range: ${picked.get()}" }
            results += "PASS: monthly range selection retains inclusive start/end dates"
        } catch (error: Throwable) {
            failure = error
            File(targetContext.cacheDir, "date-picker-nodes.txt").writeText(nodes().joinToString("\n") {
                "text=${it.text}; description=${it.contentDescription}; clickable=${it.isClickable}; editable=${it.isEditable}"
            })
            uiAutomation.takeScreenshot()?.let { image ->
                File(targetContext.cacheDir, "date-picker-failure.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
        } finally {
            activity?.let { runOnMainSync { it.finish() } }
            repo.update { original }
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", results.joinToString("\n", postfix = "\n") + (failure?.stackTraceToString() ?: ""))
        })
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
        }
        uiAutomation.rootInActiveWindow?.let(::walk)
        return result
    }

    private fun dayNode(day: Int): AccessibilityNodeInfo? {
        val number = Regex("(?<!\\d)0?$day(?!\\d)")
        return nodes().firstOrNull {
            it.isClickable && it.isVisibleToUser && number.containsMatchIn("${it.text?.toString().orEmpty()} ${it.contentDescription?.toString().orEmpty()}")
        }
    }

    private fun clickText(text: String) {
        await("button $text") { nodes().any { it.text?.toString() == text } }
        click(nodes().first { it.text?.toString() == text })
    }

    private fun click(node: AccessibilityNodeInfo) {
        val rect = Rect().also(node::getBoundsInScreen)
        check(!rect.isEmpty) { "Calendar element has no touch bounds: ${node.text}" }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            check(uiAutomation.injectInputEvent(event, true))
            event.recycle()
            Thread.sleep(30)
        }
        waitForIdleSync()
        Thread.sleep(100)
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            waitForIdleSync()
            if (condition()) return
            Thread.sleep(80)
        }
        error("Timed out waiting for $label")
    }
}
