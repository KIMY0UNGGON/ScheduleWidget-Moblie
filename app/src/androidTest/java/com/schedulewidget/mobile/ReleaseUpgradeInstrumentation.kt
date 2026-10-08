package com.schedulewidget.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

/** Run after upgrading the disposable emulator's previous APK to the same-key current release APK. */
class ReleaseUpgradeInstrumentation : Instrumentation() {
    private var seed = false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); seed = arguments?.getString("seed") == "true"; start() }

    override fun onStart() {
        val failure = runCatching {
            if (seed) {
                File(targetContext.filesDir, "upgrade-marker.txt").writeText("preserve-0.0-data")
                return@runCatching
            }
            val info = targetContext.packageManager.getPackageInfo(targetContext.packageName, 0)
            check(info.versionName == BuildConfig.VERSION_NAME && PackageInfoCompat.getLongVersionCode(info) == BuildConfig.VERSION_CODE.toLong())
            check(targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
            check(File(targetContext.filesDir, "upgrade-marker.txt").readText().trim() == "preserve-0.0-data")
        }.exceptionOrNull()
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", failure?.stackTraceToString()
                ?: if (seed) "PASS: saved disposable upgrade marker\n"
                else "PASS: existing app data survived the non-debuggable v${BuildConfig.VERSION_NAME}/code${BuildConfig.VERSION_CODE} release upgrade\n") })
    }
}
