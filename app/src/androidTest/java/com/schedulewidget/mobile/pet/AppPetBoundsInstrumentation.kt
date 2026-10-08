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
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.schedulewidget.mobile.MainActivity
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.ui.AppTheme
import com.schedulewidget.mobile.ui.Route
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/** Uses rendered pixels and real touch events, so canvas padding cannot masquerade as the pet's bounds. */
class AppPetBoundsInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val repo = Repository.get(targetContext)
        val original = repo.data.value
        val folder = File(PetImport.dir(targetContext), "bounds-test-${System.nanoTime()}")
        val results = mutableListOf<String>()
        val area = AtomicReference<Rect>()
        val taps = AtomicInteger()
        val longPresses = AtomicInteger()
        val backgroundTaps = AtomicInteger()
        var activity: MainActivity? = null
        var failure: Throwable? = null
        try {
            checkSpriteFrames(results)
            check(folder.mkdirs())
            val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            for (y in 30 until 90) for (x in 10 until 70) image.setPixel(x, y, Color.MAGENTA)
            for (y in 50 until 60) for (x in 35 until 45) image.setPixel(x, y, Color.TRANSPARENT)
            for (y in 30 until 45) for (x in 10 until 22) image.setPixel(x, y, Color.TRANSPARENT)
            File(folder, "image.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            image.recycle()
            File(folder, "pet.json").writeText("""{"displayName":"Bounds test","imagePath":"image.png"}""")
            Characters.invalidate()
            repo.update {
                it.copy(
                    characterManifest = "pet:${folder.name}", characterAnimation = "idle", miniCharacterScale = 100,
                    petX = .5f, petY = .5f, petPositions = emptyMap(), miniExtraCharacters = emptyList(),
                    miniFirstPetFlipped = false, floatingPet = false,
                )
            }
            val host = startActivitySync(
                Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_ROUTE, Route.Mini.name),
            ) as MainActivity
            activity = host
            runOnMainSync {
                host.setContent {
                    AppTheme {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(240.dp, 360.dp).background(ComposeColor.Black).onGloballyPositioned {
                                val r = it.boundsInWindow()
                                val origin = IntArray(2)
                                host.window.decorView.getLocationOnScreen(origin)
                                area.set(Rect(r.left.roundToInt() + origin[0], r.top.roundToInt() + origin[1],
                                    r.right.roundToInt() + origin[0], r.bottom.roundToInt() + origin[1]))
                            }) {
                                Box(Modifier.fillMaxSize().clickable { backgroundTaps.incrementAndGet() })
                                DraggablePet(heightDp = 100, onTap = { taps.incrementAndGet() },
                                    onDoubleTap = { taps.addAndGet(10) }, onTripleTap = { taps.addAndGet(100) },
                                    onLongPress = { longPresses.incrementAndGet() })
                            }
                        }
                    }
                }
            }
            val density = targetContext.resources.displayMetrics.density
            val tolerance = (3 * density).roundToInt()
            await("initial painted rectangle") { area.get() != null && paintedBounds(area.get()) != null }
            var painted = checkNotNull(paintedBounds(area.get()))
            check(abs(painted.width() - 60 * density) <= tolerance && abs(painted.height() - 60 * density) <= tolerance)

            touch(host, painted.right + 15 * density, painted.exactCenterY())
            await("transparent padding passes input to background") { backgroundTaps.get() == 1 }
            check(taps.get() == 0) { "Transparent sprite padding intercepted a tap" }
            touch(host, painted.exactCenterX(), painted.top - 15 * density)
            await("top padding passes input to background") { backgroundTaps.get() == 2 }
            touch(host, painted.left + 30 * density, painted.top + 25 * density)
            await("transparent hole passes input to background") { backgroundTaps.get() == 3 }
            touch(host, painted.left + 5 * density, painted.top + 5 * density)
            await("transparent silhouette corner passes input to background") { backgroundTaps.get() == 4 }
            touch(host, painted.left + 40 * density, painted.top + 40 * density)
            await("single pet tap") { taps.get() == 1 }
            results += "PASS: padding, silhouette corners and transparent holes pass through; painted body receives taps"

            for (flipped in listOf(false, true)) for (scale in listOf(50, 100, 150)) {
                repo.update { it.copy(miniFirstPetFlipped = flipped, miniCharacterScale = scale, petX = .5f, petY = .5f) }
                await("scale $scale / flip $flipped") {
                    paintedBounds(area.get())?.let { abs(it.width() - 60 * scale / 100f * density) <= tolerance } == true
                }
                val frameCenterX = area.get().exactCenterX()
                val expectedX = frameCenterX + (if (flipped) 10 else -10) * scale / 100f * density
                await("asymmetric flipped margins") {
                    paintedBounds(area.get())?.let { abs(it.exactCenterX() - expectedX) <= tolerance } == true
                }
                val edges = listOf("left", "right", "top", "bottom", "top-left", "bottom-right")
                for (edge in edges) {
                    painted = checkNotNull(paintedBounds(area.get()))
                    val a = area.get()
                    val endX = when { "left" in edge -> a.left + 1f; "right" in edge -> a.right - 1f; else -> painted.exactCenterX() }
                    val endY = when { "top" in edge -> a.top + 1f; "bottom" in edge -> a.bottom - 1f; else -> painted.exactCenterY() }
                    drag(host, painted.left + painted.width() * .8f, painted.top + painted.height() * .8f, endX, endY)
                    await("paint reaches $edge / scale $scale / flip $flipped") {
                        paintedBounds(a)?.let { b ->
                            ("left" !in edge || abs(b.left - a.left) <= tolerance) &&
                                ("right" !in edge || abs(b.right - a.right) <= tolerance) &&
                                ("top" !in edge || abs(b.top - a.top) <= tolerance) &&
                                ("bottom" !in edge || abs(b.bottom - a.bottom) <= tolerance)
                        } == true
                    }
                    check(repo.data.value.petX != null && repo.data.value.petY != null)
                }
                results += "PASS: all four edges, corners and repeated saved drags / scale=$scale / flipped=$flipped"
            }
            check(taps.get() == 1 && longPresses.get() == 0) { "Dragging also triggered a tap or long press" }
            painted = checkNotNull(paintedBounds(area.get()))
            val bodyX = painted.left + painted.width() * .8f
            val bodyY = painted.top + painted.height() * .8f
            repeat(2) { touch(host, bodyX, bodyY) }
            await("double tap") { taps.get() == 11 }
            repeat(3) { touch(host, bodyX, bodyY) }
            await("triple tap") { taps.get() == 111 }
            results += "PASS: repeated drags preserve single/double/triple tap detection"
            val screenshot = checkNotNull(uiAutomation.takeScreenshot())
            val file = File(targetContext.cacheDir, "app-pet-bounds.png")
            file.outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            screenshot.recycle()
            results += "SCREENSHOT: ${file.absolutePath}"
        } catch (error: Throwable) {
            failure = error
        } finally {
            activity?.let { runOnMainSync { it.finish() } }
            repo.update { original }
            folder.deleteRecursively()
            Characters.invalidate()
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", results.joinToString("\n", postfix = "\n") + (failure?.stackTraceToString() ?: ""))
        })
    }

    private fun paintedBounds(area: Rect): Rect? {
        val image = uiAutomation.takeScreenshot() ?: return null
        try {
            val pixels = IntArray(area.width() * area.height())
            image.getPixels(pixels, 0, area.width(), area.left, area.top, area.width(), area.height())
            var left = area.right
            var top = area.bottom
            var right = area.left
            var bottom = area.top
            for (y in 0 until area.height()) for (x in 0 until area.width()) {
                val color = pixels[y * area.width() + x]
                if (Color.red(color) > 240 && Color.green(color) < 20 && Color.blue(color) > 240) {
                    left = minOf(left, area.left + x); right = maxOf(right, area.left + x + 1)
                    top = minOf(top, area.top + y); bottom = maxOf(bottom, area.top + y + 1)
                }
            }
            return if (left < right && top < bottom) Rect(left, top, right, bottom) else null
        } finally { image.recycle() }
    }

    private fun checkSpriteFrames(results: MutableList<String>) {
        val sheet = Bitmap.createBitmap(320, 80, Bitmap.Config.ARGB_8888)
        try {
            for (y in 6 until 34) for (x in 3 until 22) sheet.setPixel(x, y, Color.MAGENTA)
            for (y in 12 until 16) for (x in 10 until 14) sheet.setPixel(x, y, Color.TRANSPARENT)
            for (y in 2 until 24) for (x in 23 until 38) sheet.setPixel(80 + x, 40 + y, Color.MAGENTA)
            val geometry = checkNotNull(spriteGeometry(sheet, 2))
            check(spriteGeometry(sheet, 2) === geometry) { "Repeated lookups recalculated sprite geometry" }
            check(geometry.bounds(0, false) == ComposeRect(3 / 40f, 6 / 40f, 22 / 40f, 34 / 40f))
            check(geometry.bounds(10, false) == ComposeRect(23 / 40f, 2 / 40f, 38 / 40f, 24 / 40f))
            val flipped = geometry.bounds(0, true)
            check(abs(flipped.left - (1 - 22 / 40f)) < .001 && abs(flipped.right - (1 - 3 / 40f)) < .001)
            runOnMainSync {
                for (flip in listOf(false, true)) {
                    val outline = geometry.silhouette(0, flip).createOutline(Size(80f, 80f), LayoutDirection.Ltr, Density(1f)) as Outline.Generic
                    val region = android.graphics.Region().apply { setPath(outline.path.asAndroidPath(), android.graphics.Region(0, 0, 80, 80)) }
                    fun mirror(x: Int) = if (flip) 79 - x else x
                    check(region.contains(mirror(8), 16)) { "Painted pixel missing from sprite outline" }
                    check(!region.contains(mirror(22), 26)) { "Transparent hole filled by sprite outline" }
                    check(!region.contains(mirror(3), 20)) { "Transparent padding filled by sprite outline" }
                }
            }
            results += "PASS: sheet row/column selection, mirrored silhouettes, holes and cached geometry"
        } finally { sheet.recycle() }
        val character = checkNotNull(Characters.find(targetContext, Characters.DEFAULT))
        val builtin = checkNotNull(Characters.sheet(targetContext, character, 1))
        val started = SystemClock.elapsedRealtime()
        val geometry = checkNotNull(spriteGeometry(builtin, character.rows))
        val scanMs = SystemClock.elapsedRealtime() - started
        val lookupStarted = SystemClock.elapsedRealtimeNanos()
        check(spriteGeometry(builtin, character.rows) === geometry)
        results += "PASS: ${builtin.width}x${builtin.height} builtin sheet computed once in ${scanMs}ms; cached lookup ${(SystemClock.elapsedRealtimeNanos() - lookupStarted) / 1000}us (emulator)"
    }

    private fun touch(activity: Activity, x: Float, y: Float) = gesture(activity, x, y, x, y, false)
    private fun drag(activity: Activity, x: Float, y: Float, endX: Float, endY: Float) = gesture(activity, x, y, endX, endY, true)

    private fun gesture(activity: Activity, x: Float, y: Float, endX: Float, endY: Float, drag: Boolean) {
        val origin = IntArray(2)
        runOnMainSync { activity.window.decorView.getLocationOnScreen(origin) }
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, time: Long, px: Float, py: Float) {
            runOnMainSync {
                val event = MotionEvent.obtain(down, time, action, px - origin[0], py - origin[1], 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                activity.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        event(MotionEvent.ACTION_DOWN, down, x, y)
        if (drag) for (step in 1..10) {
            Thread.sleep(20)
            event(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis(), x + (endX - x) * step / 10, y + (endY - y) * step / 10)
        }
        Thread.sleep(30)
        event(MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), endX, endY)
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
