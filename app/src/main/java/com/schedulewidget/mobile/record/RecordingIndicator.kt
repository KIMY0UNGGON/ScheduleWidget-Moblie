package com.schedulewidget.mobile.record

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Recording red (iOS system red). */
val RecordRed = Color(0xFFFF3B30)

/** The bubble's border while recording: a clearly visible gray. */
val RecordingBorderGray = Color(0xFF8E8E93)

/** Milliseconds recorded so far, ticking once a second on the second boundary. 0 when not recording. */
@Composable
fun rememberRecordingElapsed(state: RecordingState): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.isRecording, state.startedElapsed) {
        while (state.isRecording) {
            now = SystemClock.elapsedRealtime()
            delay(1000 - (now - state.startedElapsed).mod(1000L) + 5)
        }
    }
    return state.elapsedMs(now)
}

/** A red dot with a white rim, gently pulsing; [pulse] off for static uses. */
@Composable
fun RecordingDot(modifier: Modifier = Modifier, size: Dp = 10.dp, rim: Boolean = false, pulse: Boolean = true) {
    val alpha = if (pulse) {
        val t = rememberInfiniteTransition(label = "rec")
        t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "rec-alpha").value
    } else 1f
    // White ring outside, red inside (a ring keeps the dot readable over any character or wallpaper).
    Box(
        modifier.size(size)
            .semantics { contentDescription = "녹음 중" }
            .clip(CircleShape).background(if (rim) Color.White else Color.Transparent)
            .padding(if (rim) 2.dp else 0.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape).background(RecordRed),
    )
}

/** The small badge drawn near a pet while recording. */
@Composable
fun PetRecordingBadge(modifier: Modifier = Modifier) = RecordingDot(modifier, size = 14.dp, rim = true)
