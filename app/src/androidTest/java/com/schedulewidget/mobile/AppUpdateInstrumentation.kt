package com.schedulewidget.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.core.content.FileProvider
import com.schedulewidget.mobile.pet.Characters
import com.schedulewidget.mobile.update.AppUpdates
import com.schedulewidget.mobile.update.UpdateException
import com.schedulewidget.mobile.update.UpdateRelease
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLStreamHandler
import java.security.MessageDigest

/** Runs against a disposable emulator and a locally built, same-key future APK fixture. No real GitHub token. */
class AppUpdateInstrumentation : Instrumentation() {
    private lateinit var fixture: File
    private var futureVersion = "0.2"
    private var mode = "available"
    private var apiAuthorization: String? = null
    private var cdnAuthorization: String? = null
    private var showInstaller = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        fixture = File(requireNotNull(arguments?.getString("futureApk")))
        futureVersion = arguments?.getString("futureVersion")?.takeIf { it.isNotBlank() } ?: futureVersion
        showInstaller = arguments?.getString("showInstaller") == "true"
        start()
    }

    override fun onStart() {
        val results = mutableListOf<String>()
        var failures = 0
        fun test(name: String, action: () -> Unit) {
            try { action(); results += "PASS: $name" }
            catch (e: Throwable) { failures++; results += "FAIL: $name: ${e.stackTraceToString()}" }
        }
        try {
            check(fixture.isFile) { "Missing local future-version APK fixture" }
            val installedVersion = targetContext.packageManager.getPackageInfo(targetContext.packageName, 0).versionName.orEmpty()
            val release = UpdateRelease(futureVersion, 123, "ScheduleWidget-mobile-notes.apk", fixture.length(), sha256(fixture), "${AppUpdates.RELEASES_URL}/tag/v$futureVersion")
            val metadata = """{"draft":false,"prerelease":false,"tag_name":"v$futureVersion","html_url":"${release.releaseUrl}",
                "assets":[{"id":123,"name":"${release.assetName}","size":${release.size},"digest":"sha256:${release.sha256}",
                "state":"uploaded","url":"https://api.github.com/repos/${AppUpdates.REPOSITORY}/releases/assets/123"}]}"""
            URL.setURLStreamHandlerFactory { protocol ->
                if (protocol != "https") null else object : URLStreamHandler() {
                    override fun openConnection(url: URL) = object : HttpURLConnection(url) {
                        override fun connect() {}
                        override fun disconnect() {}
                        override fun usingProxy() = false
                        override fun getResponseCode(): Int {
                            if (url.host == "release-assets.githubusercontent.com") {
                                cdnAuthorization = getRequestProperty("Authorization")
                                return 200
                            }
                            apiAuthorization = getRequestProperty("Authorization")
                            return when {
                                url.path.endsWith("/assets/123") -> 302
                                mode == "unauthorized" -> 401
                                mode == "private" -> 404
                                mode == "empty" && url.path.endsWith("/latest") -> 404
                                else -> 200
                            }
                        }
                        override fun getHeaderField(name: String): String? =
                            if (name == "Location") "https://release-assets.githubusercontent.com/test/update.apk" else null
                        override fun getContentLengthLong(): Long = if (url.host == "release-assets.githubusercontent.com") fixture.length() else metadata.toByteArray().size.toLong()
                        override fun getInputStream() = if (url.host == "release-assets.githubusercontent.com") fixture.inputStream() else ByteArrayInputStream(metadata.toByteArray())
                    }
                }
            }
            test("Bundled pets are the four mochi characters") {
                val builtins = Characters.list(targetContext).filterNot { it.imported }.map { it.id }.toSet()
                check(builtins == setOf("mochi-white", "mochi-black", "mochi-blue", "mochi-red")) { "$builtins" }
                check(Characters.find(targetContext, Characters.DEFAULT) != null)
            }
            test("New stable release is offered; same version is current") {
                mode = "available"
                apiAuthorization = "unexpected"
                check(installedVersion == "0.1") { "Unexpected installed version: $installedVersion" }
                check(runBlocking { AppUpdates.check(installedVersion) }?.version == futureVersion)
                check(apiAuthorization == null) { "Anonymous public update check sent Authorization" }
                check(runBlocking { AppUpdates.check(futureVersion) } == null)
            }
            test("Missing public repository and unpublished releases are errors") {
                mode = "private"
                expectFailure("공개 GitHub 저장소") { runBlocking { AppUpdates.check(installedVersion) } }
                mode = "empty"
                expectFailure("정식 릴리즈") { runBlocking { AppUpdates.check(installedVersion) } }
                mode = "unauthorized"
                expectFailure("토큰") { runBlocking { AppUpdates.check(installedVersion, "audit_read_only") } }
                expectFailure("GitHub가 요청을 거부") { runBlocking { AppUpdates.check(installedVersion) } }
            }
            test("Same-key newer APK passes Android package validation") {
                AppUpdates.validateApk(targetContext, release, fixture)
            }
            test("Installed APK cannot be offered as an upgrade") {
                val own = File(targetContext.applicationInfo.sourceDir)
                expectFailure("버전 코드") {
                    AppUpdates.validateApk(targetContext, release.copy(version = installedVersion, size = own.length(), sha256 = sha256(own)), own)
                }
            }
            test("Download verifies bytes and APK; token stays on GitHub API") {
                mode = "available"
                var progress = 0L
                val apk = runBlocking { AppUpdates.download(targetContext, release, "audit_read_only") { progress = it } }
                check(progress == release.size && apk.length() == release.size)
                check(apiAuthorization == "Bearer audit_read_only" && cdnAuthorization == null)
                val uri = FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.files", apk)
                check(uri.scheme == "content")
                check(targetContext.contentResolver.openInputStream(uri)!!.use { it.read() } == 0x50)
            }
            test("Anonymous public APK download sends no Authorization") {
                mode = "available"
                apiAuthorization = "unexpected"
                cdnAuthorization = "unexpected"
                val apk = runBlocking { AppUpdates.download(targetContext, release, onProgress = {}) }
                check(apk.length() == release.size)
                check(apiAuthorization == null && cdnAuthorization == null) { "Anonymous download sent Authorization" }
            }
            test("Wrong digest removes partial and rejected download") {
                expectFailure("해시") { runBlocking { AppUpdates.download(targetContext, release.copy(sha256 = "b".repeat(64))) } }
                check(File(targetContext.cacheDir, "app-updates").listFiles().orEmpty().isEmpty())
            }
            if (failures == 0) {
                File(targetContext.filesDir, "update-smoke-marker.txt").writeText("kept")
                if (showInstaller) {
                    val apk = fixture.copyTo(File(targetContext.cacheDir, "app-updates/installer-fixture.apk"), overwrite = true)
                    AppUpdates.validateApk(targetContext, release, apk)
                    val uri = FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.files", apk)
                    runOnMainSync {
                        targetContext.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                    // Keep the URI grant alive while the external smoke driver confirms the system dialog.
                    sendStatus(0, Bundle().apply { putString("stream", results.joinToString("\n", "\n", "\nREADY: confirm Android installer\n")) })
                    Thread.sleep(30_000)
                }
            }
        } catch (e: Throwable) {
            failures++
            results += e.stackTraceToString()
        }
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", results.joinToString("\n", "\n", "\nFailures: $failures\n")) })
    }

    private fun expectFailure(message: String, action: () -> Unit) {
        try { action(); error("Expected update rejection") }
        catch (e: UpdateException) { check(e.message.orEmpty().contains(message)) { e.message.orEmpty() } }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
