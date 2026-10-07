package com.schedulewidget.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

/** Run after upgrading the disposable emulator's real v0.0 APK to the same-key v0.1 release APK. */
class ReleaseUpgradeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val failure = runCatching {
            val info = targetContext.packageManager.getPackageInfo(targetContext.packageName, 0)
            check(info.versionName == "0.1" && PackageInfoCompat.getLongVersionCode(info) == 5L)
            check(targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
            check(File(targetContext.filesDir, "upgrade-marker.txt").readText().trim() == "preserve-0.0-data")
        }.exceptionOrNull()
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", failure?.stackTraceToString()
                ?: "PASS: v0.0 app data survived the non-debuggable v0.1/code5 release upgrade\n") })
    }
}
