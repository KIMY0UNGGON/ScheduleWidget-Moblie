package com.schedulewidget.mobile.music

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.MusicPlaylist
import com.schedulewidget.mobile.data.MusicSettings
import com.schedulewidget.mobile.data.MusicTrack
import com.schedulewidget.mobile.data.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun updateMusic(ctx: Context, f: (MusicSettings) -> MusicSettings) =
    Repository.get(ctx).update { it.copy(music = f(it.music)) }

private fun updatePlaylist(ctx: Context, id: String, f: (MusicPlaylist) -> MusicPlaylist) =
    updateMusic(ctx) { m -> m.copy(playlists = m.playlists.map { if (it.id == id) f(it) else it }) }

/** Invisible WebView host for the playlist YouTube player. */
@Composable
fun YouTubeHost() = YouTubeHostContent()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by Repository.get(ctx).data.collectAsStateWithLifecycle()
    val music = data.music
    val playlist = music.selectedPlaylist
    val now by WidgetPlayer.state.collectAsStateWithLifecycle()
    val current by WidgetPlayer.current.collectAsStateWithLifecycle()
    val status by WidgetPlayer.status.collectAsStateWithLifecycle()
    val blocked by WidgetPlayer.blocked.collectAsStateWithLifecycle()

    var nameDialog by remember { mutableStateOf<Pair<String?, String>?>(null) } // (playlistId or null=new, initial)
    var confirmDelete by remember { mutableStateOf(false) }
    var youtubeDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var relinkIndex by remember { mutableStateOf(-1) }
    var libraryDialog by remember { mutableStateOf(false) }
    // Long-pressing a song marks "insert after this one" for the next files / links / Google import (null = end).
    // Stored with its playlist id (not remember(playlist.id)) so "재생 중 목록" can set it for the list it just selected,
    // and cleared/clamped when songs are removed so it never points past the end.
    var mark by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val insertAfter: Int? = mark?.takeIf { playlist != null && it.first == playlist.id && it.second in playlist.tracks.indices }?.second
    fun setInsertAfter(value: Int?, playlistId: String? = playlist?.id) {
        mark = if (value == null || playlistId == null) null else playlistId to value
    }

    fun ensurePlaylist(): String {
        playlist?.let { return it.id }
        val created = MusicPlaylist()
        updateMusic(ctx) { it.copy(playlists = it.playlists + created, selectedPlaylistId = created.id) }
        return created.id
    }

    fun addTracks(tracks: List<MusicTrack>) {
        if (tracks.isEmpty()) return
        val id = ensurePlaylist()
        val after = mark?.takeIf { it.first == id }?.second // live state read (callbacks may run after an await)
        updatePlaylist(ctx, id) { p ->
            val at = after?.let { (it + 1).coerceIn(0, p.tracks.size) } ?: p.tracks.size
            p.copy(tracks = p.tracks.take(at) + tracks + p.tracks.drop(at))
        }
        // Keep inserting after the block just added, so several additions stay in order.
        if (after != null) setInsertAfter(after + tracks.size, id)
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val tracks = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    persist(ctx, uri)
                    MusicTrack(title = displayTitle(ctx, uri), source = uri.toString())
                }
            }
            addTracks(tracks)
            message = "${tracks.size}곡을 추가했습니다."
        }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val tracks = withContext(Dispatchers.IO) {
                persist(ctx, tree)
                collectFolder(ctx, tree)
            }
            busy = false
            addTracks(tracks)
            message = if (tracks.isEmpty()) "폴더에서 음악 파일을 찾지 못했습니다." else "폴더에서 ${tracks.size}곡을 추가했습니다."
        }
    }

    val pickRelink = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val index = relinkIndex
        relinkIndex = -1
        val pl = playlist ?: return@rememberLauncherForActivityResult
        if (uri == null || index !in pl.tracks.indices) return@rememberLauncherForActivityResult
        scope.launch {
            val title = withContext(Dispatchers.IO) { persist(ctx, uri); displayTitle(ctx, uri) }
            updatePlaylist(ctx, pl.id) { p ->
                p.copy(tracks = p.tracks.mapIndexed { i, t ->
                    if (i == index) t.copy(source = uri.toString(), title = t.title.ifBlank { title }) else t
                })
            }
            message = "‘${pl.tracks[index].title}’을(를) 다시 연결했습니다."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("음악 플레이리스트") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PlaylistPicker(
                music = music,
                onSelect = { id -> updateMusic(ctx) { it.copy(selectedPlaylistId = id) } },
                onNew = { nameDialog = null to "새 플레이리스트" },
                onRename = { playlist?.let { nameDialog = it.id to it.name } },
                onDelete = { if (playlist != null) confirmDelete = true },
            )
            NowPlayingPanel(now, status, music, current != null)
            blocked?.let { track ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("‘${track.title}’은(는) 앱에서 재생이 막혀 있습니다.", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    TextButton(onClick = { WidgetPlayer.openInYouTube(ctx, track) }) { Text("YouTube에서 열기") }
                    IconButton(onClick = { WidgetPlayer.dismissBlocked() }) { Icon(Icons.Filled.Close, "닫기") }
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(onClick = { pickFiles.launch(arrayOf("audio/*")) }, label = { Text("파일 추가") })
                AssistChip(onClick = { pickFolder.launch(null) }, label = { Text("폴더 추가") })
                AssistChip(onClick = { youtubeDialog = true }, label = { Text("YouTube·Spotify 링크") })
                AssistChip(onClick = {
                    if (busy) return@AssistChip
                    busy = true
                    message = "재생 중인 목록을 가져오는 중…"
                    scope.launch {
                        message = importNowPlaying(ctx)?.let { r ->
                            mark = r.second?.let { NOW_PLAYING_LIST_ID to it }
                            r.first
                        }
                            ?: "가져올 재생 목록이 없습니다. YouTube Music(또는 YouTube)에서 재생을 시작한 뒤 다시 눌러 주세요. (알림 접근 권한 필요)"
                        busy = false
                    }
                }, label = { Text("재생 중 목록") })
                AssistChip(onClick = { libraryDialog = true }, label = { Text("YouTube 재생목록") })
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            message?.let {
                Text(it, Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                LaunchedEffect(it) { delay(5000); message = null }
            }
            YouTubePlayerPicker(data.youTubePlayer) { pkg -> Repository.get(ctx).update { it.copy(youTubePlayer = pkg) } }
            insertAfter?.let { at ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${at + 1}번 곡 다음에 추가합니다", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { setInsertAfter(null) }) { Text("맨 뒤에 추가") }
                }
            }
            HorizontalDivider(Modifier.padding(top = 4.dp))

            val tracks = playlist?.tracks.orEmpty()
            if (tracks.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("곡이 없습니다. 파일, 폴더 또는 YouTube·Spotify 링크를 추가하세요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val playingIndex = current?.takeIf { it.playlistId == playlist?.id }?.let { ref ->
                    if (tracks.getOrNull(ref.index)?.source == ref.source) ref.index
                    else tracks.indexOfFirst { it.source == ref.source }
                } ?: -1
                val listState = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    itemsIndexed(tracks, key = { i, t -> "$i|${t.source}" }) { i, track ->
                        TrackRow(
                            index = i,
                            track = track,
                            playing = i == playingIndex,
                            isFirst = i == 0,
                            isLast = i == tracks.lastIndex,
                            onPlay = { playlist?.let { WidgetPlayer.play(ctx, it.id, i) } },
                            marked = insertAfter == i,
                            onMark = { setInsertAfter(if (insertAfter == i) null else i) },
                            onMove = { delta ->
                                playlist?.let { pl ->
                                    val j = i + delta
                                    updatePlaylist(ctx, pl.id) { p ->
                                        val list = p.tracks.toMutableList()
                                        if (i in list.indices && j in list.indices) { val t = list.removeAt(i); list.add(j, t) }
                                        p.copy(tracks = list)
                                    }
                                    // The insertion marker follows its song.
                                    if (j in tracks.indices) when (insertAfter) {
                                        i -> setInsertAfter(j)
                                        j -> setInsertAfter(i)
                                    }
                                }
                            },
                            onDelete = {
                                playlist?.let { pl ->
                                    updatePlaylist(ctx, pl.id) { p -> p.copy(tracks = p.tracks.filterIndexed { k, _ -> k != i }) }
                                    // Keep the marker on the same song (or the one before a deleted marked song).
                                    insertAfter?.let { at ->
                                        if (at > i || (at == i && at > 0)) setInsertAfter(at - 1)
                                        else if (at == i) setInsertAfter(null)
                                    }
                                }
                            },
                            onRelink = { relinkIndex = i; pickRelink.launch(arrayOf("audio/*")) },
                        )
                    }
                }
            }
        }
    }

    nameDialog?.let { (id, initial) ->
        NameDialog(
            title = if (id == null) "새 플레이리스트" else "이름 바꾸기",
            initial = initial,
            onDismiss = { nameDialog = null },
            onConfirm = { name ->
                nameDialog = null
                if (id == null) {
                    val created = MusicPlaylist(name = name)
                    updateMusic(ctx) { it.copy(playlists = it.playlists + created, selectedPlaylistId = created.id) }
                } else updatePlaylist(ctx, id) { it.copy(name = name) }
            },
        )
    }

    if (confirmDelete && playlist != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("플레이리스트 삭제") },
            text = { Text("‘${playlist.name}’과(와) 안의 ${playlist.tracks.size}곡을 목록에서 삭제할까요? 음악 파일은 지워지지 않습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (current?.playlistId == playlist.id) WidgetPlayer.stop(ctx)
                    updateMusic(ctx) { m ->
                        val rest = m.playlists.filter { it.id != playlist.id }
                        m.copy(playlists = rest, selectedPlaylistId = rest.firstOrNull()?.id ?: m.selectedPlaylistId)
                    }
                }) { Text("삭제") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }

    if (libraryDialog) {
        YouTubePlaylistDialog(
            insertLabel = if (insertAfter != null) "${insertAfter!! + 1}번 곡 다음에" else "현재 목록 끝에",
            onDismiss = { libraryDialog = false },
            onImport = { tracks, name, asNew ->
                libraryDialog = false
                if (asNew) {
                    val created = MusicPlaylist(name = name, tracks = tracks)
                    updateMusic(ctx) { it.copy(playlists = it.playlists + created, selectedPlaylistId = created.id) }
                    message = "‘$name’ 재생목록(${tracks.size}곡)을 만들었습니다."
                } else {
                    addTracks(tracks)
                    message = "‘$name’에서 ${tracks.size}곡을 추가했습니다."
                }
            },
        )
    }

    if (youtubeDialog) {
        YouTubeDialog(
            onDismiss = { youtubeDialog = false },
            onAdded = { tracks, note ->
                youtubeDialog = false
                addTracks(tracks)
                message = note
            },
        )
    }
}
