package com.schedulewidget.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.apps.pressable
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.record.Recorder
import com.schedulewidget.mobile.record.RecordingDot
import com.schedulewidget.mobile.record.RecordingsScreen

/**
 * The app's home: the calendar board, plus — with 수업 녹음 on — a slim "달력 | 녹음" tab bar at the bottom whose 녹음 tab
 * is the recordings list ([Route.Recordings], so the pet bubble and the notification can deep-link to it). With the
 * feature off there is no tab bar at all, just the board. With 노트 on there is also a 노트 tab ([Route.Notes], the
 * notebooks library; [openNote] opens a notebook in the full-screen editor). The bar is hidden when only 달력 is left.
 */
@Composable
fun HomeTabs(route: Route, navigate: (Route) -> Unit, openNote: (String) -> Unit = {}) {
    val context = LocalContext.current
    val data by Repository.get(context).data.collectAsStateWithLifecycle()
    val showRecordings = data.stt.enabled
    val showNotes = data.notes.enabled // notes
    val onNotes: (() -> Unit)? = if (showNotes) ({ navigate(Route.Notes) }) else null
    if (!showRecordings && !showNotes) {
        MiniScreen(navigate)
        return
    }
    val theme = MiniThemes.of(data.miniTheme)
    val rec by Recorder.state.collectAsStateWithLifecycle()
    val recordings = showRecordings && route == Route.Recordings
    val notes = showNotes && route == Route.Notes // notes
    Column(Modifier.fillMaxSize().background(theme.canvas)) {
        // The tab bar takes the navigation-bar inset; the screens above must not pad for it again.
        Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.navigationBars)) {
            when {
                recordings -> RecordingsScreen()
                notes -> com.schedulewidget.mobile.notes.NotesLibraryScreen(onOpen = openNote) // notes
                else -> MiniScreen(navigate, onNotes)
            }
        }
        HorizontalDivider(thickness = 1.dp, color = theme.hairline)
        Row(Modifier.fillMaxWidth().background(theme.surface).navigationBarsPadding().height(52.dp)) {
            Tab(Icons.Outlined.CalendarMonth, "달력", !recordings && !notes, theme) { navigate(Route.Mini) }
            if (showRecordings) Tab(Icons.Outlined.Mic, "녹음", recordings, theme, badge = rec.isRecording) { navigate(Route.Recordings) }
            // notes: the 노트 tab.
            if (showNotes) Tab(Icons.Outlined.EditNote, "노트", notes, theme) { navigate(Route.Notes) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tab(
    icon: ImageVector, label: String, selected: Boolean, theme: MiniTheme, badge: Boolean = false, onClick: () -> Unit,
) {
    val inkSelected = theme.id == "paper" || theme.id == "modern"
    val color = if (selected) (if (inkSelected) theme.auxInk else theme.accent) else theme.auxMuted
    Column(
        Modifier.weight(1f).fillMaxHeight().pressable(role = Role.Tab, onClick = onClick).semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Box {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            if (badge) RecordingDot(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp), size = 8.dp)
        }
        Spacer(Modifier.height(2.dp))
        Text(label, color = color, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}
