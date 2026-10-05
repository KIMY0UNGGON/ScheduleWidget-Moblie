package com.schedulewidget.mobile.pet

import androidx.compose.ui.text.style.TextOverflow
import com.schedulewidget.mobile.data.Repository
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.schedulewidget.mobile.record.RecordRed
import com.schedulewidget.mobile.record.Recorder
import com.schedulewidget.mobile.record.RecordingBorderGray
import com.schedulewidget.mobile.record.RecordingDot
import com.schedulewidget.mobile.record.RecordingState
import com.schedulewidget.mobile.record.formatDuration
import com.schedulewidget.mobile.record.rememberRecordingElapsed
import com.schedulewidget.mobile.apps.Mp3Palette
import com.schedulewidget.mobile.apps.Mp3Style
import com.schedulewidget.mobile.apps.pressable
import com.schedulewidget.mobile.apps.rememberMp3Palette

/**
 * The speech bubble a single tap on the pet opens: the apps pinned in pet settings (up to three, [PetApps]) followed by
 * 음악 (MP3 tool), 달력, 설정, and 노트 when enabled. It widens with each shortcut up to five icons a row, then wraps. With
 * [calendarShown] (in-app pet only) it also carries the "달력 보이기" switch. Styled like the MP3 tool: white card,
 * hairline, one blue accent.
 *
 * With 수업 녹음 on (AppData.stt.enabled) a second page is a swipe away ([PetRecordPage]), with dots under the pages;
 * while recording the bubble gets a gray 2dp border and page 1 a compact "● 12:34 정지" row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PetMenu(
    onApp: (String) -> Unit,
    onMusic: () -> Unit,
    onCalendar: () -> Unit,
    onSettings: () -> Unit,
    calendarShown: Boolean? = null,
    onCalendarShown: (Boolean) -> Unit = {},
    /** Opens the app's 녹음 tab. */
    onOpenRecordings: () -> Unit = {},
    /** Opens the transcript of a recording in the app's 녹음 tab. */
    onOpenTranscript: (String) -> Unit = {},
    /** Opens the notebook library, when notes are enabled. */
    onNotes: (() -> Unit)? = null,
) {
    val p = rememberMp3Palette()
    val shape = RoundedCornerShape(22.dp)
    val tail = LocalBubbleTail.current
    val context = LocalContext.current
    val data by remember { Repository.get(context) }.data.collectAsState()
    val rec by Recorder.state.collectAsState()
    val recordOn = data.stt.enabled
    val recording = recordOn && rec.isRecording
    val edge = if (recording) BubbleEdge(RecordingBorderGray, 2.dp) else BubbleEdge(p.hairline, 1.dp)
    val apps = remember(data.petApps) { PetApps.installed(context, data.petApps) }
    val notesButton = onNotes?.takeIf { data.notes.enabled }?.let { openNotes ->
        @Composable { MenuButton(Icons.Outlined.EditNote, "노트", p, openNotes) }
    }
    val buttons: List<@Composable () -> Unit> = apps.map { pkg ->
        @Composable { AppButton(pkg, p) { onApp(pkg) } }
    } + listOf(
        @Composable { MenuButton(Icons.Outlined.MusicNote, "음악", p, onMusic) },
        @Composable { MenuButton(Icons.Outlined.CalendarMonth, "달력", p, onCalendar) },
        @Composable { MenuButton(Icons.Outlined.Settings, "설정", p, onSettings) },
    ) + listOfNotNull(notesButton)

    val firstPage = @Composable {
        Column(Modifier.fillMaxWidth()) {
            if (recording) RecordingRow(p, rec) { Recorder.stop(context) }
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                buttons.chunked(PetApps.PER_ROW).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        row.forEach { button -> Box(Modifier.width(68.dp), contentAlignment = Alignment.TopCenter) { button() } }
                    }
                }
            }
            if (calendarShown != null) {
                HorizontalDivider(Modifier.padding(horizontal = 18.dp), thickness = 1.dp, color = p.hairline)
                Row(
                    Modifier.fillMaxWidth().pressable { onCalendarShown(!calendarShown) }
                        .padding(start = 18.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("달력 보이기", style = Mp3Style.body, color = p.ink, modifier = Modifier.weight(1f))
                    Switch(
                        checked = calendarShown, onCheckedChange = onCalendarShown,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White, checkedTrackColor = p.accent, checkedBorderColor = Color.Transparent,
                            uncheckedThumbColor = Color.White, uncheckedTrackColor = p.track, uncheckedBorderColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }

    Column(Modifier.width(petMenuWidthDp(buttons.size).dp).padding(horizontal = 8.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (tail?.up == true) TailRow(p, tail, up = true, edge)
        Column(
            Modifier.fillMaxWidth()
                // A whisper of lift so the bubble reads over any wallpaper; no heavy frame.
                .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.16f))
                .clip(shape).background(p.panel).border(edge.width, edge.color, shape),
        ) {
            if (!recordOn) firstPage()
            else {
                val pager = rememberPagerState(pageCount = { 2 })
                // Pages differ in height; the bubble eases between them instead of jumping.
                HorizontalPager(
                    pager, Modifier.fillMaxWidth().animateContentSize(),
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    if (page == 0) firstPage()
                    else PetRecordPage(p, LocalBubbleRoom.current?.let { (it - BUBBLE_CHROME).coerceAtLeast(160.dp) }, onOpenRecordings, onOpenTranscript)
                }
                PageDots(p, pager.currentPage, 2)
            }
        }
        if (tail?.up != true) TailRow(p, tail, up = false, edge)
    }
}

/** Tail, page dots, outer padding and border around a page. */
private val BUBBLE_CHROME = 56.dp

/** "● 12:34  정지" at the top of page 1 while recording. */
@Composable
private fun RecordingRow(p: Mp3Palette, rec: RecordingState, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecordingDot(size = 8.dp)
        Spacer(Modifier.width(8.dp))
        Text("녹음 중 ", style = Mp3Style.caption, color = p.secondary)
        Text(formatDuration(rememberRecordingElapsed(rec)), style = Mp3Style.caption, color = p.ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(
            "정지", style = Mp3Style.caption, color = Color.White, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(RecordRed).pressable(onClick = onStop)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PageDots(p: Mp3Palette, current: Int, count: Int) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Box(
                Modifier.padding(horizontal = 3.dp).size(6.dp).clip(CircleShape)
                    .background(if (i == current) p.secondary else p.track),
            )
        }
    }
}


/** A pinned app: its own launcher icon and name. */
@Composable
private fun AppButton(pkg: String, p: Mp3Palette, onClick: () -> Unit) {
    val context = LocalContext.current
    val label = remember(pkg) { PetApps.label(context, pkg) }
    Column(
        Modifier.pressable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(pkg, 44)
        Spacer(Modifier.height(6.dp))
        Text(label, style = Mp3Style.fine, color = p.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MenuButton(icon: ImageVector, label: String, p: Mp3Palette, onClick: () -> Unit) {
    Column(
        Modifier.pressable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(p.chip), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = p.accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = Mp3Style.fine, color = p.secondary)
    }
}
