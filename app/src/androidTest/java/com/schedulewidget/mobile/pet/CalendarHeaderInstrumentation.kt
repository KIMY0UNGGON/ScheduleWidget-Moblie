package com.schedulewidget.mobile.pet

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.MainActivity
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.ui.AppTheme
import com.schedulewidget.mobile.ui.MiniThemes
import com.schedulewidget.mobile.ui.Route
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/** Real MainActivity-hosted UI checks for the floating calendar header and the public updater section. */
class CalendarHeaderInstrumentation : Instrumentation() {
    private data class RenderedCalendar(val widthPx: Int, val fontScale: Float)

    private val rangePattern = Regex("(\\d{1,2})\\.(\\d{1,2})\\s*(?:—|-)\\s*(\\d{1,2})\\.(\\d{1,2})")

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val repo = Repository.get(targetContext)
        val original = repo.data.value
        val runId = System.nanoTime().toString(36)
        val screenshots = mutableListOf<File>()
        val results = mutableListOf<String>()
        val opened = CopyOnWriteArrayList<Route>()
        val added = CopyOnWriteArrayList<LocalDate>()
        val closed = AtomicBoolean(false)
        val rendered = AtomicReference<RenderedCalendar?>(null)
        var activity: Activity? = null
        var failure: Throwable? = null

