package com.schedulewidget.mobile.pet

import android.graphics.Rect
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.record.Recorder
import com.schedulewidget.mobile.ui.QuickAddActivity
import com.schedulewidget.mobile.ui.Route
import kotlin.math.roundToInt

internal enum class PetPanel { Menu, Calendar, Music }

/** Owns floating-window panel placement, calendar resizing, and panel content. */
internal class PetOverlayPanels(private val service: PetOverlayService) {
    private var view: ComposeView? = null
    private var kind: PetPanel? = null
    private var petIndex: Int? = null
    private val menuTail = mutableStateOf<BubbleTail?>(null)
    private val menuRoom = mutableStateOf<Int?>(null)
    private val calendarHeight = mutableIntStateOf(260)
    private var params: WindowManager.LayoutParams? = null
    private var heightCarry = 0f

    private fun minWidth() = (200 * service.resources.displayMetrics.density).roundToInt()

    private fun maxCalendarWidth() = service.resources.displayMetrics.widthPixels

    private fun moveCalendar(dx: Float, dy: Float) {
        val panelView = view ?: return
        val panelParams = params ?: return
        if (kind != PetPanel.Calendar || !panelView.isAttachedToWindow) return
        val screen = service.resources.displayMetrics
        panelParams.x = (panelParams.x + dx.roundToInt()).coerceIn(0, (screen.widthPixels - panelView.width).coerceAtLeast(0))
        panelParams.y = (panelParams.y + dy.roundToInt()).coerceIn(0, (screen.heightPixels - panelView.height).coerceAtLeast(0))
        runCatching { service.windows.updateViewLayout(panelView, panelParams) }
    }

    private fun maxCalendarHeight() = service.resources.displayMetrics.let {
        (it.heightPixels * 0.75f / it.density).roundToInt().coerceAtLeast(MIN_CAL_HEIGHT)
    }

    fun resizeCalendar(edges: ResizeEdges, dx: Float, dy: Float) {
        val panelView = view ?: return
        val panelParams = params ?: return
        if (kind != PetPanel.Calendar || !panelView.isAttachedToWindow) return
        val screen = service.resources.displayMetrics
        val sw = screen.widthPixels
        val sh = screen.heightPixels
        val density = screen.density
        val minW = minWidth()
        panelParams.width = panelParams.width.coerceIn(1, sw)
        panelParams.x = panelParams.x.coerceIn(0, sw - panelParams.width)
        if (edges.right) {
            val maxW = minOf(sw - panelParams.x, maxCalendarWidth())
            panelParams.width = (panelParams.width + dx.roundToInt()).coerceIn(minOf(minW, maxW), maxW)
        }
        if (edges.left) {
            val right = panelParams.x + panelParams.width
            val maxW = minOf(right, maxCalendarWidth())
            val newWidth = (panelParams.width - dx.roundToInt()).coerceIn(minOf(minW, maxW), maxW)
            panelParams.x = right - newWidth
            panelParams.width = newWidth
        }
        if (edges.bottom || edges.top) {
            heightCarry += (if (edges.bottom) dy else -dy) / density
            val step = heightCarry.toInt()
            if (step != 0) {
                heightCarry -= step
                val old = calendarHeight.intValue
                val oldPx = (old * density).roundToInt()
                val chrome = (panelView.height - oldPx).coerceAtLeast(0)
                val roomPx = if (edges.top) panelParams.y + oldPx else sh - panelParams.y - chrome
                val maxH = minOf(maxCalendarHeight(), (roomPx / density).toInt()).coerceAtLeast(MIN_CAL_HEIGHT)
                val next = (old + step).coerceIn(MIN_CAL_HEIGHT, maxH)
                if (next != old + step) heightCarry = 0f
                if (edges.top) panelParams.y = (panelParams.y + oldPx - (next * density).roundToInt()).coerceAtLeast(0)
                calendarHeight.intValue = next
            }
        }
        runCatching { service.windows.updateViewLayout(panelView, panelParams) }
    }

