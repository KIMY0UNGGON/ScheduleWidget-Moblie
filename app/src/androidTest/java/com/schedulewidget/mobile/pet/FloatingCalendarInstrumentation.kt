package com.schedulewidget.mobile.pet

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.annotation.RequiresApi
import androidx.compose.ui.platform.ComposeView
import com.schedulewidget.mobile.MainActivity
import com.schedulewidget.mobile.data.AppData
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.ui.Route
import com.schedulewidget.mobile.ui.CALENDAR_FLIP_DURATION_MS
import com.schedulewidget.mobile.ui.calendarAnimationsEnabled
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt

/** Disposable-emulator check of real service windows; overlay/notification permissions are granted by the runner. */
class FloatingCalendarInstrumentation : Instrumentation() {
    private val range = Regex("(\\d{1,2})\\.(\\d{1,2})\\s*(?:—|-)\\s*(\\d{1,2})\\.(\\d{1,2})")
    private val dayLabel = Regex("^\\d{1,2}\\b")
    private var baselineWindows = emptySet<Int>()
    private var calendarWindow = -1
    private val dayHeight = 220
    private var bodyLeftInset = 0
    private var bodyRightInset = 0
    private var bodyBottomInset = 0
    private var bodyHeightPx = 0
    private var currentCalendarView: View? = null
    private var effectOrder = listOf(1, 2)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        effectOrder = arguments?.getString("effectOrder")?.split(',')?.map { it.trim().toInt() } ?: listOf(1, 2)
        require(effectOrder.isNotEmpty() && effectOrder.all { it in 1..6 }) { "effectOrder must contain effects 1–6" }
        start()
    }

    override fun onStart() {
        if (Build.VERSION.SDK_INT < 29) {
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "SKIP: WindowInspector requires API 29+\n") })
            return
        }
        val repo = Repository.get(targetContext)
        val original = repo.data.value
        val originalForeground = FloatingPet.appInForeground.value
        val results = mutableListOf<String>()
        var activity: Activity? = null
        var service: PetOverlayService? = null
        var failure: Throwable? = null
        try {
            check(FloatingPet.canDraw(targetContext)) { "Runner must grant SYSTEM_ALERT_WINDOW on the disposable emulator" }
            check(calendarAnimationsEnabled(targetContext)) { "Runner must enable animator, window, and transition scales for frame checks" }
            uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            repo.update {
                AppData().let { clean -> clean.copy(
                    floatingPet = true, miniCharacterVisible = false, miniFirstPetHidden = false,
                    miniCharacterScale = 50, floatingX = 24, floatingY = 80,
                    petCalendarWidth = 400, petCalendarHeight = dayHeight,
                    miniFlipEffect = effectOrder.first(),
                    stt = clean.stt.copy(enabled = false), notes = clean.notes.copy(enabled = false),
                ) }
            }
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ROUTE, Route.Mini.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync { FloatingPet.sync(targetContext); FloatingPet.appInForeground.value = false }
            await("floating service") {
                service = serviceViews().firstOrNull()?.context as? PetOverlayService
                var visible = false
                runOnMainSync { visible = service?.petWindows?.values?.any { it.view.isShown } == true }
                visible
            }
            runOnMainSync { checkNotNull(service).let { it.onPetTap(it.activePetIndex, 2) } }
            var panel: View? = null
            await("calendar overlay") {
                val theme = nodes().firstOrNull { it.contentDescription?.toString() == "테마 선택: Light" }
                if (theme != null) {
                    calendarWindow = theme.windowId
                    panel = calendarView()
                }
                panel != null
            }
            val calendar = checkNotNull(panel)
            runOnMainSync {
                val params = calendar.layoutParams as WindowManager.LayoutParams
                check(params.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                check(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            }
            awaitLayout(calendar)
            baselineWindows = uiAutomation.windows.map { it.id }.toSet()
            results += "PASS: calendar is a real TYPE_APPLICATION_OVERLAY / FLAG_NOT_FOCUSABLE service window"

            tap(bounds(control("테마 선택: Light")))
            await("service theme popup") { popupNodes().any { it.text?.toString() == "Blue" } }
            tap(bounds(popupNodes().first { it.text?.toString() == "Blue" }))
            await("Blue selection and popup dismissal") {
                repo.data.value.miniTheme == "blue" && popupNodes().none { it.text?.toString() == "Blue" } &&
                    hasControl("테마 선택: Blue")
            }
            measureBody(calendar)
            val body = bodyBounds(calendar)
            tap(bounds(control("테마 선택: Blue")))
            await("popup reopened for outside tap") { popupNodes().any { it.text?.toString() == "Blue" } }
            tap(Rect(body.right - 2, body.centerY(), body.right, body.centerY() + 2))
            await("outside-tap popup dismissal and Blue header") {
                popupNodes().none { it.text?.toString() == "Blue" } && hasControl("테마 선택: Blue")
            }
            tap(bounds(control("테마 선택: Blue")))
            await("popup reopened for Back") { popupNodes().any { it.text?.toString() == "Blue" } }
            check(uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            await("Back popup dismissal and Blue header") {
                popupNodes().none { it.text?.toString() == "Blue" } && hasControl("테마 선택: Blue")
            }
            check(!checkNotNull(activity).isFinishing && calendar.isAttachedToWindow) { "Popup dismissal leaked to the Activity or removed the calendar" }
            results += "PASS: service dropdown selects Blue, dismisses on outside tap, and consumes Back"

            val animationScale = Settings.Global.getFloat(targetContext.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            results += "ANIMATION SCALE: $animationScale; production duration=${CALENDAR_FLIP_DURATION_MS}ms; effectOrder=$effectOrder"
            var expected = LocalDate.now()
            for (effect in effectOrder) {
                repo.update { it.copy(miniFlipEffect = effect) }
                waitForIdleSync()
                SystemClock.sleep(100)
                await("effect $effect starting page", expected) { displays(expected) && hasControl("다음 7일") }
                awaitLayout(calendar)
                val measured = measureBody(calendar)
                results += "GEOMETRY: effect=$effect panel=${viewBounds(calendar)} body=$measured"
                val before = capture(calendar)
                val arrow = bounds(control("다음 7일"))
                val began = nativeTap(calendar, arrow, results)
                SystemClock.sleep(((80 * animationScale).roundToInt().toLong() - (SystemClock.uptimeMillis() - began)).coerceAtLeast(0))
                val early = capture(calendar)
                val earlyAt = SystemClock.uptimeMillis() - began
                SystemClock.sleep(((160 * animationScale).roundToInt().toLong() - earlyAt).coerceAtLeast(0))
                val mid = capture(calendar)
                val midAt = SystemClock.uptimeMillis() - began
                results += "TOUCH periods after early/mid frames: ${periodTexts()}"
                SystemClock.sleep(((CALENDAR_FLIP_DURATION_MS * animationScale).roundToInt() + 230L - (SystemClock.uptimeMillis() - began)).coerceAtLeast(0))
                expected = expected.plusDays(7)
                await("effect $effect final date", expected) { displays(expected) && hasControl("다음 7일") }
                awaitLayout(calendar)
                val after = capture(calendar)
                val frames = listOf("before" to before, "early-$earlyAt" to early, "mid-$midAt" to mid, "after" to after)
                try {
                    for ((label, image) in frames) {
                        val file = File(targetContext.cacheDir, "floating-calendar-effect-$effect-$label.png")
                        file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        results += "SCREENSHOT: ${file.absolutePath}"
                    }
                    check(!before.sameAs(after)) { "Effect $effect did not change the body to the next week" }
                    check(listOf(early, mid).any { !it.sameAs(before) && !it.sameAs(after) }) {
                        "Effect $effect has no distinct intermediate body frame: early=${earlyAt}ms, mid=${midAt}ms"
                    }
                    results += "PASS: service effect $effect has old/intermediate/new body frames (${earlyAt}ms, ${midAt}ms) and advances 7 days"
                } finally { frames.forEach { it.second.recycle() } }
            }
            if (Build.VERSION.SDK_INT >= 33) uiAutomation.setAnimationScale(1f)
            else check(animationScale == 1f) { "API 29–32 runner must use animation scale 1" }
            waitForIdleSync()
            SystemClock.sleep(100)
            awaitLayout(calendar)
            val previous = bounds(control("이전 7일"))
            val next = bounds(control("다음 7일"))
            rapidTaps(listOf(next, previous))
            SystemClock.sleep(750)
            await("rapid +7/-7 cancellation", expected) { displays(expected) }
            rapidTaps(listOf(next, next, next, previous))
            SystemClock.sleep(750)
            await("rapid latest date", expected.plusDays(14)) { displays(expected.plusDays(14)) }
            results += "PASS: service arrows settle correctly after rapid +7/-7 and next x3 / previous x1 at animation scale 1"
        } catch (e: Throwable) { failure = e }
        finally {
            runCatching { if (Build.VERSION.SDK_INT >= 33) uiAutomation.setAnimationScale(1f) }
            runCatching { runOnMainSync { service?.closePanel(); targetContext.stopService(Intent(targetContext, PetOverlayService::class.java)) } }
            runCatching { await("service shutdown") { service?.destroyed != false } }
            activity?.let { current -> runCatching { runOnMainSync { current.finish() } } }
            runOnMainSync { repo.update { original }; FloatingPet.appInForeground.value = originalForeground }
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", (results + listOfNotNull(failure?.stackTraceToString())).joinToString("\n") + "\n")
        })
    }

    @RequiresApi(29)
    private fun serviceViews(): List<View> {
        var views = emptyList<View>()
        runOnMainSync { views = WindowInspector.getGlobalWindowViews().filter { it is ComposeView && it.context is PetOverlayService && it.isAttachedToWindow } }
        return views
    }

    @RequiresApi(29)
    private fun calendarView(): View? {
        var found: View? = null
        val views = serviceViews()
        runOnMainSync { found = views.singleOrNull { it.createAccessibilityNodeInfo().windowId == calendarWindow } }
        currentCalendarView = found
        return found
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        // Read the live virtual tree after the service page recomposes.
        if (Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
        }
        uiAutomation.windows.forEach { it.root?.let(::walk) }
        return result
    }

    private fun popupNodes() = nodes().filter { it.windowId !in baselineWindows }
    private fun hasControl(description: String) = nodes().any {
        it.windowId == calendarWindow && it.contentDescription?.toString() == description
    }
    private fun control(description: String) = checkNotNull(nodes().firstOrNull {
        it.windowId == calendarWindow && it.contentDescription?.toString() == description
    }) { "Missing service control: $description" }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)

    private fun displays(start: LocalDate): Boolean = nodes().filter { it.windowId == calendarWindow }.any {
        val match = range.find(it.text?.toString().orEmpty()) ?: return@any false
        val end = start.plusDays(6)
        match.groupValues.drop(1).map(String::toInt) == listOf(start.monthValue, start.dayOfMonth, end.monthValue, end.dayOfMonth)
    }

    private fun periodTexts() = nodes().filter { it.windowId == calendarWindow }
        .mapNotNull { it.text?.toString() }.filter(range::containsMatchIn)

    private fun viewBounds(view: View): Rect {
        val location = IntArray(2)
        var result = Rect()
        runOnMainSync {
            check(view.createAccessibilityNodeInfo().windowId == calendarWindow) { "View does not own calendarWindow=$calendarWindow" }
            view.getLocationOnScreen(location)
            result = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }
        return result
    }

    private fun awaitLayout(view: View) {
        var previous: Rect? = null
        await("stable calendar window layout") {
            val current = viewBounds(view)
            val native = uiAutomation.windows.firstOrNull { it.id == calendarWindow }?.let { Rect().also(it::getBoundsInScreen) }
            var laidOut = false
            runOnMainSync { laidOut = view.isLaidOut && !view.isLayoutRequested }
            val stable = laidOut && current == native && current == previous
            previous = current
            stable
        }
    }

    private fun measureBody(view: View): Rect {
        val window = viewBounds(view)
        val current = nodes().filter { it.windowId == calendarWindow }
        val cells = current.mapNotNull { node ->
            val day = dayLabel.find(node.text?.toString()?.trim().orEmpty())?.value?.toIntOrNull() ?: return@mapNotNull null
            if (day !in 1..31) return@mapNotNull null
            var cell: AccessibilityNodeInfo? = node
            while (cell != null && !cell.isClickable) cell = cell.parent
            cell?.let(::bounds)?.takeIf { it.height() >= dayHeight * view.resources.displayMetrics.density * 0.9f }
        }.distinct()
        check(cells.isNotEmpty()) { "No real day-cell bounds; texts=${current.map { it.text }}" }
        val body = Rect().apply { cells.forEach(::union) }
        val headerBottom = current.filter { it.contentDescription != null }.maxOf { bounds(it).bottom }
        check(window.contains(body) && body.top >= headerBottom) { "Body overlaps header or leaves calendar: window=$window, body=$body, headerBottom=$headerBottom" }
        bodyLeftInset = body.left - window.left
        bodyRightInset = window.right - body.right
        bodyBottomInset = window.bottom - body.bottom
        bodyHeightPx = body.height()
        return body
    }

    private fun bodyBounds(view: View): Rect = viewBounds(view).let { window ->
        check(bodyHeightPx > 0)
        Rect(window.left + bodyLeftInset, window.bottom - bodyBottomInset - bodyHeightPx,
            window.right - bodyRightInset, window.bottom - bodyBottomInset)
    }

    private fun capture(view: View): Bitmap {
        val body = bodyBounds(view)
        val screen = checkNotNull(uiAutomation.takeScreenshot())
        try {
            check(body.left >= 0 && body.top >= 0 && body.right <= screen.width && body.bottom <= screen.height) { "Body outside screen: $body" }
            val crop = Bitmap.createBitmap(screen, body.left, body.top, body.width(), body.height())
            try { return checkNotNull(crop.copy(Bitmap.Config.ARGB_8888, false)) }
            finally { if (crop !== screen) crop.recycle() }
        } finally { screen.recycle() }
    }

    private fun tap(rect: Rect) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(uiAutomation.injectInputEvent(event, true)) { "Could not tap service window at $rect" } }
            finally { event.recycle() }
        }
    }

    private fun nativeTap(view: View, rect: Rect, trace: MutableList<String>): Long {
        trace += "TOUCH before native input: periods=${periodTexts()}, view=${viewBounds(view)}, arrow=$rect"
        val began = SystemClock.uptimeMillis()
        val output = ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(
            "input touchscreen tap ${rect.exactCenterX()} ${rect.exactCenterY()}",
        )).use { it.readBytes().toString(Charsets.UTF_8) }
        check(output.isBlank()) { "Native input tap failed: $output" }
        trace += "TOUCH native input completed at ${SystemClock.uptimeMillis() - began}ms"
        return began
    }

    private fun rapidTaps(controls: List<Rect>) {
        for (rect in controls) {
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try { check(uiAutomation.injectInputEvent(event, false)) { "Native service arrow input failed at $rect" } }
                finally { event.recycle() }
                if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(20)
            }
        }
    }

    private fun await(label: String, expected: LocalDate? = null, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(60)
        }
        val calendarNodes = nodes().filter { it.windowId == calendarWindow }
        val descriptions = calendarNodes.mapNotNull { it.contentDescription?.toString() }
        val periods = calendarNodes.mapNotNull { it.text?.toString() }.filter(range::containsMatchIn)
        val view = runCatching { currentCalendarView?.let(::viewBounds) }.getOrNull()
        val native = uiAutomation.windows.firstOrNull { it.id == calendarWindow }?.let { Rect().also(it::getBoundsInScreen) }
        error("Timed out waiting for $label: theme=${Repository.get(targetContext).data.value.miniTheme}, " +
            "foreground=${FloatingPet.appInForeground.value}, windows=${uiAutomation.windows.map { it.id }}, " +
            "calendarWindow=$calendarWindow, viewBounds=$view, nativeBounds=$native, expectedDate=$expected, " +
            "actualPeriods=$periods, calendarControls=$descriptions")
    }
}
