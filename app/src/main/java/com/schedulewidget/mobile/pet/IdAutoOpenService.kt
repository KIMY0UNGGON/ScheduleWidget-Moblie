package com.schedulewidget.mobile.pet

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Presses 순천향톡's "모바일 신분증" button right after [IdShortcut.open] launched the app, so the pet's 신분증 button
 * and the home-screen shortcut land directly on the mobile ID. 순천향톡 offers no link to that screen itself.
 *
 * Limited on purpose: it only receives 순천향톡's windows (res/xml/id_accessibility.xml), and does nothing unless a
 * request is armed (a few seconds after our own button was pressed). It reads button labels only and keeps nothing.
 */
class IdAutoOpenService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val retry = Runnable { tryOpen() }

    override fun onServiceConnected() {
        IdShortcut.onArmed = { main.post(retry) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!IdShortcut.armed() || event?.packageName != IdShortcut.ID_APP) return
        // Coalesce the burst of content changes while the web page loads.
        main.removeCallbacks(retry)
        main.postDelayed(retry, 250)
    }

    private fun tryOpen() {
        if (!IdShortcut.armed()) return
        val root = rootInActiveWindow
        if (root != null && root.packageName == IdShortcut.ID_APP) {
            // Pressed even when the app was left on the ID card: opening it again is harmless.
            val target = IdShortcut.BUTTON_LABELS.firstNotNullOfOrNull { label -> findVisible(root, label)?.let(::clickableOf) }
            if (target != null && press(target)) {
                IdShortcut.done()
                return
            }
        }
        // The page may still be loading (splash / login check): look again shortly.
        main.removeCallbacks(retry)
        main.postDelayed(retry, 500)
    }

    /** A node on screen whose text or description is exactly [label] (the web page repeats labels in hidden parts). */
    private fun findVisible(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByText(label).firstOrNull { n ->
            val text = (n.contentDescription ?: n.text)?.toString()?.trim()
            val r = Rect().also(n::getBoundsInScreen)
            text != null && text.startsWith(label) && n.isVisibleToUser && r.width() > 8 && r.height() > 8
        }

    private fun clickableOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        repeat(6) {
            val cur = n ?: return null
            if (cur.isClickable) return cur
            n = cur.parent
        }
        return node
    }

    private fun press(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        // Some web views ignore the accessibility click: tap the middle of the button instead.
        val r = Rect().also(node::getBoundsInScreen)
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        main.removeCallbacks(retry)
        IdShortcut.onArmed = null
        super.onDestroy()
    }
}