    fun clamp() {
        val panelView = view ?: return
        val panelParams = params ?: return
        val screen = service.resources.displayMetrics
        if (panelParams.width > 0) {
            panelParams.width = panelParams.width.coerceAtMost(screen.widthPixels)
            panelParams.x = panelParams.x.coerceIn(0, (screen.widthPixels - panelParams.width).coerceAtLeast(0))
        }
        panelParams.y = panelParams.y.coerceIn(0, (screen.heightPixels - panelView.height).coerceAtLeast(0))
        if (kind == PetPanel.Calendar) calendarHeight.intValue = calendarHeight.intValue.coerceAtMost(maxCalendarHeight())
        if (panelView.isAttachedToWindow) runCatching { service.windows.updateViewLayout(panelView, panelParams) }
    }

    private fun saveCalendarSize() {
        if (kind != PetPanel.Calendar) return
        val panelParams = params ?: return
        val density = service.resources.displayMetrics.density
        val width = (panelParams.width / density).roundToInt()
        val height = calendarHeight.intValue
        Repository.get(service).update { it.copy(petCalendarWidth = width, petCalendarHeight = height) }
    }

    fun toggle(panel: PetPanel) {
        val same = kind == panel && petIndex == service.activePetIndex
        close()
        if (!same) open(panel)
    }

