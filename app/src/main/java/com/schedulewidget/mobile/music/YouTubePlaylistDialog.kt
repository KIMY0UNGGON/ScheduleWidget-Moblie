package com.schedulewidget.mobile.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.data.MusicTrack
import kotlinx.coroutines.launch

/**
 * "YouTube 재생목록": the signed-in account's playlists (YouTube Music playlists are YouTube playlists too, plus
 * 좋아요 표시한 동영상). One list comes in as a new widget playlist or at the marked position of the current one.
 */
@Composable
fun YouTubePlaylistDialog(
    insertLabel: String,
    onDismiss: () -> Unit,
    onImport: (tracks: List<MusicTrack>, name: String, asNewPlaylist: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var signedIn by remember { mutableStateOf(YouTubeLogin.signedIn(context)) }
    var playlists by remember { mutableStateOf<List<YouTubeLogin.RemotePlaylist>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            working = true
            error = null
            runCatching { YouTubeLogin.playlists(context) }
                .onSuccess { playlists = it }
                .onFailure { e -> error = e.message; signedIn = YouTubeLogin.signedIn(context) }
            working = false
        }
    }

    fun signIn() {
        scope.launch {
            working = true
            error = "브라우저에서 구글 계정을 고르고 허용하면 자동으로 돌아옵니다."
            runCatching { YouTubeLogin.signIn(context) }
                .onSuccess { signedIn = true; load() }
                .onFailure { e -> error = e.message ?: "로그인하지 못했습니다."; working = false }
        }
    }

    LaunchedEffect(Unit) { if (signedIn) load() }

    fun import(p: YouTubeLogin.RemotePlaylist, asNew: Boolean) {
        scope.launch {
            working = true
            runCatching { YouTubeLogin.tracks(context, p.id) }
                .onSuccess { if (it.isEmpty()) { error = "‘${p.title}’에 가져올 곡이 없습니다."; working = false } else onImport(it, p.title, asNew) }
                .onFailure { error = it.message; working = false }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("YouTube 재생목록") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!signedIn) {
                    Text(
                        "YouTube(YouTube Music 포함) 계정의 재생목록과 좋아요 목록을 가져옵니다. 브라우저에서 한 번 로그인하면 이후에는 바로 열립니다.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { signIn() }, enabled = !working) { Text("YouTube 로그인") }
                } else {
                    YouTubeLogin.account(context)?.let {
                        Text("계정: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (working) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  불러오는 중…", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                playlists?.let { list ->
                    if (list.isEmpty()) Text("이 계정에 재생목록이 없습니다.", style = MaterialTheme.typography.bodyMedium)
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(list, key = { it.id }) { p ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(p.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (p.count >= 0) Text("${p.count}곡", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row {
                                    TextButton(onClick = { import(p, true) }, enabled = !working) { Text("새 플레이리스트로") }
                                    TextButton(onClick = { import(p, false) }, enabled = !working) { Text(insertLabel) }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
        dismissButton = if (signedIn) ({
            TextButton(onClick = { YouTubeLogin.signOut(context); signedIn = false; playlists = null; error = null }, enabled = !working) {
                Text("로그아웃")
            }
        }) else null,
    )
}
