package com.schedulewidget.mobile

import android.app.Activity
import android.app.Instrumentation
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.widget.SevenDayCalendarWidget
import com.schedulewidget.mobile.widget.SevenDayCalendarWidgetReceiver
import com.schedulewidget.mobile.widget.ThirtyDayCalendarWidget
import com.schedulewidget.mobile.widget.ThirtyDayCalendarWidgetReceiver
import com.schedulewidget.mobile.widget.weekdayKo
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate

/** Renders registered providers in a real AppWidgetHost; runner requires the emulator's appwidget grantbind. */
class WidgetCalendarInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val repo = Repository.get(targetContext)
        val original = repo.data.value
        val host = AppWidgetHost(targetContext, 20261006)
        val manager = AppWidgetManager.getInstance(targetContext)
        val ids = mutableListOf<Int>()
        val results = mutableListOf<String>()
        var activity: Activity? = null
        var failure: Throwable? = null
        try {
            repo.update { it.copy(miniDayCount = 1) }
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ROUTE, "Mini").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val current = checkNotNull(activity)
            val container = FrameLayout(current)
            runOnMainSync {
                container.setPadding(0, (32 * current.resources.displayMetrics.density).toInt(), 0, 0)
                current.setContentView(container); host.startListening()
            }
            for ((days, provider, widget) in listOf(
                Triple(7, SevenDayCalendarWidgetReceiver::class.java, SevenDayCalendarWidget() as GlanceAppWidget),
                Triple(30, ThirtyDayCalendarWidgetReceiver::class.java, ThirtyDayCalendarWidget() as GlanceAppWidget),
            )) {
                val id = host.allocateAppWidgetId().also { ids += it }
                check(manager.bindAppWidgetIdIfAllowed(id, ComponentName(targetContext, provider))) { "Grant widget binding on the emulator first" }
                lateinit var view: AppWidgetHostView
                runOnMainSync { view = host.createView(current, id, manager.getAppWidgetInfo(id)) }
                val glanceId = GlanceAppWidgetManager(targetContext).getGlanceIdBy(id)
                for ((width, height) in if (days == 30) listOf(250 to 250, 320 to 320) else listOf(250 to 200, 320 to 280)) {
                    val density = current.resources.displayMetrics.density
                    runOnMainSync {
                        container.removeAllViews()
                        container.addView(view, FrameLayout.LayoutParams((width * density).toInt(), (height * density).toInt()))
                        view.updateAppWidgetSize(Bundle(), width, height, width, height)
                    }
                    manager.updateAppWidgetOptions(id, Bundle().apply {
                        putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(width.toFloat(), height.toFloat())))
                    })
                    runBlocking { widget.update(targetContext, glanceId) }
                    awaitDates(view, days, LocalDate.now())
                    Thread.sleep(500)
                    awaitDates(view, days, LocalDate.now())
                    val image = checkNotNull(uiAutomation.takeScreenshot())
                    File(targetContext.getExternalFilesDir(null), "widget-$days-$width.png").outputStream().use {
                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    image.recycle()
                    results += "PASS: $days-day provider shows all dates at ${width}x${height}dp despite miniDayCount=1"
                }
                runOnMainSync { click(textViews(view).first { it.text.toString() == "›" }) }
                awaitDates(view, days, LocalDate.now().plusDays(days.toLong()))
                runOnMainSync { click(textViews(view).first { it.text.toString() == "오늘" }) }
                awaitDates(view, days, LocalDate.now())
                results += "PASS: $days-day next period advances $days days and today resets it"
            }
        } catch (e: Throwable) { failure = e }
        finally {
            ids.forEach { host.deleteAppWidgetId(it) }
            host.stopListening()
            activity?.let { runOnMainSync { it.finish() } }
            repo.update { original }
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", (results + listOfNotNull(failure?.stackTraceToString())).joinToString("\n") + "\n")
        })
    }

    private fun awaitDates(view: View, days: Int, start: LocalDate) {
        val expected = (0 until days).map { start.plusDays(it.toLong()).let { date -> "${date.dayOfMonth} ${date.weekdayKo()}" } }
        val deadline = SystemClock.uptimeMillis() + 20000
        var actual = emptyList<String>()
        while (SystemClock.uptimeMillis() < deadline) {
            runOnMainSync { actual = textViews(view).filter { it.isShown }.map { it.text.toString() }.filter { it.matches(Regex("\\d{1,2} [월화수목금토일]")) } }
            if (actual == expected) return
            Thread.sleep(100)
        }
        error("Expected $expected, rendered $actual")
    }

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun click(view: View) {
        var current: View? = view
        while (current != null) {
            if (current.isClickable) { check(current.performClick()); return }
            current = current.parent as? View
        }
        error("Widget control is not clickable")
    }
}
