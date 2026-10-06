package com.schedulewidget.mobile.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** Owner: widget agent. */
class CalendarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = CalendarWidget()
}

class SevenDayCalendarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = SevenDayCalendarWidget()
}

class ThirtyDayCalendarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = ThirtyDayCalendarWidget()
}

class MusicWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = MusicWidget()
}
