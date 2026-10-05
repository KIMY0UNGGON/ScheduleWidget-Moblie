package com.schedulewidget.mobile.notes.editor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.notes.NoteStore
import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.notes.ink.StoredNote
import com.schedulewidget.mobile.notes.ui.NotesIconButton
import com.schedulewidget.mobile.notes.ui.NotesPill
import com.schedulewidget.mobile.notes.ui.NotesShapes
import com.schedulewidget.mobile.notes.ui.NotesSpace
import com.schedulewidget.mobile.notes.ui.NotesTokens
import com.schedulewidget.mobile.notes.ui.NotesTopBar
import com.schedulewidget.mobile.notes.ui.PillStyle
import com.schedulewidget.mobile.record.PlayerControls
import com.schedulewidget.mobile.record.Recorder
import com.schedulewidget.mobile.record.RecordingItem
import com.schedulewidget.mobile.record.Recordings
import com.schedulewidget.mobile.record.rememberRecordingPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@Composable
internal fun EditorTopBar(
    st: EditorState, title: String, sttEnabled: Boolean, onBack: () -> Unit, onRename: () -> Unit,
    onPages: () -> Unit, onAddPage: () -> Unit, onShare: () -> Unit, onSaveAs: () -> Unit,
    fingerDraws: Boolean, onFingerDraws: (Boolean) -> Unit, onImmersive: () -> Unit,
) {
    val context = LocalContext.current
    val c = NotesTokens.colors
    val rec by Recorder.state.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val compact = LocalWindowInfo.current.containerSize.width < 400f * density.density || density.fontScale > 1.2f
    var menu by remember { mutableStateOf(false) }
    NotesTopBar(Modifier.fillMaxWidth()) {
        NotesIconButton(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", onBack)
        Text(
            title, style = NotesTokens.type.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = NotesSpace.xxs)
                .clip(NotesShapes.full).clickable(onClickLabel = "이름 바꾸기", onClick = onRename)
                .padding(horizontal = NotesSpace.xs, vertical = 6.dp),
        )
        NotesIconButton(Icons.AutoMirrored.Filled.Undo, "실행 취소", st::undo, enabled = st.canUndo)
        if (!compact) NotesIconButton(Icons.AutoMirrored.Filled.Redo, "다시 실행", st::redo, enabled = st.canRedo)
        if (sttEnabled && (!compact || rec.isRecording)) {
            if (rec.isRecording) {
                // Recording: a soft red-tinted pill so it reads as "live" without shouting.
                NotesIconButton(Icons.Filled.Stop, "녹음 중지", { Recorder.stop(context) }, selected = true, tint = c.danger)
            } else {
                NotesIconButton(Icons.Filled.Mic, "녹음 시작", { Recorder.start(context) })
            }
        }
        NotesIconButton(Icons.Filled.GridView, "페이지", onPages)
        Box {
            NotesIconButton(Icons.Filled.MoreVert, "메뉴", { menu = true }, selected = menu)
            DropdownMenu(
                expanded = menu, onDismissRequest = { menu = false },
                shape = NotesShapes.sm, containerColor = c.canvas, tonalElevation = 0.dp, shadowElevation = 0.dp,
                border = BorderStroke(1.dp, c.hairline),
            ) {
                if (compact) {
                    MenuItem("다시 실행", Icons.AutoMirrored.Filled.Redo, enabled = st.canRedo) { menu = false; st.redo() }
                    if (sttEnabled && !rec.isRecording) MenuItem("녹음 시작", Icons.Filled.Mic) { menu = false; Recorder.start(context) }
                }
                MenuItem("빈 페이지 추가", Icons.AutoMirrored.Filled.NoteAdd) { menu = false; onAddPage() }
                if (sttEnabled) MenuItem("녹음 연동 (필기 탭 → 재생)", Icons.Filled.Link, checked = st.linkMode) {
                    menu = false; st.inputToolOverride = null; st.linkMode = !st.linkMode; st.clearSelection()
                }
                MenuItem("손가락으로 필기", Icons.Filled.TouchApp, checked = fingerDraws) { menu = false; onFingerDraws(!fingerDraws) }
                MenuItem("전체 화면", Icons.Filled.Fullscreen) { menu = false; onImmersive() }
                HorizontalDivider(Modifier.padding(vertical = NotesSpace.xxs), thickness = 1.dp, color = c.hairlineSoft)
                MenuItem("PDF 공유", Icons.Filled.Share) { menu = false; onShare() }
                MenuItem("PDF로 저장", Icons.Filled.FileDownload) { menu = false; onSaveAs() }
            }
        }
    }
}

