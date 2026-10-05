package com.schedulewidget.mobile.pet

import kotlinx.coroutines.flow.MutableStateFlow
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.schedulewidget.mobile.data.Repository

/**
 * The pet "taken out" of the app: it floats over other apps (PetOverlayService) and stays after the app is closed.
 * AppData.floatingPet is the user's wish; the overlay only shows while "draw over other apps" is granted.
 */
object FloatingPet {
    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * True while the app's screen is visible. The floating pet steps aside then (the in-app pet is shown instead, crisp),
     * and comes back over the home screen / other apps once the app is closed.
     */
    val appInForeground = MutableStateFlow(false)

    // Screens of ours that are visible now (the app, the quick schedule dialog). A set, not a counter: the next screen
    // starts before the previous one stops, and a rotation restarts the same screen.
    private val visibleScreens = HashSet<String>()

    /** Called from onStart / onStop of our screens; while any is visible the floating pet and its panels step aside. */
    fun screenVisible(key: String, visible: Boolean) {
        if (visible) visibleScreens += key else visibleScreens -= key
        appInForeground.value = visibleScreens.isNotEmpty()
    }

    /** True when the pet is actually floating outside the app (the in-app pet is then drawn crisp, not see-through). */
    fun isActive(context: Context): Boolean = Repository.get(context).data.value.floatingPet && canDraw(context)

    fun permissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Takes the pet outside. Asks for the overlay permission first when needed; [sync] starts it once granted. */
    fun enable(context: Context) {
        Repository.get(context).update { it.copy(floatingPet = true) }
        if (canDraw(context)) start(context) else runCatching { context.startActivity(permissionIntent(context)) }
    }

    fun disable(context: Context) {
        Repository.get(context).update { it.copy(floatingPet = false) }
        context.stopService(Intent(context, PetOverlayService::class.java))
    }

    /** Starts or stops the overlay to match the saved setting (app start, resume, boot). */
    fun sync(context: Context) {
        if (Repository.get(context).data.value.floatingPet && canDraw(context)) start(context)
        else context.stopService(Intent(context, PetOverlayService::class.java))
    }

    private fun start(context: Context) {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, PetOverlayService::class.java)) }
    }
}

/** Brings the floating pet back after a reboot or app update. */
class FloatingPetBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            FloatingPet.sync(context)
        }
    }
}

/** Settings row: "화면 위에 띄우기" switch with permission hint. */
@Composable
fun FloatingPetToggle(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val data by remember { Repository.get(context) }.data.collectAsState()
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val granted = remember(resumed) { FloatingPet.canDraw(context) }
    val on = data.floatingPet && granted
    Row(
        modifier.fillMaxWidth().clickable { if (on) FloatingPet.disable(context) else FloatingPet.enable(context) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("화면 위에 펫 띄우기", style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    data.floatingPet && !granted -> "'다른 앱 위에 표시' 권한을 허용하면 나타납니다."
                    data.stt.enabled -> "앱을 닫아도 홈 화면·다른 앱 위에 남습니다. 끌어서 옮기고, 누르면 메뉴, 두 번 누르면 달력, 세 번 누르면 수업 녹음이 시작돼요 (녹음 중 두 번 = 정지)."
                    else -> "앱을 닫아도 홈 화면·다른 앱 위에 남습니다. 끌어서 옮기고, 누르면 메뉴, 두 번 누르면 달력, 세 번 누르면 MP3가 열립니다."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = on, onCheckedChange = { if (it) FloatingPet.enable(context) else FloatingPet.disable(context) })
    }
}