    private fun open(panel: PetPanel) {
        if (service.destroyed || FloatingPet.appInForeground.value) return
        val anchor = service.petWindows[service.activePetIndex] ?: service.petWindows.values.firstOrNull() ?: return
        val pet = anchor.view
        val petParams = anchor.params
        val screen = service.resources.displayMetrics
        val saved = Repository.get(service).data.value
        val maxWidth = (screen.widthPixels - (16 * screen.density).roundToInt())
            .let { if (panel == PetPanel.Calendar) minOf(it, maxCalendarWidth()) else it }
        val menuIcons = 3 + PetApps.installed(service, saved.petApps).size + if (saved.notes.enabled) 1 else 0
        val width = if (panel == PetPanel.Menu) (petMenuWidthDp(menuIcons) * screen.density).roundToInt().coerceAtMost(screen.widthPixels)
        else if (panel == PetPanel.Music) (340 * screen.density).roundToInt().coerceAtMost(screen.widthPixels - 16)
        else if (saved.petCalendarWidth > 0) (saved.petCalendarWidth * screen.density).roundToInt().coerceIn(minOf(minWidth(), maxWidth), maxWidth)
        else maxWidth
        val panelParams = service.overlayParams(width).apply {
            x = (petParams.x + pet.width / 2 - width / 2).coerceIn(8, (screen.widthPixels - width - 24).coerceAtLeast(8))
            y = 0
            alpha = 0f
        }
        val panelView = service.composeView {
            when (panel) {
                PetPanel.Menu -> CompositionLocalProvider(
                    LocalBubbleTail provides menuTail.value,
                    LocalBubbleRoom provides menuRoom.value?.dp,
                ) {
                    PetMenu(
                        onApp = { pkg -> close(); PetApps.launch(service, pkg) },
                        onNotes = if (saved.notes.enabled) ({ service.openApp(Route.Notes) }) else null,
                        onMusic = {
                            service.main.post {
                                val anchorIndex = petIndex
                                close()
                                if (anchorIndex != null) service.activePetIndex = anchorIndex
                                open(PetPanel.Music)
                            }
                        },
                        onCalendar = {
                            service.main.post {
                                val anchorIndex = petIndex
                                close()
                                if (anchorIndex != null) service.activePetIndex = anchorIndex
                                open(PetPanel.Calendar)
                            }
                        },
                        onSettings = { service.openApp(Route.Pets) },
                        onOpenRecordings = { service.openApp(Route.Recordings) },
                        onOpenTranscript = { id -> Recorder.openTranscript.value = id; service.openApp(Route.Recordings) },
                    )
                }
                PetPanel.Calendar -> OverlayCalendar(
                    onClose = ::close,
                    onOpenApp = service::openApp,
                    onAdd = { date -> close(); service.startActivity(QuickAddActivity.intent(service, date)) },
                    dayHeight = calendarHeight.intValue,
                    onResize = { edges, dx, dy -> resizeCalendar(edges, dx, dy) },
                    onResizeEnd = ::saveCalendarSize,
                    onMove = ::moveCalendar,
                )
                PetPanel.Music -> PetMusicPanel(
                    onClose = ::close,
                    onOpenPlaylists = { service.openApp(Route.Playlists) },
                    onOpenMusicApps = { service.openApp(Route.MusicApps) },
                )
            }
        }
        menuRoom.value = null
        heightCarry = 0f
        var placed = false
        var menuAbove = false
        panelView.addOnLayoutChangeListener { changedView, _, top, _, bottom, _, _, _, _ ->
            if (!changedView.isAttachedToWindow || bottom - top <= 0) return@addOnLayoutChangeListener
            val screenHeight = service.resources.displayMetrics.heightPixels
            val height = bottom - top
            if (panel == PetPanel.Calendar && Build.VERSION.SDK_INT >= 29) {
                // Keep Android's edge-back gesture from cancelling the resize grips; at most 200dp per side.
                val density = service.resources.displayMetrics.density
                val corner = (34 * density).roundToInt()
                val side = (16 * density).roundToInt()
                val halfMiddle = minOf(height * 0.3f, 66 * density).roundToInt()
                val width = changedView.width
                changedView.systemGestureExclusionRects = listOf(
                    Rect(0, 0, corner, corner), Rect(width - corner, 0, width, corner),
                    Rect(0, height - corner, corner, height), Rect(width - corner, height - corner, width, height),
                    Rect(0, height / 2 - halfMiddle, side, height / 2 + halfMiddle),
                    Rect(width - side, height / 2 - halfMiddle, width, height / 2 + halfMiddle),
                )
            }
            val y = if (!placed) {
                val below = petParams.y + pet.height + 8
                val above = petParams.y - height - 8
                when {
                    panel == PetPanel.Menu && above >= 0 -> above
                    below + height <= screenHeight -> below
                    above >= 0 -> above
                    else -> (screenHeight - height).coerceAtLeast(0)
                }
            } else if (panel == PetPanel.Menu && menuAbove) {
                (petParams.y - height - 8).coerceAtLeast(0)
            } else panelParams.y.coerceAtMost((screenHeight - height).coerceAtLeast(0))
            if (!placed) menuAbove = y < petParams.y
            if (panel == PetPanel.Menu) {
                val roomPx = if (menuAbove) petParams.y - 8 else screenHeight - (petParams.y + pet.height + 8)
                val room = (roomPx / service.resources.displayMetrics.density).roundToInt()
                if (menuRoom.value != room) menuRoom.value = room
                val tail = BubbleTail(up = y >= petParams.y + pet.height, x = petParams.x + pet.width / 2 - panelParams.x)
                if (menuTail.value != tail) menuTail.value = tail
            }
            if (!placed || panelParams.y != y) {
                placed = true
                panelParams.y = y
                panelParams.alpha = 1f
                runCatching { service.windows.updateViewLayout(changedView, panelParams) }
            }
        }
        if (panel == PetPanel.Calendar) calendarHeight.intValue = saved.petCalendarHeight.coerceIn(MIN_CAL_HEIGHT, maxCalendarHeight())
        runCatching { service.windows.addView(panelView, panelParams) }
            .onSuccess { view = panelView; kind = panel; petIndex = anchor.index; params = panelParams }
            .onFailure { if (!FloatingPet.canDraw(service)) service.stopSelf() }
    }

    fun close() {
        view?.let { runCatching { service.windows.removeView(it) } }
        view = null
        kind = null
        petIndex = null
        params = null
        heightCarry = 0f
    }
}

private const val MIN_CAL_HEIGHT = 120