/** ⋮ menu row: leading icon, sentence-case label, and a check when [checked] (toggles). */
@Composable
private fun MenuItem(text: String, icon: ImageVector, checked: Boolean? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val c = NotesTokens.colors
    DropdownMenuItem(
        text = { Text(text, style = NotesTokens.type.bodySm) },
        leadingIcon = { Icon(icon, null, Modifier.size(20.dp), tint = c.muted) },
        trailingIcon = if (checked == true) ({ Icon(Icons.Filled.Check, "켜짐", Modifier.size(18.dp), tint = c.ink) }) else null,
        onClick = onClick,
        enabled = enabled,
        colors = MenuDefaults.itemColors(textColor = c.ink),
        modifier = Modifier.padding(horizontal = NotesSpace.xxs).clip(NotesShapes.sm),
    )
}

/** A floating pill over the pages: canvas fill, 1px hairline, no shadow. */
@Composable
internal fun FloatingPill(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val c = NotesTokens.colors
    Row(
        modifier.height(NotesSpace.pillSmall).clip(NotesShapes.full).background(c.canvas)
            .border(1.dp, c.hairline, NotesShapes.full).clickable(onClick = onClick)
            .padding(horizontal = NotesSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content,
    )
}

/** "3 / 12 · 150%"; tap opens the page overview. */
@Composable
internal fun PageCounter(st: EditorState, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = NotesTokens.colors
    val n = st.note.pages.size
    val zoom = (st.scale * 100).roundToInt()
    FloatingPill(modifier, onClick) {
        Text("${st.currentPage + 1} / $n", style = NotesTokens.type.label.copy(fontSize = 13.sp))
        if (zoom != 100) Text("  ·  $zoom%", style = NotesTokens.type.label.copy(fontSize = 13.sp, color = c.muted))
    }
}

/** Finger / pen input mode at a glance (NotesSettings.fingerDraws); tap toggles. */
@Composable
internal fun InputModePill(fingerDraws: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val c = NotesTokens.colors
    FloatingPill(modifier, onToggle) {
        Icon(
            if (fingerDraws) Icons.Filled.TouchApp else Icons.Filled.Draw,
            null, Modifier.size(16.dp), tint = c.ink,
        )
        Spacer(Modifier.width(6.dp))
        Text(if (fingerDraws) "손가락 필기" else "펜 전용", style = NotesTokens.type.label.copy(fontSize = 13.sp))
    }
}

/** Actions for a lasso selection, floating above (or below) it. */
@Composable
internal fun SelectionBar(st: EditorState, maxWidthPx: Int) {
    val sel = st.selection ?: return
    if (st.dragDx != 0f || st.dragDy != 0f) return
    val r = st.selectionScreenRect(sel) ?: return
    val density = LocalDensity.current
    val barH = with(density) { 48.dp.toPx() }
    val barW = with(density) { 300.dp.toPx() }
    val y = if (r.top - barH - 8f > 0f) r.top - barH - 8f else r.bottom + 8f
    val x = (r.centerX() - barW / 2f).coerceIn(0f, (maxWidthPx - barW).coerceAtLeast(0f))
    val nc = NotesTokens.colors
    Row(
        Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .height(48.dp).clip(NotesShapes.full).background(nc.canvas).border(1.dp, nc.hairline, NotesShapes.full)
            .padding(horizontal = NotesSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NotesIconButton(Icons.Filled.Delete, "삭제", st::deleteSelection, tint = nc.danger)
        NotesIconButton(Icons.Filled.ContentCopy, "복제", st::duplicateSelection)
        EditorState.PEN_COLORS.forEach { c -> Swatch(c, false) { st.recolorSelection(c) } }
        NotesIconButton(Icons.Filled.Close, "선택 해제", st::clearSelection)
    }
}


internal fun safeFileName(title: String): String =
    title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().ifEmpty { "노트" }.take(80)

internal fun toast(context: Context, msg: String) = Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()
