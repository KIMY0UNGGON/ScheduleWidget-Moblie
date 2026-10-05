package com.schedulewidget.mobile.pet

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ComposeView
import com.schedulewidget.mobile.data.Repository
import kotlin.math.abs
import kotlin.math.roundToInt

/** A single floating pet window and its touch state. */
internal class PetOverlayWindow(
    val index: Int,
    val manifest: String,
    val params: WindowManager.LayoutParams,
) {
    lateinit var view: ComposeView
    lateinit var touch: PetOverlayTouch
    val reactKey = mutableIntStateOf(0)
}

/** Raw-screen drag, long-press close target, and tap-count recognition for one overlay window. */
internal class PetOverlayTouch(
    private val service: PetOverlayService,
    private val pet: PetOverlayWindow,
) : View.OnTouchListener {
    private val slop = ViewConfiguration.get(service).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var held = false
    private var view: View? = null
    private val hold = Runnable {
        if (service.destroyed) return@Runnable
        held = true
        dragging = true
        taps.cancel()
        service.closePanel()
        service.showCloseTarget()
        view?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }
    private val taps = TapCounter { count -> service.onPetTap(pet.index, count) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, e: MotionEvent): Boolean {
        view = v
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                service.activePetIndex = pet.index
                service.main.removeCallbacks(hold)
                downX = e.rawX
                downY = e.rawY
                startX = pet.params.x
                startY = pet.params.y
                dragging = false
                held = false
                service.main.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    service.main.removeCallbacks(hold)
                    dragging = true
                    taps.cancel()
                    service.closePanel()
                }
                if (dragging) {
                    service.moveTo(pet, startX + dx.roundToInt(), startY + dy.roundToInt())
                    if (held) service.highlightCloseTarget(service.overCloseTarget(v))
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                service.main.removeCallbacks(hold)
                val dropOnClose = held && service.overCloseTarget(v)
                service.hideCloseTarget()
                when {
                    dropOnClose -> {
                        FloatingPet.disable(service)
                        service.stopSelf()
                    }
                    dragging -> savePosition()
                    else -> taps.tap()
                }
                dragging = false
                held = false
            }
            MotionEvent.ACTION_CANCEL -> {
                service.main.removeCallbacks(hold)
                service.hideCloseTarget()
                if (dragging) savePosition()
                dragging = false
                held = false
            }
        }
        return true
    }

    private fun savePosition() {
        val (x, y) = pet.params.x to pet.params.y
        Repository.get(service).update { data ->
            val slot = data.petSlots().firstOrNull { it.index == pet.index }
            if (slot == null) data else data.withPet(slot.copy(floatingX = x, floatingY = y))
        }
    }

    fun cancel() {
        service.main.removeCallbacks(hold)
        taps.cancel()
        dragging = false
        held = false
    }
}
