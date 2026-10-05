package com.schedulewidget.mobile.pet

import android.os.Handler
import android.os.Looper

/**
 * Tells 1, 2 and 3 quick taps on the pet apart (tap = reaction, double = calendar, triple = MP3 player).
 * Waits [window] ms after the last tap so a triple tap never also fires the single or double action.
 */
class TapCounter(
    // The system double-tap timeout (300 ms): a shorter window split ordinary double taps into two single taps.
    private val window: Long = android.view.ViewConfiguration.getDoubleTapTimeout().toLong(),
    private val onTaps: (Int) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var count = 0
    private val fire = Runnable {
        val n = count
        count = 0
        if (n > 0) onTaps(n.coerceAtMost(3))
    }

    fun tap() {
        count++
        handler.removeCallbacks(fire)
        if (count >= 3) fire.run() else handler.postDelayed(fire, window)
    }

    /** Drops pending taps without firing them (a drag/long-press started, or the owner is going away). */
    fun cancel() {
        handler.removeCallbacks(fire)
        count = 0
    }
}
