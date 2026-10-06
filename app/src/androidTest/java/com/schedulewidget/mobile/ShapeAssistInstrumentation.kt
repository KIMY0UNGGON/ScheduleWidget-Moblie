package com.schedulewidget.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.PointF
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import com.schedulewidget.mobile.notes.NoteStore
import com.schedulewidget.mobile.notes.editor.EditorState
import com.schedulewidget.mobile.notes.editor.EditorTool
import com.schedulewidget.mobile.notes.editor.InkInput
import com.schedulewidget.mobile.notes.ink.Stroke
import com.schedulewidget.mobile.notes.ink.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pen hold correction (line / circle / curve) through real stylus MotionEvents on a scratch notebook. Asserts only
 * visible geometry, undo and saved ink: ordinary strokes, cancel, highlighter and side-button erasing stay as before.
 */
class ShapeAssistInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val root = File(targetContext.cacheDir, "shape-assist-${System.nanoTime()}")
        val files = File(root, "files").apply { mkdirs() }
        val cache = File(root, "cache").apply { mkdirs() }
        val prefsPrefix = "shape_assist_test_${System.nanoTime()}_"
        val prefsUsed = HashSet<String>()
        val scratch = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = files
            override fun getCacheDir() = cache
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                prefsUsed += prefsPrefix + name
                return targetContext.getSharedPreferences(prefsPrefix + name, mode)
            }
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
                suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }
                val meta = NoteStore.createBlank(scratch, "Shape checks", "plain")
                val note = checkNotNull(NoteStore.load(scratch, meta.id))
                val uid = note.pages.single().uid
                val state = onMain { EditorState(scratch, note, emptyMap(), 1f, scope).apply { setViewport(600f, 900f) } }
                val input = onMain { InkInput(state, exponentialDecay<Offset>()) }
                val k = onMain { state.pxPerPt(0) }
                val pen = onMain { state.writerPen }
                var downAt = 0L
                // Real clock times, so both timer-based and event-time-based hold detection see the actual pauses.
                fun send(action: Int, p: PointF, buttons: Int = 0) {
                    val now = SystemClock.uptimeMillis()
                    if (action == MotionEvent.ACTION_DOWN) downAt = now
                    val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
                    val coords = arrayOf(MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = 1f; size = 1f })
                    val e = MotionEvent.obtain(downAt, now, action, 1, properties, coords, 0, buttons, 1f, 1f, 0, 0,
                        InputDevice.SOURCE_STYLUS, 0)
                    try { input.onTouch(e) } finally { e.recycle() }
                }
                suspend fun strokes() = onMain { state.inkOf(uid).strokes }
                suspend fun undoDepth() = onMain { state.undoStack.size }
                suspend fun page(path: List<PointF>) = onMain { path.map { PointF(state.toPageX(0, it.x), state.toPageY(0, it.y)) } }
                suspend fun press(path: List<PointF>, buttons: Int = 0) = onMain {
                    send(MotionEvent.ACTION_DOWN, path[0], buttons)
                    path.drop(1).forEach { send(MotionEvent.ACTION_MOVE, it, buttons) }
                }
                suspend fun lift(p: PointF) = onMain { send(MotionEvent.ACTION_UP, p) }
                // The pen rests past the hold time (off the main thread); a real pen keeps reporting the same point.
                suspend fun rest(p: PointF, buttons: Int = 0) {
                    delay(300)
                    onMain { send(MotionEvent.ACTION_MOVE, p, buttons) }
                    delay(HOLD_WAIT_MS - 300)
                }
                suspend fun draw(path: List<PointF>, hold: Boolean): Stroke {
                    val before = strokes().size
                    press(path)
                    if (hold) rest(path.last())
                    lift(path.last())
                    val after = strokes()
                    check(after.size == before + 1) { "Expected one new stroke, ${before} -> ${after.size}" }
                    return after.last()
                }

                // 1. Shaky diagonal line + hold -> straight, same pen, endpoints kept.
                val linePath = shakyLine(120f, 160f, 420f, 260f, 3f)
                val linePage = page(linePath)
                check(chordDeviation(linePage) > 2f / k) { "Line input is not shaky" }
                val beforeLine = strokes().size
                val line = draw(linePath, hold = true)
                val lp = line.points()
                check(line.tool == pen.tool && line.color == pen.color && line.width == pen.width) { "Line lost the pen preset" }
                check(chordDeviation(lp) < 0.5f) { "Held line not straightened: ${chordDeviation(lp)}pt" }
                check(dist(lp.first(), linePage.first()) < 6f / k && dist(lp.last(), linePage.last()) < 6f / k) { "Line endpoints moved" }
                results += "PASS: held shaky line becomes straight with the same pen and endpoints"

                // Undo removes it (one or two steps if the raw stroke is kept as its own step); redo brings it back.
                var undos = 0
                while (strokes().size > beforeLine && undos < 2) { onMain { state.undo() }; undos++ }
                check(strokes().size == beforeLine) { "Undo left the corrected line" }
                onMain { while (state.canRedo) state.redo() }
                check(strokes().last().let { it.id == line.id && it.pts.contentEquals(line.pts) }) { "Redo did not restore the line" }
                results += "PASS: undo/redo restores the corrected line ($undos undo step(s))"

                // 2. Ordinary pen-up (and a short pause) keeps raw ink; nothing changes afterwards.
                val wavy = shakyLine(120f, 300f, 420f, 300f, 3f)
                val depth = undoDepth()
                val plain = draw(wavy, hold = false)
                check(samePoints(plain, page(wavy))) { "Ordinary stroke was changed" }
                delay(HOLD_WAIT_MS)
                check(strokes().last().let { it.id == plain.id && it.pts.contentEquals(plain.pts) } && undoDepth() == depth + 1) {
                    "Ordinary stroke changed after pen-up"
                }
                val paused = shakyLine(120f, 340f, 420f, 340f, 3f)
                press(paused); delay(250); lift(paused.last())
                check(samePoints(strokes().last(), page(paused))) { "Short pause was corrected" }
                results += "PASS: quick and briefly paused strokes stay raw; no delayed snap after pen-up"

                // 3. Rough closed loop + hold -> circle.
                val ring = shakyArc(300f, 470f, 90f, 0.0, 360.0, 7f, 48)
                check(roundness(page(ring)).second > 0.06f) { "Circle input is not rough" }
                val circle = draw(ring, hold = true).points()
                val (radius, error) = roundness(circle)
                check(error < 0.03f) { "Held loop not a circle: radial error ${error}" }
                check(abs(radius * k - 90f) < 0.15f * 90f) { "Circle radius ${radius * k}px, drawn 90px" }
                check(dist(circle.first(), circle.last()) < 0.2f * radius) { "Circle is not closed" }
                results += "PASS: held rough loop becomes a closed circle of the drawn size"

                // 4. Shaky open arc + hold -> smooth curve, still open and bent, endpoints kept.
                val bowPath = shakyArc(300f, 720f, 110f, 200.0, 340.0, 4f, 30)
                val bowPage = page(bowPath)
                turning(bowPage).let { (total, net) -> check(total > 3f * abs(net)) { "Curve input is not shaky" } }
                val bow = draw(bowPath, hold = true).points()
                val (total, net) = turning(bow)
                check(abs(net) in 90f..200f && total <= 1.25f * abs(net) + 20f) { "Held arc not smooth: total=$total net=$net" }
                check(dist(bow.first(), bowPage.first()) < 8f / k && dist(bow.last(), bowPage.last()) < 8f / k) { "Curve endpoints moved" }
                check(chordDeviation(bow) > 0.5f * 110f * (1f - cos(Math.toRadians(70.0)).toFloat()) / k) { "Curve was flattened" }
                results += "PASS: held shaky arc becomes a smooth open curve"

                // 5. Cancel: after a hold preview nothing is committed; a cancelled stroke's timer can't snap the next one.
                val count = strokes().size
                val undoBefore = undoDepth()
                val dropped = shakyLine(120f, 720f, 420f, 700f, 3f)
                press(dropped); rest(dropped.last()); onMain { send(MotionEvent.ACTION_CANCEL, dropped.last()) }
                check(strokes().size == count && undoDepth() == undoBefore && onMain { state.liveN } == 0) { "Cancelled stroke left ink" }
                delay(HOLD_WAIT_MS)
                check(strokes().size == count && undoDepth() == undoBefore) { "Cancelled stroke committed later" }
                press(dropped.take(4)); onMain { send(MotionEvent.ACTION_CANCEL, dropped[3]) }
                val moving = shakyLine(120f, 730f, 420f, 730f, 3f, n = 20)
                onMain { send(MotionEvent.ACTION_DOWN, moving[0]) }
                for (p in moving.drop(1)) { delay(50); onMain { send(MotionEvent.ACTION_MOVE, p) } }
                lift(moving.last())
                check(strokes().size == count + 1 && samePoints(strokes().last(), page(moving))) { "Stale hold changed a moving stroke" }
                results += "PASS: cancel commits nothing; a stroke that keeps moving is not snapped"

                // 6. Highlighter keeps its own behaviour: quick stays raw, hold is straight.
                onMain { state.selectTool(EditorTool.HIGHLIGHTER) }
                val hlQuick = shakyLine(120f, 80f, 400f, 90f, 3f)
                val quick = draw(hlQuick, hold = false)
                check(quick.tool == Tool.HIGHLIGHTER && samePoints(quick, page(hlQuick))) { "Quick highlighter changed" }
                val held = draw(shakyLine(120f, 115f, 400f, 125f, 3f), hold = true)
                check(held.tool == Tool.HIGHLIGHTER && chordDeviation(held.points()) < 0.5f) { "Held highlighter not straight" }
                onMain { state.selectTool(EditorTool.PEN) }
                results += "PASS: highlighter quick/hold behaviour preserved"

                // 7. Side button held: erasing (even while resting) removes the corrected line and draws nothing.
                val keep = strokes().filter { it.id != line.id }.map { it.id }
                val eraseDepth = undoDepth()
                val sweep = (0..4).map { PointF(270f, 170f + it * 20f) }
                press(sweep, MotionEvent.BUTTON_STYLUS_PRIMARY)
                rest(sweep.last(), MotionEvent.BUTTON_STYLUS_PRIMARY)
                lift(sweep.last())
                check(strokes().map { it.id } == keep && undoDepth() == eraseDepth + 1) { "Side-button erase: ${strokes().map { it.id }}" }
                check(onMain { state.displayTool } == EditorTool.PEN) { "Pen not restored after side button" }
                val after = shakyLine(120f, 770f, 420f, 770f, 3f)
                val afterStroke = draw(after, hold = false)
                check(afterStroke.tool == pen.tool && samePoints(afterStroke, page(after))) { "Pen after erase changed" }
                results += "PASS: side-button erase removes corrected ink, never shapes; pen resumes raw"

                // 8. Saved ink equals the editor's ink.
                val live = strokes()
                onMain { state.saveNow(final = true) }?.await()
                val saved = NoteStore.loadInk(scratch, meta.id, uid).strokes
                check(saved.map { it.id } == live.map { it.id } &&
                    saved.zip(live).all { (a, b) -> a.tool == b.tool && a.width == b.width && a.pts.contentEquals(b.pts) }) {
                    "Saved ink differs from editor ink"
                }
                results += "PASS: corrected and raw strokes round-trip through the normal save"
            }
        } catch (e: Throwable) {
            failures++
            results += "FAIL: ${e.stackTraceToString()}"
        } finally {
            scope.cancel()
            runBlocking { NoteStore.writeMutex.lock(); try { root.deleteRecursively() } finally { NoteStore.writeMutex.unlock() } }
            prefsUsed.forEach { targetContext.deleteSharedPreferences(it) }
        }
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\n${results.joinToString("\n")}\nFailures: $failures\n")
        })
    }

    private fun Stroke.points() = (0 until count).map { PointF(x(it), y(it)) }

    private fun dist(a: PointF, b: PointF) = hypot(a.x - b.x, a.y - b.y)

    /** The stroke is the input, point for point (raw ink, page coordinates rounded to 0.01). */
    private fun samePoints(s: Stroke, expected: List<PointF>) = s.count == expected.size &&
        expected.indices.all { abs(s.x(it) - expected[it].x) < 0.05f && abs(s.y(it) - expected[it].y) < 0.05f }

    /** Largest distance of any point from the chord through the first and last points. */
    private fun chordDeviation(p: List<PointF>): Float {
        val a = p.first(); val b = p.last(); val len = dist(a, b)
        return p.maxOf { abs((b.x - a.x) * (a.y - it.y) - (a.x - it.x) * (b.y - a.y)) / len }
    }

    /** Mean radius and worst relative radial error about the bounding-box centre. */
    private fun roundness(p: List<PointF>): Pair<Float, Float> {
        val cx = (p.minOf { it.x } + p.maxOf { it.x }) / 2f; val cy = (p.minOf { it.y } + p.maxOf { it.y }) / 2f
        val r = p.map { hypot(it.x - cx, it.y - cy) }
        val mean = r.average().toFloat()
        return mean to r.maxOf { abs(it - mean) } / mean
    }

    /** Sum of absolute turning and net turning along the polyline, in degrees. */
    private fun turning(p: List<PointF>): Pair<Float, Float> {
        val dirs = p.zipWithNext().filter { (a, b) -> dist(a, b) > 1e-3f }.map { (a, b) -> atan2(b.y - a.y, b.x - a.x).toDouble() }
        var total = 0.0; var net = 0.0
        dirs.zipWithNext().forEach { (a, b) ->
            var d = b - a
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            total += abs(d); net += d
        }
        return Math.toDegrees(total).toFloat() to Math.toDegrees(net).toFloat()
    }

    /** A shaky hand: every odd point pushed ±[jitter] px, even points (and both ends) exact. */
    private fun wobble(i: Int, jitter: Float) = when (i % 4) { 1 -> jitter; 3 -> -jitter; else -> 0f }

    private fun shakyLine(ax: Float, ay: Float, bx: Float, by: Float, jitter: Float, n: Int = 30): List<PointF> {
        val len = hypot(bx - ax, by - ay); val nx = -(by - ay) / len; val ny = (bx - ax) / len
        return (0..n).map { i ->
            val t = i.toFloat() / n; val j = wobble(i, jitter)
            PointF(ax + (bx - ax) * t + nx * j, ay + (by - ay) * t + ny * j)
        }
    }

    /** Arc around ([cx], [cy]) from [from] to [to] degrees (screen y down); odd points pushed ±[jitter] px radially. */
    private fun shakyArc(cx: Float, cy: Float, r: Float, from: Double, to: Double, jitter: Float, n: Int): List<PointF> =
        (0..n).map { i ->
            val a = Math.toRadians(from + (to - from) * i / n); val rr = r + wobble(i, jitter)
            PointF(cx + rr * cos(a).toFloat(), cy + rr * sin(a).toFloat())
        }

    private companion object {
        /** The editor's 550 ms hold plus slack. */
        const val HOLD_WAIT_MS = 800L
    }
}
