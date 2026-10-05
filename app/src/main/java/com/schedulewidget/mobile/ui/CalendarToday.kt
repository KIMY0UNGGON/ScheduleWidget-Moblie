package com.schedulewidget.mobile.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

internal val LocalCalendarToday = staticCompositionLocalOf { LocalDate.now() }

/** A date that follows midnight and system date/time-zone changes while this calendar is open. */
@Composable
internal fun rememberCalendarToday(): LocalDate {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var today by remember { mutableStateOf(LocalDate.now()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { refresh++ }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(refresh) {
        val now = ZonedDateTime.now()
        today = now.toLocalDate()
        val nextMidnight = today.plusDays(1).atStartOfDay(now.zone)
        delay((Duration.between(now, nextMidnight).toMillis() + 250).coerceAtLeast(1_000))
        refresh++
    }
    return today
}
