package com.schedulewidget.mobile.music

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.data.MusicLinks
import com.schedulewidget.mobile.data.MusicSettings
import com.schedulewidget.mobile.data.MusicTrack
import com.schedulewidget.mobile.data.Spotify
import com.schedulewidget.mobile.data.YouTube
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PlaylistPicker(
    music: MusicSettings,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selected = music.selectedPlaylist
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { open = true }, enabled = music.playlists.isNotEmpty()) {
                Text(
                    selected?.let { "${it.name} (${it.tracks.size})" } ?: "플레이리스트 없음",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                music.playlists.forEach { pl ->
                    DropdownMenuItem(
                        text = {
                            Text("${pl.name} (${pl.tracks.size})",
                                fontWeight = if (pl.id == selected?.id) FontWeight.Bold else FontWeight.Normal)
                        },
                        onClick = { open = false; onSelect(pl.id) },
                    )
                }
            }
        }
        IconButton(onClick = onNew) { Icon(Icons.Filled.Add, "새 플레이리스트") }
        IconButton(onClick = onRename, enabled = selected != null) { Icon(Icons.Filled.Edit, "이름 바꾸기") }
        IconButton(onClick = onDelete, enabled = selected != null) { Icon(Icons.Filled.Delete, "플레이리스트 삭제") }
    }
}

@Composable
internal fun NowPlayingPanel(now: NowPlaying, status: String, music: MusicSettings, loaded: Boolean) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(now.isPlaying) {
        while (now.isPlaying) { delay(500); tick = SystemClock.elapsedRealtime() }
    }
    val position = remember(now, tick) {
        val p = if (now.isPlaying) now.positionMs + (SystemClock.elapsedRealtime() - now.updatedAt) else now.positionMs
        if (now.durationMs > 0) p.coerceIn(0, now.durationMs) else p.coerceAtLeast(0)
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var volume by remember { mutableFloatStateOf(music.volume.toFloat()) }
    LaunchedEffect(music.volume) { volume = music.volume.toFloat() }

    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (loaded && now.title.isNotBlank()) now.title else "재생할 곡을 선택하세요.",
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (now.canSeek && now.durationMs > 0) {
                Slider(
                    value = dragging ?: (position.toFloat() / now.durationMs),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { WidgetPlayer.seekTo(ctx, (it * now.durationMs).toLong()) }
                        dragging = null
                    },
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatTime(dragging?.let { (it * now.durationMs).toLong() } ?: position), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(now.durationMs), style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { WidgetPlayer.previous(ctx) }) { Icon(Icons.Filled.SkipPrevious, "이전 곡") }
                FilledIconButton(onClick = { WidgetPlayer.playPause(ctx) }) {
                    Icon(if (now.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (now.isPlaying) "일시정지" else "재생")
                }
                IconButton(onClick = { WidgetPlayer.next(ctx) }) { Icon(Icons.Filled.SkipNext, "다음 곡") }
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = music.shuffle || music.repeatOne, onClick = { WidgetPlayer.cycleMode(ctx) },
                    label = { Text(WidgetPlayer.modeLabel(music)) })
                Spacer(Modifier.width(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { WidgetPlayer.setRepeat(ctx, !music.repeat) }) {
                    Checkbox(checked = music.repeat, onCheckedChange = { WidgetPlayer.setRepeat(ctx, it) })
                    Text("목록 반복", style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, "볼륨", Modifier.size(20.dp))
                Slider(
                    value = volume,
                    onValueChange = { volume = it; WidgetPlayer.previewVolume(ctx, it) },
                    onValueChangeFinished = { WidgetPlayer.setVolume(ctx, volume) },
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                Text("${(volume * 100).toInt()}%", Modifier.width(40.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TrackRow(
    index: Int,
    track: MusicTrack,
    playing: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onPlay: () -> Unit,
    marked: Boolean,
    onMark: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onRelink: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .background(if (playing) colors.primaryContainer else colors.surface)
            .combinedClickable(onClick = onPlay, onLongClick = onMark)
            .then(if (marked) Modifier.drawBehind {
                // Insertion marker under the song new tracks will follow.
                drawRect(colors.primary, topLeft = Offset(0f, size.height - 3.dp.toPx()), size = Size(size.width, 3.dp.toPx()))
            } else Modifier)
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (playing) "▶" else "${index + 1}", Modifier.width(28.dp),
            style = MaterialTheme.typography.labelMedium, color = if (playing) colors.primary else colors.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(track.title.ifBlank { "제목 없음" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal)
            val kind = when {
                track.isDesktopPath -> "재연결 필요 · PC 경로"
                track.isYouTube && YouTube.playlistId(track.source) != null -> "YouTube 재생목록"
                track.isYouTube -> "YouTube"
                track.isSpotify && Spotify.parse(track.source)?.isList == true -> "Spotify 재생목록 · Spotify 앱"
                track.isSpotify -> "Spotify · Spotify 앱"
                else -> "파일"
            }
            Text(kind, style = MaterialTheme.typography.labelSmall,
                color = if (track.isDesktopPath) colors.error else colors.onSurfaceVariant)
        }
        if (track.isDesktopPath) TextButton(onClick = onRelink) { Text("재연결") }
        IconButton(onClick = { onMove(-1) }, enabled = !isFirst) { Icon(Icons.Filled.KeyboardArrowUp, "위로") }
        IconButton(onClick = { onMove(1) }, enabled = !isLast) { Icon(Icons.Filled.KeyboardArrowDown, "아래로") }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, "삭제") }
    }
}

@Composable
internal fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("이름") }) },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
internal fun YouTubeDialog(onDismiss: () -> Unit, onAdded: (List<MusicTrack>, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var expand by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Accepts youtube.com, youtu.be, music.youtube.com and open.spotify.com / spotify.link links (or a pasted share text).
    val link = MusicLinks.normalize(url) ?: url.trim()

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("YouTube · Spotify 링크 추가") },
        text = {
            Column {
                OutlinedTextField(url,{ url = it; error = null }, singleLine = true,
                    label = { Text("YouTube · YouTube Music · Spotify 주소") }, enabled = !working)
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp).clickable(enabled = !working) { expand = !expand }) {
                    Checkbox(checked = expand, onCheckedChange = { expand = it }, enabled = !working)
                    Text("재생목록·앨범 링크는 안에 있는 곡을 모두 따로 추가", style = MaterialTheme.typography.bodySmall)
                }
                if (working) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  가져오는 중…", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !working && link.isNotEmpty(), onClick = {
                val short = if (MusicLinks.normalize(link) == null) Spotify.shortLink(link) else null
                if (MusicLinks.normalize(link) == null && short == null) {
                    error = "YouTube 영상·재생목록 또는 Spotify 곡·앨범·재생목록 주소를 입력하세요."
                    return@TextButton
                }
                working = true
                scope.launch {
                    val target = if (short != null) SpotifyFetch.expandShortLink(short) else link
                    if (target == null) {
                        working = false
                        error = "Spotify 짧은 링크를 열지 못했습니다. Spotify 앱에서 복사한 open.spotify.com 주소를 붙여 넣어 주세요."
                        return@launch
                    }
                    val result = YouTubeImport.resolve(target, expand)
                    working = false
                    onAdded(result.tracks, result.message)
                }
            }) { Text("추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("취소") } },
    )
}
private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
