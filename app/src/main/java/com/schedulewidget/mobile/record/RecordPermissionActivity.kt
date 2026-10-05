package com.schedulewidget.mobile.record

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Invisible activity the floating pet opens to start recording when it cannot do so itself: to ask for the microphone
 * permission, or because Android refused a microphone foreground service started from the overlay. Being a visible
 * activity, it is allowed to start one; it then closes right away.
 */
class RecordPermissionActivity : ComponentActivity() {
    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true || Recorder.hasPermission(this)) startAndFinish()
        else {
            Toast.makeText(this, "마이크 권한이 있어야 녹음할 수 있어요", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // the permission dialog is already up (recreated after rotation)
        if (Recorder.hasPermission(this)) startAndFinish()
        else {
            // Ask for notifications together on Android 13+, so the "녹음 중" notification with its 정지 button shows.
            val perms = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@RecordPermissionActivity, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            ask.launch(perms.toTypedArray())
        }
    }

    private fun startAndFinish() {
        if (!Recorder.isRecording) {
            runCatching { ContextCompat.startForegroundService(this, RecordService.startIntent(this, fromActivity = true)) }
                .onFailure { Toast.makeText(this, "녹음을 시작할 수 없어요", Toast.LENGTH_SHORT).show() }
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        fun launch(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(context, RecordPermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
            }
        }
    }
}
