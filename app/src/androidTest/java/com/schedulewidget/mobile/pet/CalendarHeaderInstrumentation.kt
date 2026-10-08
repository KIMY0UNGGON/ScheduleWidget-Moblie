package com.schedulewidget.mobile.pet

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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
import com.schedulewidget.mobile.ui.KoreanHolidays
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
                assertDayTextCentered(width, metrics.widthPixels, metrics.density)
                check(nodes().none { it.text?.toString() == "일정 없음" }) { "Empty calendar days should stay blank" }
                results += "PASS: header order, touch targets, Today slot, centered day text, and blank days at ${width}dp / fontScale=$fontScale"
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

            // Compact 280dp / 1.5 header: its order, touch targets, and Today slot are checked in the loop above.
            showCalendar(host, 280, 1.5f, opened, added, closed, rendered)
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

            clickToday()
            await("Today returns to LocalDate.now") { displaysPage(LocalDate.now()) }
            clickDescription("테마 선택: Blue")
            await("add menu after Today") { nodes().any { it.text?.toString() == "일정 추가" } }
            clickText("일정 추가")
            await("add receives today") { added.lastOrNull() == LocalDate.now() }
            results += "PASS: Today returns the period to LocalDate.now and add receives today"

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
        var issue = "header not rendered"
        val metrics = targetContext.resources.displayMetrics
        runCatching {
            await("calendar header at ${widthDp}dp / $fontScale") {
                val size = rendered.get()
                size != null && size.widthPx == (widthDp * metrics.density).roundToInt() && size.fontScale == fontScale &&
                    headerIssue(widthDp, metrics.widthPixels, metrics.density).also { issue = it ?: "none" } == null
            }
        }.onFailure { error("${it.message}; last header check: $issue") }
    }

    /** Reading order of the header actions: Today takes app-open's old slot, and app-open sits immediately left of Settings. */
    private val headerActions = listOf("이전 7일", "테마 선택", "오늘로 이동", "앱 열기", "설정", "다음 7일")

    private fun headerNode(action: String): AccessibilityNodeInfo? = nodes().firstOrNull {
        val description = it.contentDescription?.toString().orEmpty()
        if (action == "테마 선택") description.startsWith(action) else description == action || it.text?.toString() == action
    }

    /** Bounds of the clickable ancestor: the 48dp touch target, not the 24dp icon that carries the description. */
    private fun touchBounds(node: AccessibilityNodeInfo): Rect {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return bounds(target ?: node)
    }

    /** The first header expectation that fails at this width, or null when the layout matches. */
    private fun headerIssue(widthDp: Int, screenWidthPx: Int, density: Float): String? {
        val panelWidthPx = (widthDp * density).roundToInt()
        val panelLeft = (screenWidthPx - panelWidthPx) / 2
        val cardLeft = panelLeft + 10 * density
        val cardRight = panelLeft + panelWidthPx - 10 * density
        val period = periodNode()?.let { bounds(it) } ?: return "date period is missing"
        if (abs(period.centerX() - screenWidthPx / 2) > 5 * density) return "date period is not centered: $period"
        val touch = mutableMapOf<String, Rect>()
        for (action in headerActions) touch[action] = touchBounds(headerNode(action) ?: return "missing control: $action")
        for ((action, rect) in touch) {
            if (rect.width() < 48 * density - 1 || rect.height() < 48 * density - 1) return "$action touch target is under 48dp: $rect"
            if (rect.left < cardLeft || rect.right > cardRight) return "$action is outside the card: $rect"
        }
        val entries = touch.entries.toList()
        for (i in entries.indices) for (j in i + 1 until entries.size) {
            if (Rect.intersects(entries[i].value, entries[j].value)) {
                return "${entries[i].key} overlaps ${entries[j].key}: ${entries[i].value} / ${entries[j].value}"
            }
        }
        for ((before, after) in listOf("테마 선택", "오늘로 이동", "앱 열기", "설정").zipWithNext()) {
            val a = touch.getValue(before)
            val b = touch.getValue(after)
            val sameRow = a.top < b.bottom && b.top < a.bottom
            val ordered = if (sameRow) b.centerX() > a.centerX() else b.centerY() > a.centerY()
            if (!ordered) return "$after is not after $before: $a / $b"
        }
        val app = touch.getValue("앱 열기")
        val settings = touch.getValue("설정")
        val gap = settings.left - app.right
        if (app.top >= settings.bottom || settings.top >= app.bottom || gap !in -1..(2 * density).roundToInt()) {
            return "app-open is not immediately left of Settings: $app / $settings"
        }
        // App-open's old slot: 10dp card padding + 4dp header padding + previous + theme + half of Today.
        val today = touch.getValue("오늘로 이동")
        val todaySlot = if (widthDp < 316) 86 else 134
        if (abs(today.centerX() - (panelLeft + todaySlot * density)) > 8 * density) return "Today is not beside the theme control: $today"
        if (widthDp >= 380) {
            // Single-row layout (OverlayCalendar's compact threshold): each control keeps a fixed slot from the edges.
            val slots = mapOf("이전 7일" to 38, "테마 선택" to 86, "오늘로 이동" to 134, "앱 열기" to widthDp - 134, "설정" to widthDp - 86, "다음 7일" to widthDp - 38)
            for ((action, slot) in slots) {
                val rect = touch.getValue(action)
                if (abs(rect.centerX() - (panelLeft + slot * density)) > 8 * density) return "$action is off its ${slot}dp slot: $rect"
            }
        }
        return null
    }

    private fun assertHeaderLayout(widthDp: Int, screenWidthPx: Int, density: Float) {
        val issue = headerIssue(widthDp, screenWidthPx, density)
        check(issue == null) { "Header at ${widthDp}dp: $issue" }
        check(nodes().none { it.text?.toString() == "일정 없음" }) { "Empty days should not show placeholder text" }
    }

    /** Day numbers and weekdays are centered in each fully visible day cell: cell edges come from accessibility, the ink from a screenshot. */
    private fun assertDayTextCentered(widthDp: Int, screenWidthPx: Int, density: Float) {
        val start = currentPageStart()
        val screen = checkNotNull(uiAutomation.takeScreenshot()) { "Screenshot unavailable for the day-centering check" }
        val image = checkNotNull(screen.copy(Bitmap.Config.ARGB_8888, false))
        screen.recycle()
        try {
            val panelLeft = (screenWidthPx - (widthDp * density).roundToInt()) / 2
            // 10dp card padding + 8dp row padding bound the strip that compact widths scroll; partly visible cells are skipped.
            val visibleLeft = panelLeft + 18 * density
            val visibleRight = panelLeft + (widthDp - 18) * density
            var checked = 0
            for (offset in 0L until 7L) {
                val day = start.plusDays(offset)
                // A holiday name is extra start-aligned text in the same cell, so its ink would skew the center.
                if (KoreanHolidays.nameOf(day) != null) continue
                // Compact widths scroll the strip, so a cell outside the viewport may not be exposed at all.
                val cell = dayCell(day)
                if (cell == null) { check(widthDp < 380) { "Missing day cell for $day at ${widthDp}dp" }; continue }
                val rect = bounds(cell)
                if (rect.left < visibleLeft - 2 || rect.right > visibleRight + 2) continue
                val background = image.getPixel(rect.left + (2 * density).roundToInt(), rect.centerY())
                val inset = (10 * density).roundToInt() // rounded cell corners
                var inkLeft = Int.MAX_VALUE
                var inkRight = Int.MIN_VALUE
                for (y in rect.top + inset until rect.bottom - inset) for (x in rect.left + 1 until rect.right - 1) {
                    if (differs(image.getPixel(x, y), background)) {
                        inkLeft = minOf(inkLeft, x)
                        inkRight = maxOf(inkRight, x)
                    }
                }
                check(inkRight >= inkLeft) { "No day text found in the $day cell at ${widthDp}dp: $rect" }
                check(abs((inkLeft + inkRight) / 2f - rect.centerX()) <= 3 * density) {
                    "Day text for $day is not centered at ${widthDp}dp: ink $inkLeft..$inkRight in $rect"
                }
                checked++
            }
            check(checked > 0) { "No fully visible day cell was checked at ${widthDp}dp" }
        } finally {
            image.recycle()
        }
    }

    /** The clickable ancestor of the node whose text starts with the day of month; the period "10.8 — 10.14" does not match. */
    private fun dayCell(day: LocalDate): AccessibilityNodeInfo? {
        val label = Regex("^${day.dayOfMonth}(?![\\d.])")
        val text = nodes().firstOrNull { label.containsMatchIn(it.text?.toString()?.trim().orEmpty()) } ?: return null
        var cell: AccessibilityNodeInfo? = text
        while (cell != null && !cell.isClickable) cell = cell.parent
        return cell
    }

    private fun differs(a: Int, b: Int): Boolean =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b)) > 90

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

    private fun clickToday() = click(
        awaitNode("control: 오늘로 이동") { it.contentDescription?.toString() == "오늘로 이동" },
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
