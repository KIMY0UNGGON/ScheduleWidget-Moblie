package com.schedulewidget.mobile.pet

import android.app.Activity
import android.app.Instrumentation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import com.schedulewidget.mobile.MainActivity
import com.schedulewidget.mobile.data.AppData
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.data.MiniCharacterSlot
import com.schedulewidget.mobile.ui.Route
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Actual WindowManager routing to a different UID: the in-app Compose fixture cannot prove this behavior. */
class FloatingPetBoundsInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val repo = Repository.get(targetContext)
        val original = repo.data.value
        val foreground = FloatingPet.appInForeground.value
        val folder = File(PetImport.dir(targetContext), "floating-bounds-${System.nanoTime()}")
        val results = mutableListOf<String>()
        var activity: Activity? = null
        var failure: Throwable? = null
        try {
            uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            check(folder.mkdirs())
            val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            for (y in 30 until 90) for (x in 10 until 70) image.setPixel(x, y, Color.MAGENTA)
            for (y in 50 until 65) for (x in 30 until 50) image.setPixel(x, y, Color.TRANSPARENT)
            File(folder, "image.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            image.recycle()
            File(folder, "pet.json").writeText("""{"displayName":"Floating bounds","imagePath":"image.png"}""")
            Characters.invalidate()
            repo.update { AppData().copy(characterManifest = "pet:${folder.name}", miniCharacterVisible = false,
                floatingPet = true, floatingX = 400, floatingY = 400, miniCharacterScale = 100) }
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_ROUTE, Route.Mini.name))
            runOnMainSync { FloatingPet.sync(targetContext); FloatingPet.appInForeground.value = false }
            targetContext.startActivity(backgroundIntent())
            await("different-UID background") { backgroundTaps() == 0 }
            await("real floating pet") { service()?.petWindows?.get(0)?.view?.isShown == true && painted() != null }
            val host = checkNotNull(service())
            val density = targetContext.resources.displayMetrics.density
            val tolerance = (2 * density).roundToInt()
            for (flip in listOf(false, true)) for (scale in listOf(50, 100, 150)) {
                repo.update { it.copy(miniFirstPetFlipped = flip, miniCharacterScale = scale, floatingX = 400, floatingY = 400,
                    petPositions = emptyMap()) }
                await("scale $scale / flip $flip") {
                    val pet = host.petWindows.getValue(0)
                    val location = IntArray(2)
                    runOnMainSync { pet.view.getLocationOnScreen(location) }
                    painted()?.let { abs(it.height() - 66 * scale / 100f * density) <= tolerance &&
                        pet.params.y == 400 && abs(it.top - location[1] - pet.view.height * .3f) <= tolerance } == true
                }
                val body = checkNotNull(painted())
                val startX = body.exactCenterX()
                val startY = body.top + body.height() * .8f
                drag(startX, startY, startX, 1f)
                await("negative floating Y / scale $scale / flip $flip") { host.petWindows.getValue(0).params.y < 0 }
                val pet = host.petWindows.getValue(0)
                val origin = IntArray(2)
                runOnMainSync { pet.view.getLocationOnScreen(origin) }
                val metrics = targetContext.getSystemService(WindowManager::class.java).currentWindowMetrics
                val wall = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
                check(abs(origin[1] - wall - pet.params.y) <= 1) { "WindowManager moved the native pet away from its requested position" }
                await("paint reaches usable top") { painted()?.let { abs(it.top - wall) <= tolerance } == true }
                await("negative Y survives drag release") { repo.data.value.floatingY == pet.params.y }
                repo.update { it.copy(miniBlockDDayVisible = !it.miniBlockDDayVisible) }
                await("saved anchor after repository update") { painted()?.let { abs(it.top - wall) <= tolerance } == true }
                results += "PASS: native top edge and saved negative position / scale=$scale / flipped=$flip"
            }
            repo.update { it.copy(miniCharacterScale = 100, miniFirstPetFlipped = false, floatingX = 400, floatingY = 400,
                petPositions = emptyMap()) }
            await("pet centered for native input") {
                val pet = host.petWindows.getValue(0)
                val location = IntArray(2)
                runOnMainSync { pet.view.getLocationOnScreen(location) }
                painted()?.let { pet.params.y == 400 && abs(it.top - location[1] - pet.view.height * .3f) <= tolerance } == true
            }
            val pet = host.petWindows.getValue(0)
            val origin = IntArray(2)
            runOnMainSync { pet.view.getLocationOnScreen(origin) }
            val before = backgroundTaps()
            check(before == 0) { "A pet drag reached the background instead: $before" }
            // A hole inside the painted bounding rectangle must route to the actual different-UID Activity.
            tap(origin[0] + pet.view.width * .4f, origin[1] + pet.view.height * .575f)
            await("transparent hole reaches different UID") { backgroundTaps() == before + 1 }
            tap(origin[0] + pet.view.width * .05f, origin[1] + pet.view.height * .5f)
            await("transparent margin reaches different UID") { backgroundTaps() == before + 2 }
            results += "PASS: native hole and padding input reaches the app underneath"
            val count = backgroundTaps()
            val reaction = pet.reactKey.intValue
            val body = checkNotNull(painted())
            tap(body.exactCenterX(), body.top + body.height() * .8f)
            await("opaque body receives the tap") { pet.reactKey.intValue > reaction }
            check(backgroundTaps() == count) { "Opaque pet tap reached the background" }
            runOnMainSync { host.closePanel() }
            results += "PASS: opaque pixels receive pet actions without reaching the background"

            val bottomStart = checkNotNull(painted())
            drag(bottomStart.exactCenterX(), bottomStart.top + bottomStart.height() * .8f,
                bottomStart.exactCenterX(), targetContext.resources.displayMetrics.heightPixels - 1f)
            val metrics = targetContext.getSystemService(WindowManager::class.java).currentWindowMetrics
            val bottom = metrics.bounds.height() - metrics.windowInsets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()).bottom
            await("bottom remains reachable") { painted()?.bottom?.let { abs(it - bottom) <= tolerance } == true }
            results += "PASS: bottom movement remains reachable"
            val savedY = repo.data.value.floatingY
            repo.update { it.copy(miniFirstPetHidden = true) }
            await("native window removed") { host.petWindows.isEmpty() }
            repo.update { it.copy(miniFirstPetHidden = false) }
            await("saved window recreated") { host.petWindows[0]?.params?.y == savedY && painted() != null }
            results += "PASS: saved floating coordinates survive window recreation"

            repo.update { it.copy(floatingX = 400, floatingY = 400, petPositions = emptyMap(), miniExtraCharacters =
                List(2) { MiniCharacterSlot(manifest = "pet:${folder.name}", scale = 100, floatingX = 400, floatingY = 400) }) }
            await("three overlapping pets") { host.petWindows.size == 3 && host.petWindows.values.all { it.view.width > 0 && it.params.y == 400 } }
            val overlapCount = backgroundTaps()
            val overlapPet = host.petWindows.getValue(0)
            runOnMainSync { overlapPet.view.getLocationOnScreen(origin) }
            tap(origin[0] + overlapPet.view.width * .4f, origin[1] + overlapPet.view.height * .575f)
            await("transparent input through three overlapping pets") { backgroundTaps() == overlapCount + 1 }
            results += "PASS: transparent input survives the combined opacity of three overlapping pet windows"
            screenshot("floating-pet-bounds-passed.png")
        } catch (error: Throwable) {
            failure = error
            screenshot("floating-pet-bounds-failure.png")
        } finally {
            targetContext.startActivity(backgroundIntent().putExtra("finish", true))
            activity?.let { runOnMainSync { it.finish() } }
            repo.update { original }
            runOnMainSync { FloatingPet.appInForeground.value = foreground; FloatingPet.sync(targetContext) }
            folder.deleteRecursively()
            Characters.invalidate()
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", results.joinToString("\n", postfix = "\n") + (failure?.stackTraceToString() ?: ""))
        })
    }

    private fun service() = WindowInspector.getGlobalWindowViews().firstOrNull { it.context is PetOverlayService }?.context as? PetOverlayService

    private fun backgroundIntent() = Intent().setClassName(context.packageName, FloatingPetBoundsBackgroundActivity::class.java.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) { result += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::walk) }
        uiAutomation.windows.mapNotNull { it.root }.forEach(::walk)
        return result
    }

    private fun backgroundTaps() = nodes().firstOrNull { it.text?.toString()?.startsWith("BACKGROUND_TAPS:") == true }
        ?.text?.toString()?.substringAfter(':')?.trim()?.toIntOrNull() ?: -1

    private fun painted(): Rect? {
        val image = uiAutomation.takeScreenshot() ?: return null
        try {
            val result = Rect(image.width, image.height, 0, 0)
            val line = IntArray(image.width)
            for (y in 0 until image.height) {
                image.getPixels(line, 0, image.width, 0, y, image.width, 1)
                for (x in line.indices) if (Color.red(line[x]) > 100 && Color.blue(line[x]) > 100 && Color.green(line[x]) < 30) {
                    result.left = minOf(result.left, x); result.top = minOf(result.top, y)
                    result.right = maxOf(result.right, x + 1); result.bottom = maxOf(result.bottom, y + 1)
                }
            }
            return result.takeUnless { it.isEmpty }
        } finally { image.recycle() }
    }

    private fun screenshot(name: String) { uiAutomation.takeScreenshot()?.let { image ->
        File(targetContext.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    } }

    private fun tap(x: Float, y: Float) = drag(x, y, x, y, 0)
    private fun drag(x: Float, y: Float, endX: Float, endY: Float, steps: Int = 10) {
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, px: Float, py: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, py, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            check(uiAutomation.injectInputEvent(event, true)); event.recycle()
        }
        event(MotionEvent.ACTION_DOWN, x, y)
        for (step in 1..steps) { Thread.sleep(20); event(MotionEvent.ACTION_MOVE, x + (endX - x) * step / steps, y + (endY - y) * step / steps) }
        Thread.sleep(30); event(MotionEvent.ACTION_UP, endX, endY)
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; Thread.sleep(80) }
        error("Timed out waiting for $label")
    }
}
