package com.schedulewidget.mobile.privacy

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val choicesChanged by GoogleDocumentConsent.choicesChanged.collectAsStateWithLifecycle()
    val allowed = androidx.compose.runtime.remember(choicesChanged) { GoogleDocumentConsent.isAllowed(context) }
    val policy by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            context.assets.open("privacy-policy.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("개인정보") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text("Drive로 문서 변환 허용") },
                supportingContent = { Text("선택한 PPT·Word를 구글로 보내 PDF로 변환합니다. 끄면 다음 변환 때 다시 확인합니다.") },
                trailingContent = {
                    Switch(checked = allowed, onCheckedChange = { on ->
                        if (on) scope.launch { GoogleDocumentConsent.awaitPermission(context, "앞으로 선택할 PPT·Word") }
                        else GoogleDocumentConsent.revoke(context)
                    })
                },
            )
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/connections")))
            }) { Text("구글 계정에서 앱 접근 권한 관리") }
            SelectionContainer {
                Text(
                    policy ?: "개인정보처리방침을 읽는 중…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
