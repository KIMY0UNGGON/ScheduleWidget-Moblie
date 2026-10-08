package com.schedulewidget.mobile.pet

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/** Owns the floating pet's long-press drop target and its hover feedback. */
internal class PetOverlayCloseTarget(
    private val context: Context,
    private val windows: WindowManager,
    private val onAddFailure: () -> Unit,
) {
    private var view: TextView? = null
    private var hovering = false

    fun show() {
        if (view != null) return
        hovering = false
        val density = context.resources.displayMetrics.density
        val size = (72 * density).roundToInt()
        val target = TextView(context).apply {
            text = "✕"
            textSize = 26f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC333333.toInt())
                setStroke((2 * density).roundToInt(), 0xFFFFFFFF.toInt())
            }
            contentDescription = "펫 닫기"
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (56 * density).roundToInt()
        }
        runCatching { windows.addView(target, params) }
            .onSuccess { view = target }
            .onFailure { onAddFailure() }
    }

    fun highlight(over: Boolean) {
        val target = view ?: return
        if (over == hovering) return
        hovering = over
        val scale = if (over) 1.3f else 1f
        target.animate().cancel()
        target.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        (target.background as? GradientDrawable)?.setColor(if (over) 0xEEE53935.toInt() else 0xCC333333.toInt())
    }

    fun hide() {
        view?.let {
            it.animate().cancel()
            runCatching { windows.removeView(it) }
        }
        view = null
        hovering = false
    }

    fun containsCenter(pet: View, body: androidx.compose.ui.geometry.Rect): Boolean {
        val target = view ?: return false
        if (!target.isAttachedToWindow || target.width == 0 || !pet.isAttachedToWindow) return false
        val targetPosition = IntArray(2).also { target.getLocationOnScreen(it) }
        val petPosition = IntArray(2).also { pet.getLocationOnScreen(it) }
        val targetX = targetPosition[0] + target.width / 2f
        val targetY = targetPosition[1] + target.height / 2f
        val petX = petPosition[0] + body.center.x * pet.width
        val petY = petPosition[1] + body.center.y * pet.height
        val reach = target.width * 1.1f
        return (petX - targetX) * (petX - targetX) + (petY - targetY) * (petY - targetY) < reach * reach
    }
}
