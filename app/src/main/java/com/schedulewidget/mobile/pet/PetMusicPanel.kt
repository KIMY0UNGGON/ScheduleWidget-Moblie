package com.schedulewidget.mobile.pet

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.filled.KeyboardArrowDown
import com.schedulewidget.mobile.apps.MusicMiniContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.schedulewidget.mobile.apps.Mp3Style
import com.schedulewidget.mobile.apps.MusicPlayerContent
import com.schedulewidget.mobile.apps.pressable
import com.schedulewidget.mobile.apps.rememberMp3Palette
import com.schedulewidget.mobile.data.AppData
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.music.WidgetPlayer

/**
 * The MP3 tool a triple tap on the pet opens. It starts as the one-row mini player; ⌃ expands it to the full player plus
 * the current playlist's songs, and ⌄ folds it back.
 */
@Composable
fun PetMusicPanel(onClose: () -> Unit, onOpenPlaylists: () -> Unit, onOpenMusicApps: () -> Unit) {
    val context = LocalContext.current
    val data by remember { Repository.get(context) }.data.collectAsState()
    val current by WidgetPlayer.current.collectAsState()
    val p = rememberMp3Palette()
    val playlist = data.music.selectedPlaylist
    val shape = RoundedCornerShape(18.dp)
    var expanded by remember { mutableStateOf(false) }

    if (!expanded) {
        MusicMiniContent(
            palette = p,
            onExpand = { expanded = true },
            onClose = onClose,
            modifier = Modifier.padding(6.dp).widthIn(max = 360.dp).clip(shape).background(p.panel)
                .border(1.dp, p.hairline, shape),
        )
        return
    }

    Column(
        Modifier.padding(6.dp).widthIn(max = 360.dp).clip(shape).background(p.panel)
            .border(1.dp, p.hairline, shape),
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("지금 재생", style = Mp3Style.header, color = p.ink, modifier = Modifier.weight(1f))
            Text(
                "목록 편집",
                style = Mp3Style.caption,
                color = p.accent,
                modifier = Modifier.pressable(onClick = onOpenPlaylists).padding(horizontal = 8.dp, vertical = 10.dp),
            )
            Box(
                Modifier.size(44.dp).pressable { expanded = false },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "접기", tint = p.secondary, modifier = Modifier.size(22.dp))
            }
            Box(
                Modifier.size(44.dp).pressable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(p.chip), contentAlignment = Alignment.Center) {
                    Text("✕", color = p.secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        MusicPlayerContent(
            palette = p,
            onOpenPlaylists = onOpenPlaylists,
            onOpenMusicApps = onOpenMusicApps,
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 8.dp),
        )

        // Same rule as MusicHub: a source that is no longer in musicApps falls back to the widget player.
        val widgetSource = data.musicSource == AppData.SOURCE_WIDGET || data.musicSource !in data.musicApps
        if (widgetSource && playlist != null && playlist.tracks.isNotEmpty()) {
            // PlayingRef.index may drift after edits; re-find the track by its source (first match on duplicates).
            val cur = current
            val nowIndex = when {
                cur == null || cur.playlistId != playlist.id -> -1
                playlist.tracks.getOrNull(cur.index)?.source == cur.source -> cur.index
                else -> playlist.tracks.indexOfFirst { it.source == cur.source }
            }
            HorizontalDivider(thickness = 1.dp, color = p.hairline)
            Text(
                playlist.name, style = Mp3Style.fine, color = p.muted,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 2.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(bottom = 8.dp)) {
                // Sources can repeat within a playlist, so the key must include the index.
                itemsIndexed(playlist.tracks, key = { index, track -> "$index|${track.source}" }) { index, track ->
                    val now = index == nowIndex
                    if (index > 0) {
                        HorizontalDivider(Modifier.padding(start = 54.dp, end = 18.dp), thickness = 1.dp, color = p.hairline)
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .pressable { WidgetPlayer.play(context, playlist.id, index) }
                            .heightIn(min = 44.dp)
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
                            if (now) {
                                Icon(Icons.Filled.GraphicEq, contentDescription = "재생 중", tint = p.accent, modifier = Modifier.size(18.dp))
                            } else {
                                Text("${index + 1}", style = Mp3Style.caption, color = p.muted)
                            }
                        }
                        Text(
                            track.title.ifBlank { track.source },
                            style = Mp3Style.body,
                            color = if (now) p.accent else p.ink,
                            fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.size(6.dp))
        }
    }
}
