package com.schedulewidget.mobile.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.schedulewidget.mobile.BuildConfig
import com.schedulewidget.mobile.ui.SectionTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private const val APK_MIME = "application/vnd.android.package-archive"

/**
 * Settings section "앱 업데이트": manual check, download with progress, and handing the verified APK to the
 * system package installer (the user confirms there). Nothing runs unless the user taps; all work belongs to
 * this composition, so leaving Settings cancels it and drops the token.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppUpdateSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Token: plain remember only, never saved (no rememberSaveable, preferences, AppData or logs).
    var token by remember { mutableStateOf("") }
    var tokenOpen by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<UpdateRelease?>(null) }
    var prepared by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    // Written from the download thread; collected on the main thread.
    val progress = remember { MutableStateFlow(0L) }
    val downloaded by progress.collectAsState()

    fun errorText(e: Throwable): String {
        val m = e.message ?: e.javaClass.simpleName
        return if (token.isBlank()) m else m.replace(token, "***")
    }

    fun runStep(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                status = "취소했습니다"
                throw e
            } catch (e: Exception) {
                status = "$label 실패: ${errorText(e)}"
            } finally {
                busy = null
            }
        }
    }

    fun openInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            status = "Android 설치 화면에서 업데이트를 확인해 주세요."
        } catch (_: ActivityNotFoundException) {
            status = "이 기기에서 설치 프로그램을 열 수 없습니다. GitHub 릴리스에서 직접 받아 설치해 주세요."
        }
    }

    // Re-checks the prepared file (it may have changed while the user was away) before the installer sees it.
    suspend fun verifyAndOpen(rel: UpdateRelease, apk: File) {
        busy = "파일 검증"
        try {
            withContext(Dispatchers.IO) { AppUpdates.validateApk(context.applicationContext, rel, apk) }
        } catch (e: Exception) {
            if (e !is CancellationException) prepared = null
            throw e
        }
        openInstaller(apk)
    }

    // Registered unconditionally (never inside an if), so it lives as long as the card does.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val rel = release
        val apk = prepared
        if (rel == null || apk == null) {
            status = "준비된 파일이 없습니다. 다시 다운로드해 주세요."
        } else if (!context.packageManager.canRequestPackageInstalls()) {
            status = "설치 권한이 허용되지 않았습니다. '설치 다시 시도'를 눌러 다시 허용할 수 있습니다."
        } else {
            runStep("설치 준비") { verifyAndOpen(rel, apk) }
        }
    }

    fun askInstallPermission() {
        status = "이 앱의 '출처를 알 수 없는 앱 설치'를 허용한 뒤 돌아와 주세요."
        try {
            permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        } catch (_: ActivityNotFoundException) {
            status = "설정 화면을 열 수 없습니다. 기기 설정 > 앱 > 특별한 접근 > 출처를 알 수 없는 앱 설치에서 이 앱을 허용한 뒤 '설치 다시 시도'를 눌러 주세요."
        }
    }

    SectionTitle("앱 업데이트")
    Column(modifier.padding(horizontal = 16.dp)) {
        Text("현재 버전", style = MaterialTheme.typography.bodyLarge)
        Text("Ver ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
        Text(
            "직접 누를 때만 확인합니다. 설치는 Android 설치 화면에서 직접 확인해야 진행됩니다.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        release?.let { Text("최신 버전: ${it.version} (${mb(it.size)} MB)", Modifier.padding(top = 8.dp)) }
        busy?.let { Text("$it 중…", Modifier.padding(top = 8.dp)) }
        status?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium) }

        if (busy == "다운로드") {
            val total = release?.size ?: 0L
            if (total > 0) {
                LinearProgressIndicator(progress = { (downloaded.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text("${mb(downloaded)} / ${mb(total)} MB · ${(downloaded * 100 / total).coerceIn(0, 100)}%", style = MaterialTheme.typography.bodySmall)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text("${mb(downloaded)} MB", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "완료될 때까지 이 화면에 머물러 주세요. 설정 화면을 나가면 다운로드가 취소됩니다.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedButton(enabled = busy == null, onClick = {
                runStep("업데이트 확인") {
                    release = null
                    prepared = null
                    status = null
                    val found = withContext(Dispatchers.IO) { AppUpdates.check(BuildConfig.VERSION_NAME, token.trim()) }
                    release = found
                    status = if (found == null) "확인 완료 · 최신 버전입니다" else "확인 완료 · 새 버전 ${found.version}이 있습니다"
                }
            }) { Text("업데이트 확인") }
            val rel = release
            val apk = prepared
            if (rel != null && apk == null) Button(enabled = busy == null, onClick = {
                runStep("다운로드") {
                    progress.value = 0L
                    val file = withContext(Dispatchers.IO) {
                        AppUpdates.download(context.applicationContext, rel, token.trim()) { progress.value = it }
                    }
                    prepared = file
                    // download returns only a fully validated file.
                    if (context.packageManager.canRequestPackageInstalls()) openInstaller(file) else askInstallPermission()
                }
            }) { Text("다운로드 후 설치") }
            if (rel != null && apk != null) Button(enabled = busy == null, onClick = {
                if (context.packageManager.canRequestPackageInstalls()) runStep("설치 준비") { verifyAndOpen(rel, apk) }
                else askInstallPermission()
            }) { Text("설치 다시 시도") }
            if (busy != null) OutlinedButton(onClick = { job?.cancel() }) { Text("취소") }
            TextButton(onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdates.RELEASES_URL)))
                } catch (_: ActivityNotFoundException) {
                    status = "브라우저를 열 수 없습니다: ${AppUpdates.RELEASES_URL}"
                }
            }) { Text("GitHub 릴리스 열기") }
        }

        TextButton(onClick = { tokenOpen = !tokenOpen }) { Text(if (tokenOpen) "비공개 저장소 접근 ▲" else "비공개 저장소 접근 ▼") }
        if (tokenOpen) {
            OutlinedTextField(
                value = token,
                onValueChange = {
                    // A different token may see different releases: drop everything checked with the old one.
                    token = it
                    release = null
                    prepared = null
                    status = null
                },
                enabled = busy == null,
                singleLine = true,
                label = { Text("GitHub 토큰") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "저장소가 비공개인 동안만 필요합니다. ${AppUpdates.REPOSITORY} 저장소 하나에 Contents 읽기(Read-only) 권한만 준 " +
                    "fine-grained 토큰을 사용하세요. 토큰은 저장하지 않으며 이 화면을 나가면 지워집니다.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun mb(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / 1_048_576.0)
