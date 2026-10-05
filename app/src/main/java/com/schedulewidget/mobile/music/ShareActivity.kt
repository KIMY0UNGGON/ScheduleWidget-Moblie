package com.schedulewidget.mobile.music

import androidx.compose.runtime.LaunchedEffect
import com.schedulewidget.mobile.data.Spotify
import com.schedulewidget.mobile.data.MusicLinks
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.ui.AppTheme
import kotlinx.coroutines.launch

/**
 * Receives "공유" from the YouTube / YouTube Music / Spotify apps (or a browser) and adds the song, album or playlist
 * to a widget playlist without opening the main app.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = if (intent?.action == Intent.ACTION_SEND) {
            // EXTRA_TEXT is often a CharSequence (spanned), for which getStringExtra returns null.
            listOfNotNull(intent.getCharSequenceExtra(Intent.EXTRA_TEXT), intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT))
                .joinToString("\n")
        } else intent?.dataString.orEmpty()
        val direct = MusicLinks.normalize(shared)
        // Spotify often shares spotify.link short links: look up the real link first.
        val short = if (direct == null) Spotify.shortLink(shared) else null
        setContent {
            AppTheme {
                var link by remember { mutableStateOf(direct) }
                var resolving by remember { mutableStateOf(short != null) }
                LaunchedEffect(short) {
                    if (short != null) {
                        link = SpotifyFetch.expandShortLink(short)
                        resolving = false
                    }
                }
                val found = link
                if (resolving) {
                    AlertDialog(
                        onDismissRequest = ::finish,
                        title = { Text("플레이리스트에 추가") },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("  Spotify 링크 확인 중…")
                            }
                        },
                        confirmButton = { TextButton(onClick = ::finish) { Text("취소") } },
                    )
                } else if (found == null) {
                    AlertDialog(
                        onDismissRequest = ::finish,
                        title = { Text("플레이리스트에 추가") },
                        text = { Text("YouTube, YouTube Music 또는 Spotify 링크를 찾지 못했습니다.") },
                        confirmButton = { TextButton(onClick = ::finish) { Text("닫기") } },
                    )
                } else {
                    ShareDialog(found, onDone = { message ->
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                        finish()
                    }, onCancel = ::finish)
                }
            }
        }
    }
}

@Composable
private fun ShareDialog(link: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { Repository.get(context) }
    val playlists = remember { repo.data.value.music.playlists }
    val scope = rememberCoroutineScope()
    val isList = MusicLinks.isList(link)
    var target by remember { mutableStateOf(repo.data.value.music.selectedPlaylist?.id) }
    var expand by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    val title by produceState<String?>(null, link) { value = YouTubeImport.title(link) }

    AlertDialog(
        onDismissRequest = { if (!working) onCancel() },
        title = { Text("플레이리스트에 추가") },
        text = {
            Column {
                Text(title ?: "제목 가져오는 중…", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(link, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (isList) Row(
                    Modifier.padding(top = 8.dp).clickable(enabled = !working) { expand = !expand },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = expand, onCheckedChange = { expand = it }, enabled = !working)
                    Text("재생목록 안의 곡을 모두 따로 추가", style = MaterialTheme.typography.bodySmall)
                }
                Text("추가할 플레이리스트", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    playlists.forEach { p ->
                        Choice("${p.name} (${p.tracks.size})", target == p.id, !working) { target = p.id }
                    }
                    Choice("새 플레이리스트 만들기", target == null, !working) { target = null }
                }
                if (working) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  가져오는 중…", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !working, onClick = {
                working = true
                scope.launch {
                    val result = YouTubeImport.resolve(link, expand)
                    YouTubeImport.addTo(context, target, result.tracks, newName = title?.takeIf { isList } ?: "내 플레이리스트")
                    onDone(result.message)
                }
            }) { Text("추가") }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !working) { Text("취소") } },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
