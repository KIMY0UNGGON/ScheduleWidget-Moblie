package com.schedulewidget.mobile.pet

import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.schedulewidget.mobile.MainActivity
import com.schedulewidget.mobile.R
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.ui.Route
import com.schedulewidget.mobile.record.PetRecordingBadge
import com.schedulewidget.mobile.record.Recorder
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt
import kotlin.math.pow

/**
 * Keeps the pet floating over other apps. Drag to move, tap for a reaction, double-tap to open a small calendar
 * panel next to it. Runs as a foreground service so it survives the app being closed.
 */
class PetOverlayService : LifecycleService(), SavedStateRegistryOwner {
    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    internal lateinit var windows: WindowManager
    private val panels by lazy { PetOverlayPanels(this) }
    internal val main = Handler(Looper.getMainLooper())
    internal val petWindows = linkedMapOf<Int, PetOverlayWindow>()
    internal var activePetIndex = 0
    internal var destroyed = false
    /** The pets' walls: the screen minus the visible system bars and cutout, in their params' coordinates. */
    private var area = Size(0, 0)
    private val opacityLimit by lazy {
        if (Build.VERSION.SDK_INT >= 31)
            (getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch - 0.01f).coerceIn(0f, 1f)
        else 1f
    }

    // "Draw over other apps" can be revoked while we run: the system hides our windows, so stop instead of
    // lingering as an invisible foreground service. canDrawOverlays lags the callback a little, hence the delay.
    private val appOps by lazy { getSystemService(AppOpsManager::class.java) }
    private val checkOverlay = Runnable { if (!FloatingPet.canDraw(this)) stopSelf() }
    private val opListener = AppOpsManager.OnOpChangedListener { _, pkg ->
        if (pkg == packageName) {
            main.removeCallbacks(checkOverlay)
            main.postDelayed(checkOverlay, 500)
        }
    }

