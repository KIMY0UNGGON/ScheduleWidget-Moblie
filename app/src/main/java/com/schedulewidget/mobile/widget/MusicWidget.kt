package com.schedulewidget.mobile.widget

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
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
import com.schedulewidget.mobile.apps.MusicHub
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.music.NowPlaying
import com.schedulewidget.mobile.ui.Route

class MusicWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(180.dp, 40.dp), DpSize(250.dp, 100.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = Repository.get(context)
        // Starting values are read here, outside composition; the flows below keep them current.
        val initialPlaying = MusicHub.state.value
        val initialTheme = repo.data.value.miniTheme
        provideContent {
            // Position/volume ticks (several per second while playing) must not rebuild the RemoteViews each time.
            val shown = remember {
                MusicHub.state.map { it.copy(positionMs = 0, durationMs = 0, updatedAt = 0, canSeek = false, volume = 0f) }
                    .distinctUntilChanged()
            }
            val theme = remember { repo.data.map { it.miniTheme }.distinctUntilChanged() }
            val playing by shown.collectAsState(initialPlaying)
            val miniTheme by theme.collectAsState(initialTheme)
            MusicContent(playing, WidgetPalette.of(miniTheme))
        }
    }
}

class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        MusicHub.playPause(context)
}

class NextTrackAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        MusicHub.next(context)
}

class PreviousTrackAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        MusicHub.previous(context)
}

@Composable
private fun MusicContent(np: NowPlaying, palette: WidgetPalette) {
    val context = LocalContext.current
    val tall = LocalSize.current.height >= 100.dp
    val openPlaylists = actionStartActivity(routeIntent(context, Route.Playlists))
    val title = np.title.ifBlank { "재생 중인 곡 없음" }
    val titleLine = if (np.artist.isNotBlank() && !tall) "$title · ${np.artist}" else title

    @Composable
    fun Info(modifier: GlanceModifier) = Column(modifier.clickable(openPlaylists)) {
        Text(np.sourceLabel, style = TextStyle(color = palette.subText.provider(), fontSize = 10.sp), maxLines = 1)
        Text(
            titleLine,
            style = TextStyle(color = palette.text.provider(), fontSize = if (tall) 15.sp else 13.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        if (tall && np.artist.isNotBlank()) {
            Text(np.artist, style = TextStyle(color = palette.subText.provider(), fontSize = 12.sp), maxLines = 1)
        }
    }

    @Composable
    fun Controls() = Row(verticalAlignment = Alignment.CenterVertically) {
        ControlButton("⏮", palette, false, actionRunCallback<PreviousTrackAction>())
        Spacer(GlanceModifier.width(4.dp))
        ControlButton(if (np.isPlaying) "⏸" else "▶", palette, true, actionRunCallback<PlayPauseAction>())
        Spacer(GlanceModifier.width(4.dp))
        ControlButton("⏭", palette, false, actionRunCallback<NextTrackAction>())
    }

    Box(
        GlanceModifier.fillMaxSize().background(palette.background).cornerRadius(18.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (tall) {
            Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Info(GlanceModifier.fillMaxWidth())
                Spacer(GlanceModifier.height(8.dp))
                Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { Controls() }
            }
        } else {
            Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Info(GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(6.dp))
                Controls()
            }
        }
    }
}

@Composable
private fun ControlButton(label: String, palette: WidgetPalette, primary: Boolean, action: androidx.glance.action.Action) {
    Box(
        GlanceModifier.size(if (primary) 38.dp else 32.dp).cornerRadius(19.dp)
            .background(if (primary) palette.accent else palette.surface).clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(
                color = (if (primary) palette.onAccent else palette.accent).provider(),
                fontSize = if (primary) 16.sp else 14.sp,
            ),
        )
    }
}
