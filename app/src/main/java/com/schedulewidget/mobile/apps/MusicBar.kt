package com.schedulewidget.mobile.apps

import android.annotation.SuppressLint
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.AppData
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.music.AudioRoute
import com.schedulewidget.mobile.music.NowPlaying
import com.schedulewidget.mobile.music.WidgetPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The MP3 module under the mini calendar: a parchment (or dark tile) card with title, source, seek and controls. */
@Composable
fun MusicBar(modifier: Modifier = Modifier, onOpenPlaylists: () -> Unit, onOpenMusicApps: () -> Unit) {
    val p = rememberMp3Palette()
    val shape = RoundedCornerShape(18.dp)
    MusicPlayerContent(
        palette = p,
        onOpenPlaylists = onOpenPlaylists,
        onOpenMusicApps = onOpenMusicApps,
        modifier = modifier.fillMaxWidth().clip(shape).background(p.card).border(1.dp, p.hairline, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** The player body without its own card, shared by [MusicBar] and the pet's MP3 panel. */
@Composable
@SuppressLint("StateFlowValueCalledInComposition") // only the starting value; the flow keeps it current
internal fun MusicPlayerContent(
    palette: Mp3Palette,
    onOpenPlaylists: () -> Unit,
    onOpenMusicApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = palette
    val ctx = LocalContext.current
    val np by MusicHub.state.collectAsStateWithLifecycle()
    // Only the play-mode flags are needed here; collecting the whole AppData recomposed on every edit.
    val repo = remember { Repository.get(ctx) }
    val mode by remember(repo) {
        repo.data.map { modeLabel(it.music.shuffle, it.music.repeatOne) }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(repo.data.value.music.let { modeLabel(it.shuffle, it.repeatOne) })
    val isWidget = np.sourceId == AppData.SOURCE_WIDGET

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(np.isPlaying) {
        while (np.isPlaying) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val position = livePosition(np, now)

    Column(modifier) {
        // Row 1: title + source / mode pills
        Text(
            displayTitle(np, isWidget),
            modifier = Modifier.fillMaxWidth()
                .then(if (np.isPlaying) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
            style = Mp3Style.title,
            color = if (np.title.isNotBlank()) p.ink else p.muted,
            maxLines = 1,
            overflow = if (np.isPlaying) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SourcePicker(np, p, onOpenMusicApps)
            if (isWidget) {
                Pill(p) { WidgetPlayer.cycleMode(ctx) }.let { mod ->
                    Box(mod, contentAlignment = Alignment.Center) {
                        Text(mode, style = Mp3Style.caption, fontWeight = FontWeight.SemiBold, color = p.accent)
                    }
                }
            }
        }

        // Row 2: progress + times
        Spacer(Modifier.height(10.dp))
        SeekBar(
            positionMs = position,
            durationMs = np.durationMs,
            enabled = np.canSeek && np.durationMs > 0,
            onSeek = { MusicHub.seekTo(ctx, it) },
            palette = p,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(fmt(position), style = Mp3Style.fine, color = p.muted)
            Spacer(Modifier.weight(1f))
            Text(if (np.durationMs > 0) fmt(np.durationMs) else "--:--", style = Mp3Style.fine, color = p.muted)
        }

        // Row 3: controls
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PlainIcon(Icons.AutoMirrored.Filled.QueueMusic, "플레이리스트", p.ink, onOpenPlaylists)
            Spacer(Modifier.weight(1f))
            PlainIcon(Icons.Filled.SkipPrevious, "이전", p.ink) { MusicHub.previous(ctx) }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(44.dp).pressable { MusicHub.playPause(ctx) }.clip(CircleShape).background(p.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (np.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (np.isPlaying) "일시정지" else "재생",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            PlainIcon(Icons.Filled.SkipNext, "다음", p.ink) { MusicHub.next(ctx) }
            Spacer(Modifier.weight(1f))
            val route by WidgetPlayer.route.collectAsStateWithLifecycle()
            VolumeButton(np.volume, p, live = !isWidget, boostRoute = if (isWidget) route else AudioRoute.OtherApp) {
                MusicHub.setVolume(ctx, it)
            }
        }
    }
}

/**
 * The one-glance MP3 tool: title, source, previous / play / next and a thin progress line in a single row.
 * The pet's MP3 panel opens like this; [onExpand] switches to the full player with the song list.
 */
@Composable
internal fun MusicMiniContent(
    palette: Mp3Palette,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = palette
    val ctx = LocalContext.current
    val np by MusicHub.state.collectAsStateWithLifecycle()
    val isWidget = np.sourceId == AppData.SOURCE_WIDGET

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(np.isPlaying) {
        while (np.isPlaying) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val position = livePosition(np, now)
    val fraction = if (np.durationMs > 0) (position.toFloat() / np.durationMs).coerceIn(0f, 1f) else 0f

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tapping the text also opens the full player.
            Column(Modifier.weight(1f).pressable(onClick = onExpand)) {
                Text(
                    displayTitle(np, isWidget),
                    modifier = Modifier.fillMaxWidth()
                        .then(if (np.isPlaying) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier),
                    style = Mp3Style.body,
                    fontWeight = FontWeight.SemiBold,
                    color = if (np.title.isNotBlank()) p.ink else p.muted,
                    maxLines = 1,
                    overflow = if (np.isPlaying) TextOverflow.Clip else TextOverflow.Ellipsis,
                )
                Text(np.sourceLabel, style = Mp3Style.fine, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MiniIcon(Icons.Filled.SkipPrevious, "이전", p.ink) { MusicHub.previous(ctx) }
            Box(
                Modifier.size(38.dp).pressable { MusicHub.playPause(ctx) }.clip(CircleShape).background(p.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (np.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (np.isPlaying) "일시정지" else "재생",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            MiniIcon(Icons.Filled.SkipNext, "다음", p.ink) { MusicHub.next(ctx) }
            MiniIcon(Icons.Filled.KeyboardArrowUp, "펼치기", p.secondary, onExpand)
            MiniIcon(Icons.Filled.Close, "닫기", p.secondary, onClose)
        }
        Canvas(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp).height(3.dp)) {
            val r = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(p.track, Offset.Zero, size, r)
            drawRoundRect(p.fill, Offset.Zero, Size(size.width * fraction, size.height), r)
        }
    }
}

@Composable
private fun MiniIcon(icon: ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(22.dp))
    }
}

private fun displayTitle(np: NowPlaying, isWidget: Boolean) = when {
    np.title.isNotBlank() && np.artist.isNotBlank() -> "${np.title} - ${np.artist}"
    np.title.isNotBlank() -> np.title
    isWidget -> "재생할 곡을 선택하세요"
    else -> "${np.sourceLabel} · 대기 중"
}

private fun modeLabel(shuffle: Boolean, repeatOne: Boolean) = when {
    shuffle -> "랜덤"
    repeatOne -> "1곡"
    else -> "순차"
}

private fun livePosition(np: NowPlaying, now: Long): Long {
    val base = np.positionMs
    val p = if (np.isPlaying && np.updatedAt > 0) base + (now - np.updatedAt).coerceAtLeast(0) else base
    return if (np.durationMs > 0) p.coerceIn(0, np.durationMs) else p.coerceAtLeast(0)
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Translucent-gray pill chip modifier (radius 9999) with press scale. */
private fun Pill(p: Mp3Palette, onClick: () -> Unit): Modifier =
    Modifier.pressable(onClick = onClick).height(26.dp).clip(RoundedCornerShape(percent = 50)).background(p.chip)
        .padding(horizontal = 10.dp)


@Composable
private fun SourcePicker(np: NowPlaying, p: Mp3Palette, onOpenMusicApps: () -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Pill(p) { open = true }.widthIn(max = 180.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                np.sourceLabel, style = Mp3Style.caption, color = p.secondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "재생 소스", tint = p.secondary, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = p.panel) {
            MusicHub.sources(ctx).forEach { s ->
                val selected = s.id == np.sourceId
                DropdownMenuItem(
                    text = {
                        Text(
                            s.label, style = Mp3Style.body,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) p.accent else p.ink,
                        )
                    },
                    trailingIcon = if (s.active) ({ Icon(Icons.Filled.GraphicEq, contentDescription = "재생 중", tint = p.accent, modifier = Modifier.size(16.dp)) }) else null,
                    onClick = {
                        open = false
                        MusicHub.setSource(ctx, s.id)
                    },
                )
            }
            HorizontalDivider(color = p.hairline)
            DropdownMenuItem(
                text = { Text("음악 앱 편집…", style = Mp3Style.body, color = p.ink) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null, tint = p.secondary, modifier = Modifier.size(18.dp)) },
                onClick = {
                    open = false
                    onOpenMusicApps()
                },
            )
        }
    }
}