    override fun onCreate() {
        savedState.performAttach()
        savedState.performRestore(null)
        super.onCreate()
        windows = getSystemService(WindowManager::class.java)
        closeTarget = PetOverlayCloseTarget(this, windows) { if (!FloatingPet.canDraw(this)) stopSelf() }
        if (runCatching { startInForeground() }.isFailure) {
            // e.g. ForegroundServiceStartNotAllowedException when (re)started from the background.
            stopSelf()
            return
        }
        if (!FloatingPet.canDraw(this)) {
            stopSelf()
            return
        }
        measureArea()
        syncPets(Repository.get(this).data.value)
        runCatching { appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, opListener) }
        // While the app itself is open its own pet is shown; this one hides and returns when the app is closed.
        lifecycleScope.launch {
            FloatingPet.appInForeground.collect { appOpen ->
                if (appOpen) {
                    // Drop a pending tap/hold too, or it would pop the panel / ✕ target over the app a moment later.
                    petWindows.values.forEach { it.touch.cancel() }
                    closePanel()
                    hideCloseTarget()
                }
                petWindows.values.forEach { it.view.visibility = if (appOpen) View.GONE else View.VISIBLE }
                updatePetOpacity()
            }
        }
        lifecycleScope.launch {
            Repository.get(this@PetOverlayService).data.collect { syncPets(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_HIDE) {
            FloatingPet.disable(this)
            stopSelf()
        } else if (!FloatingPet.canDraw(this) || !Repository.get(this).data.value.floatingPet) {
            stopSelf()
        } else {
            syncPets(Repository.get(this).data.value)
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation / fold / resolution change: pull the pet and the panel back onto the new screen.
        measureArea()
        petWindows.values.forEach(::fit)
        panels.clamp()
    }

    override fun onDestroy() {
        destroyed = true
        runCatching { appOps.stopWatchingMode(opListener) }
        main.removeCallbacksAndMessages(null)
        petWindows.values.forEach { it.touch.cancel() }
        hideCloseTarget()
        closePanel()
        petWindows.values.forEach(::removePetWindow)
        petWindows.clear()
        super.onDestroy()
    }

    internal fun overlayParams(width: Int = WindowManager.LayoutParams.WRAP_CONTENT) = WindowManager.LayoutParams(
        width,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    internal fun composeView(content: @androidx.compose.runtime.Composable () -> Unit) = ComposeView(this).apply {
        setViewTreeLifecycleOwner(this@PetOverlayService)
        setViewTreeSavedStateRegistryOwner(this@PetOverlayService)
        setContent(content)
    }

    private fun syncPets(data: com.schedulewidget.mobile.data.AppData) {
        val slots = data.petSlots().take(3)
        val live = slots.filterNot { it.hidden }.associateBy { it.index }
        petWindows.keys.toList().forEach { index ->
            val pet = petWindows[index] ?: return@forEach
            if (live[index]?.manifest != pet.manifest) {
                if (activePetIndex == index) closePanel()
                removePetWindow(pet)
                petWindows.remove(index)
            }
        }
        for (slot in live.values) {
            val existing = petWindows[slot.index]
            if (existing == null) addPetWindow(slot, data)
            else if (!existing.touch.isDragging) {
                val x = slot.floatingX ?: existing.wantX
                val y = slot.floatingY ?: existing.wantY
                if (x != existing.wantX || y != existing.wantY) moveTo(existing, x, y)
            }
        }
        if (activePetIndex !in petWindows) activePetIndex = petWindows.keys.firstOrNull() ?: 0
        val visible = !FloatingPet.appInForeground.value
        petWindows.values.forEach { it.view.visibility = if (visible) View.VISIBLE else View.GONE }
        updatePetOpacity()
    }

    private fun addPetWindow(slot: PetSlot, data: com.schedulewidget.mobile.data.AppData) {
        val index = slot.index
        val gap = data.petSlots().take(index).filterNot { it.hidden }.sumOf { before ->
            ((110 * before.scale.coerceIn(50, 300) / 100f * Characters.CELL_W / Characters.CELL_H + 8) * resources.displayMetrics.density).roundToInt()
        }
        val x = slot.floatingX ?: (data.floatingX + gap)
        val y = slot.floatingY ?: data.floatingY
        // NO_LIMITS lets the window pass the screen edges (negative x/y) so only its painted pixels meet the walls. Its
        // origin stays below the status bar, the same as the panels placed from these params.
        val params = overlayParams().apply {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            this.x = x
            this.y = y
        }
        val pet = PetOverlayWindow(index, slot.manifest, params)
        val repo = Repository.get(this)
        pet.view = composeView {
            // The first load and every frame, flip or bounce after it re-fit the window from its anchor.
            LaunchedEffect(Unit) { snapshotFlow { pet.painted.frame }.collect { fit(pet) } }
            val d by repo.data.collectAsState()
            val current = d.petSlots().firstOrNull { it.index == index }
            val appOpen by FloatingPet.appInForeground.collectAsState()
            val rec by Recorder.state.collectAsState()
            if (current != null && !current.hidden) {
                val height = (110 * current.scale.coerceIn(50, 300) / 100f).dp
                Box {
                    CharacterSprite(
                        current.manifest, current.animation, height,
                        interactive = false,
                        reactKey = pet.reactKey.intValue,
                        flipped = current.flipped,
                        animated = !appOpen,
                        painted = pet.painted,
                    )
                    if (d.stt.enabled && rec.isRecording) PetRecordingBadge(
                        Modifier.align(Alignment.TopEnd).offset {
                            val body = pet.painted.fraction ?: FULL_BOX
                            IntOffset(((body.right - 1f) * pet.view.width).roundToInt(), (body.top * pet.view.height).roundToInt())
                        }.padding(4.dp)
                    )
                }
            }
        }
        pet.touch = PetOverlayTouch(this, pet)
        pet.view.setOnTouchListener(pet.touch)
        // Keep each pet on screen when its size changes or its saved spot is stale.
        pet.view.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) fit(pet)
        }
        runCatching { windows.addView(pet.view, pet.params) }
            .onSuccess { petWindows[index] = pet }
            .onFailure { if (!FloatingPet.canDraw(this)) stopSelf() }
    }

    private fun removePetWindow(pet: PetOverlayWindow) {
        if (::windows.isInitialized) {
            pet.view.setOnTouchListener(null)
            pet.touch.cancel()
            runCatching { windows.removeView(pet.view) }
        }
    }

    /** Preserves the floating pet's one-, two-, and three-tap actions. */
    internal fun onPetTap(index: Int, count: Int) {
        if (destroyed) return
        activePetIndex = index
        val recordOn = Recorder.enabled(this)
        when {
            count == 1 -> { petWindows[index]?.reactKey?.let { it.intValue++ }; panels.toggle(PetPanel.Menu) }
            count == 2 && recordOn && Recorder.isRecording -> { closePanel(); Recorder.stop(this) }
            count == 2 -> panels.toggle(PetPanel.Calendar)
            recordOn -> {
                if (!Recorder.isRecording) closePanel()
                Recorder.start(this)
            }
            else -> panels.toggle(PetPanel.Music)
        }
    }

    // ---- ✕ drop target -------------------------------------------------------------------------------------------

    private lateinit var closeTarget: PetOverlayCloseTarget

    internal fun showCloseTarget() {
        if (!destroyed) closeTarget.show()
    }

    internal fun highlightCloseTarget(over: Boolean) {
        closeTarget.highlight(over)
    }

    internal fun hideCloseTarget() {
        closeTarget.hide()
    }

    /** True when the pet's centre is over the ✕ target (with some slack so it is easy to hit). */
    internal fun overCloseTarget(pet: View): Boolean = closeTarget.containsCenter(
        pet, petWindows.values.firstOrNull { it.view === pet }?.painted?.fraction ?: FULL_BOX,
    )

    /**
     * Asks [pet] to be at [x], [y] (its anchor) and shows it as near as keeps the drawn frame's painted pixels inside
     * [area]: transparent margins may hang past the walls.
     */
    internal fun moveTo(pet: PetOverlayWindow, x: Int, y: Int) {
        pet.wantX = x
        pet.wantY = y
        val v = pet.view
        if (!v.isLaidOut) return
        val p = pet.painted.fraction ?: FULL_BOX
        val minX = -(p.left * v.width).roundToInt()
        val maxX = area.width - (p.right * v.width).roundToInt()
        val minY = -(p.top * v.height).roundToInt()
        val maxY = area.height - (p.bottom * v.height).roundToInt()
        val nx = x.coerceIn(minX, maxX.coerceAtLeast(minX))
        val ny = y.coerceIn(minY, maxY.coerceAtLeast(minY))
        if (nx == pet.params.x && ny == pet.params.y) return
        pet.params.x = nx
        pet.params.y = ny
        if (v.isAttachedToWindow) runCatching { windows.updateViewLayout(v, pet.params) }
        updatePetOpacity()
    }

    /** Re-fits [pet] from its anchor to the frame now drawn; from API 33 only its painted pixels are touchable. */
    private fun fit(pet: PetOverlayWindow) {
        moveTo(pet, pet.wantX, pet.wantY)
        if (Build.VERSION.SDK_INT >= 33) {
            pet.view.rootSurfaceControl?.setTouchableRegion(pet.painted.region(pet.view.width, pet.view.height))
        }
        updatePetOpacity()
    }

    /** Android blocks input behind opaque overlay frames even where their touchable region has a hole. */
    private fun updatePetOpacity() {
        if (Build.VERSION.SDK_INT < 33) return
        val visible = petWindows.values.filter { it.view.isShown }
        fun frame(pet: PetOverlayWindow) = Rect(pet.params.x, pet.params.y, pet.params.x + pet.view.width, pet.params.y + pet.view.height)
        for (pet in visible) {
            val overlaps = visible.count { Rect.intersects(frame(pet), frame(it)) }.coerceAtLeast(1)
            val alpha = 1f - (1f - opacityLimit).toDouble().pow(1.0 / overlaps).toFloat()
            if (pet.params.alpha == alpha) continue
            pet.params.alpha = alpha
            if (pet.view.isAttachedToWindow) runCatching { windows.updateViewLayout(pet.view, pet.params) }
        }
    }

    /** Re-reads [area]. The pet windows' origin is the top-left of the screen minus the same insets. */
    internal fun measureArea() {
        area = if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.currentWindowMetrics
            val bars = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            Size(metrics.bounds.width() - bars.left - bars.right, metrics.bounds.height() - bars.top - bars.bottom)
        } else {
            // ponytail: no insets query before API 30; the app area still counts the status bar, so the bottom wall can
            // sit up to a status-bar height low there. Measure the window origin if those devices matter.
            resources.displayMetrics.let { Size(it.widthPixels, it.heightPixels) }
        }
    }

    internal fun closePanel() = panels.close()

    internal fun openApp(route: Route) {
        closePanel()
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ROUTE, route.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "화면 위 펫", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hide = PendingIntent.getService(
            this, 1, Intent(this, PetOverlayService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("펫이 화면 위에 있어요")
            .setContentText(
                if (Repository.get(this).data.value.stt.enabled) "누르면 메뉴, 두 번 누르면 달력, 세 번 누르면 수업 녹음, 끌어서 이동"
                else "누르면 메뉴, 두 번 누르면 달력, 세 번 누르면 MP3, 끌어서 이동"
            )
            .setContentIntent(open)
            .addAction(0, "펫 숨기기", hide)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val CHANNEL = "floating_pet"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_HIDE = "com.schedulewidget.mobile.HIDE_PET"
    }
}