        try {
            repo.update {
                it.copy(miniTheme = "paper", miniFlipEffect = 0, schedules = emptyList(), calendar = it.calendar.copy(showEvents = false))
            }
            activity = startActivitySync(
                Intent(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra(MainActivity.EXTRA_ROUTE, Route.Mini.name),
            )
            val host = activity as MainActivity
            val metrics = targetContext.resources.displayMetrics
            val screenWidthDp = (metrics.widthPixels / metrics.density).toInt()
            check(screenWidthDp >= 280) { "UI test device must be at least 280dp wide: ${screenWidthDp}dp" }
            val widths = mutableListOf(280, 320, 400)
            if (screenWidthDp >= OVERLAY_CALENDAR_FULL_WIDTH_DP) widths += OVERLAY_CALENDAR_FULL_WIDTH_DP
            if (OVERLAY_CALENDAR_FULL_WIDTH_DP !in widths)
                results += "SKIP: tablet-width check needs ${OVERLAY_CALENDAR_FULL_WIDTH_DP}dp; device=${screenWidthDp}dp"

            for (width in widths) for (fontScale in listOf(1f, 1.5f)) {
                showCalendar(host, width, fontScale, opened, added, closed, rendered)
                assertHeaderLayout(width, metrics.widthPixels, metrics.density)
                check(nodes().none { it.text?.toString() == "일정 없음" }) { "Empty calendar days should stay blank" }
                results += "PASS: centered header and blank days at ${width}dp / fontScale=$fontScale"
                if ((width == 280 && fontScale == 1.5f) || (width == 400 && fontScale == 1f)) {
                    val image = checkNotNull(uiAutomation.takeScreenshot())
                    val file = File(targetContext.cacheDir, "calendar-header-$width-${fontScale.toString().replace('.', '_')}-$runId.png")
                    FileOutputStream(file).use { out ->
                        check(image.compress(Bitmap.CompressFormat.PNG, 100, out)) { "Could not write calendar screenshot" }
                    }
                    image.recycle()
                    assertNoCornerMarks(host, file, width, metrics.density)
                    screenshots += file
                }
            }

            // Compact controls: the theme menu is left, app-open beside it, Settings stays on the right.
            showCalendar(host, 280, 1.5f, opened, added, closed, rendered)
            val theme = nodes().firstOrNull { it.contentDescription?.toString()?.startsWith("테마 선택") == true }
            val appOpen = nodes().firstOrNull { it.contentDescription?.toString() == "앱 열기" }
            val settings = nodes().firstOrNull { it.contentDescription?.toString() == "설정" }
            check(theme != null && appOpen != null && settings != null) { "Compact header must keep theme, app-open, and Settings controls" }
            val themeBounds = bounds(theme!!)
            val appBounds = bounds(appOpen!!)
            val settingsBounds = bounds(settings!!)
            check(themeBounds.centerX() < appBounds.centerX() && appBounds.centerX() < settingsBounds.centerX()) {
                "Compact header controls are out of order"
            }
            clickDescription("테마 선택: Light")
            await("theme menu") { nodes().any { it.text?.toString() == "Blue" } }
            clickText("Blue")
            await("theme persistence and header update") {
                repo.data.value.miniTheme == "blue" &&
                    nodes().any { it.contentDescription?.toString() == "테마 선택: Blue" }
            }
            results += "PASS: theme selection persists and updates the compact header"

            val start = currentPageStart()
            val nextButton = checkNotNull(nodes().firstOrNull { it.contentDescription?.toString() == "다음 7일" }) {
                "Missing next-week control"
            }
            tapRepeatedly(host, nextButton, 3)
            await("three next-week actions") { displaysPage(start.plusDays(21)) }
            clickDescription("이전 7일")
            await("one previous-week action") { displaysPage(start.plusDays(14)) }
            results += "PASS: rapid next ×3 and previous ×1 advance the period by 14 days"

            clickDescription("앱 열기")
            await("app-open callback") { opened.lastOrNull() == Route.Mini }
            clickDescription("설정")
            await("Settings callback") { opened.lastOrNull() == Route.Settings }
            clickDescription("테마 선택: Blue")
            await("compact menu actions") { nodes().any { it.text?.toString() == "달력 닫기" } }
            clickText("일정 추가")
            await("add callback and restored theme control") {
                added.lastOrNull() == start.plusDays(14) &&
                    repo.data.value.miniTheme == "blue" &&
                    nodes().any { it.contentDescription?.toString() == "테마 선택: Blue" }
            }
            clickDescription("테마 선택: Blue")
            await("close popup action") { nodes().any { it.text?.toString() == "달력 닫기" } }
            clickText("달력 닫기")
            await("close callback") { closed.get() }
            results += "PASS: app-open, Settings, add, and close callbacks"

            val monthStart = start.plusDays(14)
            val monthSteps = (0..13).first { offset ->
                val page = monthStart.plusDays(offset * 7L)
                page.month != page.plusDays(6).month || page.year != page.plusDays(6).year
            }
            repeat(monthSteps) { clickDescription("다음 7일") }
            val crossingMonth = monthStart.plusDays(monthSteps * 7L)
            await("month-crossing week") { displaysPage(crossingMonth) }
            check(crossingMonth.month != crossingMonth.plusDays(6).month || crossingMonth.year != crossingMonth.plusDays(6).year) {
                "Expected a displayed week that crosses a month boundary"
            }
            results += "PASS: period navigation crosses a month boundary with flip effect 0"

            repo.update { original }
            val calendarActivity = checkNotNull(activity)
            runOnMainSync { calendarActivity.finish() }
            activity = startActivitySync(
                Intent(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra(MainActivity.EXTRA_ROUTE, Route.Settings.name),
            )
            awaitUpdaterVersion()
            val settingsNodes = nodes()
            check(settingsNodes.any { it.text?.toString()?.startsWith("Ver 0.1") == true }) {
                "Settings did not show the Ver 0.1 public updater"
            }
            check(settingsNodes.any { it.text?.toString() == "업데이트 확인" }) { "Public update check control is missing" }
            check(settingsNodes.none {
                it.text?.toString()?.contains("비공개 저장소 접근") == true ||
                    it.contentDescription?.toString()?.contains("비공개 저장소 접근") == true
            }) { "Private-repository access UI is still present" }
            check(settingsNodes.none { it.className?.toString()?.contains("EditText", ignoreCase = true) == true }) {
                "Settings still exposes a token input field"
            }
            results += "PASS: public updater shows Ver 0.1 without private-token controls; update check was not tapped"
        } catch (e: Throwable) {
            failure = e
        } finally {
            activity?.let { runCatching { runOnMainSync { it.finish() } } }
            repo.update { original }
        }

        finish(
            if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply {
                putString("stream", buildString {
                    results.forEach { append(it).append('\n') }
                    screenshots.forEach { append("SCREENSHOT: ").append(it.absolutePath).append('\n') }
                    failure?.let { append(it.stackTraceToString()).append('\n') }
                })
            },
        )
    }

    private fun showCalendar(
        activity: ComponentActivity,
        widthDp: Int,
        fontScale: Float,
        opened: MutableList<Route>,
        added: MutableList<LocalDate>,
        closed: AtomicBoolean,
        rendered: AtomicReference<RenderedCalendar?>,
    ) {
        rendered.set(null)
        runOnMainSync {
            activity.setContent {
                AppTheme {
                    val baseDensity = LocalDensity.current
                    val testDensity = Density(baseDensity.density, fontScale)
                    CompositionLocalProvider(LocalDensity provides testDensity) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.requiredWidth(widthDp.dp).onGloballyPositioned {
                                rendered.set(RenderedCalendar(it.size.width, testDensity.fontScale))
                            }) {
                                OverlayCalendar(
                                    onClose = { closed.set(true) },
                                    onOpenApp = { opened += it },
                                    onAdd = { added += it },
                                    onResize = { _, _, _ -> },
                                    onMove = { _, _ -> },
                                )
                            }
                        }
                    }
                }
            }
        }
        waitForIdleSync()
        await("calendar header at ${widthDp}dp / $fontScale") {
            val size = rendered.get()
            size != null && size.widthPx == (widthDp * targetContext.resources.displayMetrics.density).roundToInt() &&
                size.fontScale == fontScale && hasExpectedControlBounds(widthDp, targetContext.resources.displayMetrics.widthPixels,
                    targetContext.resources.displayMetrics.density)
        }
    }

    private fun hasExpectedControlBounds(widthDp: Int, screenWidthPx: Int, density: Float): Boolean {
        val panelWidthPx = (widthDp * density).roundToInt()
        val panelLeft = (screenWidthPx - panelWidthPx) / 2f
        fun near(description: String, offsetDp: Float): Boolean {
            val node = nodes().firstOrNull { it.contentDescription?.toString() == description } ?: return false
            val expected = panelLeft + offsetDp * density
            return abs(bounds(node).centerX().toFloat() - expected) <= 8f * density
        }
        val center = screenWidthPx / 2f
        val period = periodNode()?.let { bounds(it).centerX().toFloat() } ?: return false
        val theme = nodes().firstOrNull { it.contentDescription?.toString()?.startsWith("테마 선택") == true } ?: return false
        val app = nodes().firstOrNull { it.contentDescription?.toString() == "앱 열기" } ?: return false
        return near("이전 7일", 38f) && near("앱 열기", 134f) && near("설정", widthDp - 86f) &&
            near("다음 7일", widthDp - 38f) && abs(period - center) <= 8f * density &&
            bounds(theme).centerX() < bounds(app).centerX()
    }

    private fun assertHeaderLayout(widthDp: Int, screenWidthPx: Int, density: Float) {
        val period = checkNotNull(periodNode()) { "Displayed date period is missing" }
        val theme = nodes().first { it.contentDescription?.toString()?.startsWith("테마 선택") == true }
        val appOpen = nodes().first { it.contentDescription?.toString() == "앱 열기" }
        val settings = nodes().first { it.contentDescription?.toString() == "설정" }
        val previous = nodes().first { it.contentDescription?.toString() == "이전 7일" }
        val next = nodes().first { it.contentDescription?.toString() == "다음 7일" }
        val periodBounds = bounds(period)
        val themeBounds = bounds(theme)
        val appOpenBounds = bounds(appOpen)
        val settingsBounds = bounds(settings)
        val previousBounds = bounds(previous)
        val nextBounds = bounds(next)
        check(abs(periodBounds.centerX() - screenWidthPx / 2) <= (5 * density).roundToInt()) {
            "Date period is not centered in the panel at ${widthDp}dp: $periodBounds"
        }
        check(previousBounds.centerX() < themeBounds.centerX() && themeBounds.centerX() < appOpenBounds.centerX() &&
            appOpenBounds.centerX() < periodBounds.centerX() && periodBounds.centerX() < settingsBounds.centerX() &&
            settingsBounds.centerX() < nextBounds.centerX()) {
            "Header controls are not ordered around the centered period at ${widthDp}dp"
        }
        check(hasExpectedControlBounds(widthDp, screenWidthPx, density)) {
            "Header accessibility bounds do not match the requested ${widthDp}dp layout"
        }
        check(nodes().none { it.text?.toString() == "일정 없음" }) { "Empty days should not show placeholder text" }
    }

    private fun assertNoCornerMarks(activity: MainActivity, screenshot: File, widthDp: Int, density: Float) {
        val bitmap = checkNotNull(android.graphics.BitmapFactory.decodeFile(screenshot.path))
        try {
            val content = activity.findViewById<View>(android.R.id.content)
            val location = IntArray(2)
            runOnMainSync { content.getLocationOnScreen(location) }
            val widthPx = (widthDp * density).roundToInt()
            val left = location[0] + (content.width - widthPx) / 2
            val y = location[1] + (7 * density).roundToInt()
            val inset = (13 * density).roundToInt()
            val mark = MiniThemes.of(Repository.get(targetContext).data.value.miniTheme).muted.toArgb()
            check(bitmap.getPixel(left + inset, y) != mark && bitmap.getPixel(left + widthPx - inset, y) != mark) {
                "A resize-corner mark is visible in the calendar header at ${widthDp}dp"
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun awaitUpdaterVersion() {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            waitForIdleSync()
            if (nodes().any { it.text?.toString()?.startsWith("Ver 0.1") == true }) return
            nodes().filter { it.isScrollable && it.isEnabled }.forEach { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            Thread.sleep(100)
        }
        error("Timed out waiting for the public updater version")
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

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            result += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        uiAutomation.rootInActiveWindow?.let(::walk)
        return result
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)

    private fun periodNode(): AccessibilityNodeInfo? = nodes().firstOrNull {
        rangePattern.containsMatchIn(it.text?.toString().orEmpty()) ||
            rangePattern.containsMatchIn(it.contentDescription?.toString().orEmpty())
    }

    private fun currentPageStart(): LocalDate {
        val node = checkNotNull(periodNode()) { "Displayed date period is missing" }
        val text = node.text?.toString() ?: node.contentDescription?.toString().orEmpty()
        val match = checkNotNull(rangePattern.find(text)) { "Date period is not parseable: $text" }
        val month = match.groupValues[1].toInt()
        val day = match.groupValues[2].toInt()
        val today = LocalDate.now()
        return (today.year - 1..today.year + 1).mapNotNull { year ->
            runCatching { LocalDate.of(year, month, day) }.getOrNull()
        }.minByOrNull { abs(it.toEpochDay() - today.toEpochDay()) } ?: error("Invalid date period: $text")
    }

    private fun displaysPage(start: LocalDate): Boolean {
        val node = periodNode() ?: return false
        val text = node.text?.toString() ?: node.contentDescription?.toString().orEmpty()
        val match = rangePattern.find(text) ?: return false
        val end = start.plusDays(6)
        return match.groupValues[1].toInt() == start.monthValue && match.groupValues[2].toInt() == start.dayOfMonth &&
            match.groupValues[3].toInt() == end.monthValue && match.groupValues[4].toInt() == end.dayOfMonth
    }

    private fun clickDescription(description: String) = click(
        awaitNode("control: $description") { it.contentDescription?.toString() == description },
    )

    private fun clickText(text: String) = click(
        awaitNode("text: $text") { it.text?.toString() == text },
    )

    private fun awaitNode(label: String, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            waitForIdleSync()
            nodes().firstOrNull(matches)?.let { return it }
            Thread.sleep(80)
        }
        error("Timed out waiting for $label")
    }

    private fun tapRepeatedly(activity: ComponentActivity, node: AccessibilityNodeInfo, count: Int) {
        val button = bounds(node)
        val origin = IntArray(2)
        runOnMainSync { activity.window.decorView.getLocationOnScreen(origin) }
        val x = (button.centerX() - origin[0]).toFloat()
        val y = (button.centerY() - origin[1]).toFloat()
        runOnMainSync {
            var time = SystemClock.uptimeMillis()
            repeat(count) {
                val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                check(activity.dispatchTouchEvent(down)) { "Next-week touch down was not handled" }
                down.recycle()
                time += 20
                val up = MotionEvent.obtain(time - 20, time, MotionEvent.ACTION_UP, x, y, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                check(activity.dispatchTouchEvent(up)) { "Next-week touch up was not handled" }
                up.recycle()
                time += 20
            }
        }
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return

        // Compose can expose an Icon's contentDescription without its merged IconButton semantics parent.
        val rect = bounds(node)
        val x = rect.centerX().toFloat()
        val y = rect.centerY().toFloat()
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        check(uiAutomation.injectInputEvent(down, true)) { "Could not inject tap down at $x,$y" }
        down.recycle()
        val up = MotionEvent.obtain(time, time + 40, MotionEvent.ACTION_UP, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        check(uiAutomation.injectInputEvent(up, true)) { "Could not inject tap up at $x,$y" }
        up.recycle()
    }
}
