package com.schedulewidget.mobile.pet

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.schedulewidget.mobile.R

/**
 * Opens the mobile student ID in 순천향톡 in one step: launches the app and, when the accessibility helper
 * ([IdAutoOpenService]) is turned on, has it press "모바일 신분증". Used by the pet's 신분증 button and the home-screen
 * shortcut. Without the helper it just opens 순천향톡.
 */
object IdShortcut {
    const val ID_APP = "com.sz.Atwee.sch"

    /** 순천향톡's buttons that open the ID card: the tile on its main page first, then the bottom tab. */
    val BUTTON_LABELS = listOf("모바일 신분증", "모바일신분증")

    private const val ARM_MS = 15_000L
    @Volatile private var armedUntil = 0L
    /** Set by the running service: pokes it to start looking right away. */
    @Volatile var onArmed: (() -> Unit)? = null

    fun armed(): Boolean = SystemClock.elapsedRealtime() < armedUntil
    fun done() { armedUntil = 0L }

    fun installed(context: Context): Boolean = context.packageManager.getLaunchIntentForPackage(ID_APP) != null

    fun helperEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(context, IdAutoOpenService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun open(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(ID_APP)
        if (launch == null) {
            Toast.makeText(context, "순천향톡 앱이 설치되어 있지 않습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (helperEnabled(context)) {
            armedUntil = SystemClock.elapsedRealtime() + ARM_MS
            onArmed?.invoke()
        }
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun helperSettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Asks the launcher to pin a "모바일 신분증" icon to the home screen. */
    fun pinToHome(context: Context): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val info = ShortcutInfoCompat.Builder(context, "mobile-id")
            .setShortLabel("모바일 신분증")
            .setLongLabel("순천향톡 모바일 신분증")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_id))
            .setIntent(Intent(context, IdShortcutActivity::class.java).setAction(Intent.ACTION_VIEW))
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }
}

/** Invisible target of the home-screen shortcut: opens the ID and closes itself. */
class IdShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IdShortcut.open(this)
        finish()
    }
}

/** Pet settings card: helper status (accessibility) and the home-screen shortcut. */
@Composable
fun IdShortcutCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val helper = remember(resumed) { IdShortcut.helperEnabled(context) }
    val installed = remember(resumed) { IdShortcut.installed(context) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("모바일 신분증 (순천향톡)", style = MaterialTheme.typography.bodyLarge)
        Text(
            when {
                !installed -> "순천향톡 앱이 설치되어 있지 않습니다."
                helper -> "켜짐: 말풍선의 순천향톡이나 홈 화면 바로가기를 누르면 ‘모바일 신분증’까지 자동으로 눌러 줍니다."
                else -> "지금은 순천향톡만 열립니다. 접근성 설정에서 ‘일정 위젯 · 신분증 바로 열기’를 켜면 신분증 화면까지 바로 열립니다. " +
                    "(순천향톡 화면에만, 우리 버튼을 누른 직후에만 동작합니다.)"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { context.startActivity(IdShortcut.helperSettingsIntent()) }) {
                Text(if (helper) "접근성 설정" else "자동 열기 켜기")
            }
            OutlinedButton(enabled = installed, onClick = {
                if (!IdShortcut.pinToHome(context)) Toast.makeText(context, "이 홈 화면은 바로가기 추가를 지원하지 않습니다.", Toast.LENGTH_SHORT).show()
            }) { Text("홈 화면에 바로가기") }
        }
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
