package com.schedulewidget.mobile.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.apps.MusicHub
import com.schedulewidget.mobile.data.AppData
import com.schedulewidget.mobile.data.MusicPlaylist
import com.schedulewidget.mobile.data.MusicTrack
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.data.Spotify
import com.schedulewidget.mobile.data.YouTube
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal val AUDIO_EXTENSIONS = setOf("mp3", "wav", "wma", "m4a", "aac", "flac", "ogg", "aif", "aiff")
internal fun persist(ctx: Context, uri: Uri) {
    runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}

private fun stripExtension(name: String) = name.substringBeforeLast('.', name).ifBlank { name }

internal fun displayTitle(ctx: Context, uri: Uri): String {
    val name = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
    return stripExtension(name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "제목 없음")
}

/** Recursively collects audio files under a SAF tree, sorted by relative path (like the desktop folder import). */
internal fun collectFolder(ctx: Context, tree: Uri): List<MusicTrack> {
    val found = mutableListOf<Pair<String, MusicTrack>>()
    val cols = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
    fun walk(docId: String, path: String, depth: Int) {
        if (depth > 32 || found.size >= 5000) return // picking a whole storage root must not hang / bloat the list
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        runCatching {
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext() && found.size < 5000) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(id, "$path$name/", depth + 1)
                    else if (name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        found += "$path$name" to MusicTrack(stripExtension(name), uri.toString())
                    }
                }
            }
        }
    }
    walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
    return found.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first }).map { it.second }
}
/** Where YouTube songs play: the in-app player, or the official app with the user's own account (Premium). */
@Composable
internal fun YouTubePlayerPicker(selected: String, onSelect: (String) -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val installed = remember {
        listOf(AppData.YOUTUBE_MUSIC_APP, AppData.YOUTUBE_APP).filter { ctx.packageManager.getLaunchIntentForPackage(it) != null }
    }
    val options = listOf(AppData.YOUTUBE_EMBED to "앱 안에서 (광고 있을 수 있음)") +
        installed.map { it to (if (it == AppData.YOUTUBE_MUSIC_APP) "YouTube Music 앱 (Premium 적용)" else "YouTube 앱 (Premium 적용)") }
    val current = options.firstOrNull { it.first == selected } ?: options.first()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("YouTube 곡 재생", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) { Text(current.second, style = MaterialTheme.typography.bodySmall) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onSelect(id) })
                }
            }
        }
    }
}
/** Spotify's queue carries spotify:track: ids, so its songs are copied as-is and play in the Spotify app. */
private fun importSpotifyQueue(ctx: Context, queue: List<MusicHub.QueueTrack>, current: Int): Pair<String, Int?> {
    val picked = queue.mapIndexedNotNull { i, t -> t.spotifyUri?.let { Spotify.parse(it) }?.let { i to (t to it) } }
    if (picked.isEmpty()) return "Spotify 재생 목록 ${queue.size}곡을 읽었지만 곡 주소가 없어 가져오지 못했습니다." to null
    val tracks = picked.map { (_, p) ->
        val (t, ref) = p
        MusicTrack(listOf(t.title, t.artist).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { ref.id }, ref.url)
    }
    Repository.get(ctx).update { d ->
        val list = MusicPlaylist(id = NOW_PLAYING_LIST_ID, name = "지금 재생 목록 · Spotify", tracks = tracks)
        val others = d.music.playlists.filter { it.id != NOW_PLAYING_LIST_ID }
        d.copy(music = d.music.copy(playlists = listOf(list) + others, selectedPlaylistId = list.id))
    }
    val skipped = queue.size - tracks.size
    val note = "Spotify 재생 목록 ${tracks.size}곡을 ‘지금 재생 목록’으로 가져왔습니다" +
        (if (skipped > 0) " (${skipped}곡은 주소가 없어 제외)" else "") +
        ". 곡을 길게 눌러 그 사이에 파일이나 링크를 넣을 수 있어요."
    val playingAt = if (current < 0) -1 else picked.count { it.first <= current } - 1
    return note to playingAt.takeIf { it >= 0 }
}

/** Fixed id of the one-off playlist that "재생 중 목록" (re)creates each time. */
internal const val NOW_PLAYING_LIST_ID = "now-playing-import"

/**
 * Copies the queue YouTube Music / YouTube is playing right now into a one-off widget playlist "지금 재생 목록",
 * replacing the previous copy. Songs play through the official app (Premium applies), and files or links can be
 * inserted anywhere in between. Returns (message, index of the song playing now) or null when no queue is available.
 */
internal suspend fun importNowPlaying(ctx: Context): Pair<String, Int?>? {
    MusicHub.refresh(ctx)
    val apps = listOf(AppData.YOUTUBE_MUSIC_APP, AppData.YOUTUBE_APP, AppData.SPOTIFY_APP)
    // The app playing right now wins; otherwise the first one that has a queue.
    val queues = apps.mapNotNull { pkg -> MusicHub.queue(pkg)?.let { pkg to it } }
    val (pkg, found) = queues.firstOrNull { MusicHub.sessions.value[it.first]?.isPlaying == true } ?: queues.firstOrNull() ?: return null
    val (queue, current) = found
    if (pkg == AppData.SPOTIFY_APP) return importSpotifyQueue(ctx, queue, current)
    // YouTube Music publishes only titles/artists in its queue: find each song's video by searching YouTube (a few at a time).
    val ids = coroutineScope {
        val gate = Semaphore(4)
        queue.map { t ->
            async {
                t.videoId ?: gate.withPermit {
                    YouTubeFetch.searchVideoId(listOf(t.title, t.artist).filter { it.isNotBlank() }.joinToString(" "))
                }
            }
        }.awaitAll()
    }
    val tracks = queue.zip(ids).filter { it.second != null }.map { (t, id) ->
        MusicTrack(listOf(t.title, t.artist).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { id!! },
            "https://www.youtube.com/watch?v=$id")
    }
    if (tracks.isEmpty()) return "재생 목록 ${queue.size}곡을 읽었지만 YouTube에서 곡을 찾지 못했습니다. 인터넷 연결을 확인해 주세요." to null
    val appName = if (pkg == AppData.YOUTUBE_MUSIC_APP) "YouTube Music" else "YouTube"
    Repository.get(ctx).update { d ->
        val list = MusicPlaylist(id = NOW_PLAYING_LIST_ID, name = "지금 재생 목록 · $appName", tracks = tracks)
        val others = d.music.playlists.filter { it.id != NOW_PLAYING_LIST_ID }
        d.copy(
            music = d.music.copy(playlists = listOf(list) + others, selectedPlaylistId = list.id),
            // Play these through the official app so the account's Premium (no ads) applies.
            youTubePlayer = if (d.youTubePlayer == AppData.YOUTUBE_EMBED) pkg else d.youTubePlayer,
        )
    }
    val skipped = queue.size - tracks.size
    val note = "$appName 재생 목록 ${tracks.size}곡을 ‘지금 재생 목록’으로 가져왔습니다" +
        (if (skipped > 0) " (${skipped}곡은 YouTube에서 찾지 못해 제외)" else "") +
        ". 곡을 길게 눌러 그 사이에 파일이나 링크를 넣을 수 있어요."
    // [current] indexes the app's queue; songs not found on YouTube were dropped, so map it into [tracks].
    val playingAt = if (current < 0) -1 else ids.take(current + 1).count { it != null } - 1
    return note to playingAt.takeIf { it >= 0 }
}
