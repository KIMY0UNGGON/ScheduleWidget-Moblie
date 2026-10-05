package com.schedulewidget.mobile.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.music.WidgetPlayer
import com.schedulewidget.mobile.ui.Route

/** Home-screen playlist: the selected widget playlist's songs, tap one to play; ‹ › switch playlists. */
class PlaylistWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = Repository.get(context)
        // Starting values are read here, outside composition; the flows below keep them current.
        val initialData = repo.data.value.let { it.music.copy(volume = 0.0) to it.miniTheme }
        val initialPlaying = WidgetPlayer.state.value.isPlaying
        provideContent {
            // Only playlists + theme, and only the playing flag: position ticks and volume must not redraw the list.
            val shownData = remember {
                repo.data.map { it.music.copy(volume = 0.0) to it.miniTheme }.distinctUntilChanged()
            }
            val playingFlow = remember { WidgetPlayer.state.map { it.isPlaying }.distinctUntilChanged() }
            val data by shownData.collectAsState(initialData)
            val current by WidgetPlayer.current.collectAsState()
            val isPlaying by playingFlow.collectAsState(initialPlaying)
            PlaylistContent(data.first, current, isPlaying, WidgetPalette.of(data.second))
        }
    }
}

class PlaylistWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = PlaylistWidget()
}

private const val MAX_ROWS = 100

private val KeyPlaylist =ActionParameters.Key<String>("playlist")
private val KeyIndex = ActionParameters.Key<Int>("index")
private val KeyStep = ActionParameters.Key<Int>("step")

class PlayTrackAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val playlist = parameters[KeyPlaylist] ?: return
        WidgetPlayer.play(context, playlist, parameters[KeyIndex] ?: 0)
    }
}

class WidgetPlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        WidgetPlayer.playPause(context)
}

class SwitchPlaylistAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val step = parameters[KeyStep] ?: 1
        Repository.get(context).update { d ->
            val lists = d.music.playlists
            if (lists.isEmpty()) return@update d
            val at = lists.indexOfFirst { it.id == d.music.selectedPlaylist?.id }.coerceAtLeast(0)
            d.copy(music = d.music.copy(selectedPlaylistId = lists[Math.floorMod(at + step, lists.size)].id))
        }
    }
}

@Composable
private fun PlaylistContent(
    music: com.schedulewidget.mobile.data.MusicSettings,
    current: com.schedulewidget.mobile.music.PlayingRef?,
    isPlaying: Boolean,
    palette: WidgetPalette,
) {
    val context = LocalContext.current
    val playlist = music.selectedPlaylist
    val openPlaylists = actionStartActivity(routeIntent(context, Route.Playlists))

    Column(
        GlanceModifier.fillMaxSize().background(palette.background).cornerRadius(18.dp).padding(10.dp),
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SmallButton("‹", palette, actionRunCallback<SwitchPlaylistAction>(actionParametersOf(KeyStep to -1)))
            Text(
                playlist?.let { "${it.name} · ${it.tracks.size}곡" } ?: "플레이리스트 없음",
                modifier = GlanceModifier.defaultWeight().padding(horizontal = 6.dp).clickable(openPlaylists),
                style = TextStyle(color = palette.text.provider(), fontSize = 14.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            SmallButton("›", palette, actionRunCallback<SwitchPlaylistAction>(actionParametersOf(KeyStep to 1)))
            Spacer(GlanceModifier.width(6.dp))
            Box(
                GlanceModifier.size(34.dp).cornerRadius(17.dp).background(palette.accent)
                    .clickable(actionRunCallback<WidgetPlayPauseAction>()),
                contentAlignment = Alignment.Center,
            ) { Text(if (isPlaying) "❚❚" else "▶", style = TextStyle(color = palette.onAccent.provider(), fontSize = 15.sp)) }
        }
        Spacer(GlanceModifier.height(6.dp))
        if (playlist == null || playlist.tracks.isEmpty()) {
            Box(GlanceModifier.fillMaxSize().clickable(openPlaylists), contentAlignment = Alignment.Center) {
                Text("눌러서 곡을 추가하세요", style = TextStyle(color = palette.subText.provider(), fontSize = 13.sp))
            }
            return@Column
        }
        // Every row travels in one RemoteViews binder transaction (~1 MB limit): a playlist of thousands of songs would
        // fail to render at all. Show a window of MAX_ROWS around the playing song; the rest is in the app.
        val playingIndex = current?.takeIf { it.playlistId == playlist.id }?.index ?: 0
        val from = if (playlist.tracks.size <= MAX_ROWS) 0
        else (playingIndex - MAX_ROWS / 4).coerceIn(0, playlist.tracks.size - MAX_ROWS)
        val rows = playlist.tracks.subList(from, minOf(playlist.tracks.size, from + MAX_ROWS))
        LazyColumn(GlanceModifier.fillMaxSize()) {
            itemsIndexed(rows, itemId = { i, _ -> (from + i).toLong() }) { i, track ->
                val index = from + i
                val now = current?.playlistId == playlist.id && current.index == index
                Row(
                    GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)
                        .clickable(actionRunCallback<PlayTrackAction>(actionParametersOf(KeyPlaylist to playlist.id, KeyIndex to index))),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        GlanceModifier.fillMaxWidth().cornerRadius(8.dp)
                            .background(if (now) palette.todayBg else palette.surface)
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (now) (if (isPlaying) "♪" else "❚❚") else "${index + 1}",
                                modifier = GlanceModifier.width(26.dp),
                                style = TextStyle(color = palette.accent.provider(), fontSize = 12.sp, fontWeight = FontWeight.Bold),
                            )
                            Text(
                                track.title.ifBlank { track.source },
                                modifier = GlanceModifier.defaultWeight(),
                                style = TextStyle(
                                    color = (if (track.isDesktopPath) palette.subText else palette.text).provider(),
                                    fontSize = 13.sp,
                                    fontWeight = if (now) FontWeight.Bold else FontWeight.Normal,
                                ),
                                maxLines = 1,
                            )
                            Text(
                                if (track.isYouTube) "YT" else if (track.isSpotify) "SP" else if (track.isDesktopPath) "재연결" else "파일",
                                style = TextStyle(color = palette.subText.provider(), fontSize = 10.sp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallButton(label: String, palette: WidgetPalette, action: androidx.glance.action.Action) {
    Box(
        GlanceModifier.size(28.dp).cornerRadius(14.dp).background(palette.surface).clickable(action),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = TextStyle(color = palette.accent.provider(), fontSize = 16.sp, fontWeight = FontWeight.Bold)) }
}
