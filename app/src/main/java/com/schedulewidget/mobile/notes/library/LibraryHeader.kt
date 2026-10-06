package com.schedulewidget.mobile.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.notes.library.NoteFolders
import com.schedulewidget.mobile.notes.library.NotesField
import com.schedulewidget.mobile.notes.library.NotesMenu
import com.schedulewidget.mobile.notes.library.NotesMenuItem
import com.schedulewidget.mobile.notes.library.SquircleTile
import com.schedulewidget.mobile.notes.library.ThinProgress
import com.schedulewidget.mobile.notes.ui.NotesCard
import com.schedulewidget.mobile.notes.ui.NotesIconButton
import com.schedulewidget.mobile.notes.ui.NotesPill
import com.schedulewidget.mobile.notes.ui.NotesShapes
import com.schedulewidget.mobile.notes.ui.NotesSpace
import com.schedulewidget.mobile.notes.ui.NotesTokens
import com.schedulewidget.mobile.notes.ui.PillStyle

// ---- header: heading, search, sort, import progress, folder breadcrumbs ----

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryHeader(
    count: String,
    query: String,
    onQuery: (String) -> Unit,
    sort: String,
    onSort: (String) -> Unit,
    running: NoteImport.State.Running?,
    showFolders: Boolean,
    folder: String?,
    onFolder: (String?) -> Unit,
    onNewFolder: () -> Unit,
    onRenameFolder: (String) -> Unit,
    onRemoveFolder: (String) -> Unit,
) {
    val c = NotesTokens.colors
    var sortMenu by remember { mutableStateOf(false) }
    var titleMenu by remember { mutableStateOf(false) }
    // Inside a folder the heading becomes that folder, with "← 노트 › 상위" above it. Search spans every note, so it reads 노트.
    val current = folder?.takeIf { showFolders }
    Column(Modifier.fillMaxWidth()) {
        if (current != null) FolderPath(current, onFolder, onRenameFolder, onRemoveFolder)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Text(
                    current?.let(NoteFolders::leaf) ?: "노트", style = NotesTokens.type.h1,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = if (current == null) Modifier else Modifier.combinedClickable(
                        onClickLabel = "폴더 메뉴", onClick = { titleMenu = true }, onLongClick = { titleMenu = true },
                    ),
                )
                if (current != null) FolderMenu(
                    expanded = titleMenu, onDismiss = { titleMenu = false },
                    onRename = { titleMenu = false; onRenameFolder(current) },
                    onRemove = { titleMenu = false; onRemoveFolder(current) },
                )
            }
            if (showFolders) NotesIconButton(Icons.Outlined.CreateNewFolder, "새 폴더", onClick = onNewFolder, tint = c.muted)
        }
        Spacer(Modifier.height(6.dp))
        Text(count, style = NotesTokens.type.caption.copy(fontSize = NotesTokens.type.bodySm.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(NotesSpace.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NotesField(
                value = query, onValueChange = onQuery, placeholder = "제목 검색",
                leadingIcon = Icons.Outlined.Search, imeAction = ImeAction.Search,
                trailing = if (query.isNotEmpty()) {
                    { NotesIconButton(Icons.Filled.Close, "검색 지우기", onClick = { onQuery("") }, size = 40.dp, tint = c.muted) }
                } else null,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(NotesSpace.xs))
            Box {
                NotesPill(
                    SORTS.firstOrNull { it.first == sort }?.second ?: "정렬",
                    onClick = { sortMenu = true }, style = PillStyle.Soft, icon = Icons.AutoMirrored.Filled.Sort,
                    modifier = Modifier.height(48.dp),
                )
                NotesMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                    SORTS.forEach { (key, label) ->
                        NotesMenuItem(label, onClick = { onSort(key); sortMenu = false }, checked = sort == key)
                    }
                }
            }
        }
        if (running != null) {
            Spacer(Modifier.height(NotesSpace.md))
            ImportProgress(running.stage, running.detail)
        }
    }
}

/**
 * "← 노트 › 학교" above a folder's heading: the up arrow stays put, the ancestors (muted text, the folder itself is the
 * heading) scroll. Tap a crumb to go there; long-press a folder crumb to rename / remove it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderPath(folder: String, onFolder: (String?) -> Unit, onRenameFolder: (String) -> Unit, onRemoveFolder: (String) -> Unit) {
    val c = NotesTokens.colors
    val crumbs = remember(folder) { listOf<String?>(null) + generateSequence(NoteFolders.parentOf(folder)) { NoteFolders.parentOf(it) }.toList().reversed() }
    val list = rememberLazyListState()
    // Deep paths open scrolled to the end so the nearest parent is the one in view.
    LaunchedEffect(folder) { list.scrollToItem(crumbs.lastIndex) }
    var menu by remember { mutableStateOf<String?>(null) }
    // Bleeds to the screen edges; the start inset puts the arrow glyph (centered in its 48dp target) on the gutter.
    Row(Modifier.bleed(Gutter).padding(start = Gutter - 14.dp), verticalAlignment = Alignment.CenterVertically) {
        NotesIconButton(Icons.AutoMirrored.Filled.ArrowBack, "상위 폴더로", onClick = { onFolder(NoteFolders.parentOf(folder)) }, size = 36.dp)
        LazyRow(
            state = list, modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(end = Gutter), verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(crumbs, key = { _, f -> f?.let { "f:$it" } ?: "root" }) { i, f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (i > 0) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.faint, modifier = Modifier.size(16.dp))
                    Box {
                        Box(
                            Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).clip(NotesShapes.full)
                                .combinedClickable(
                                    role = Role.Button,
                                    onLongClickLabel = if (f != null) "폴더 메뉴" else null,
                                    onLongClick = if (f != null) { { menu = f } } else null,
                                    onClick = { onFolder(f) },
                                )
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(f?.let(NoteFolders::leaf) ?: "노트", style = NotesTokens.type.bodySm.copy(color = c.muted), maxLines = 1) }
                        if (f != null) FolderMenu(
                            expanded = menu == f, onDismiss = { menu = null },
                            onRename = { menu = null; onRenameFolder(f) },
                            onRemove = { menu = null; onRemoveFolder(f) },
                        )
                    }
                }
            }
        }
    }
}

/** Widens a full-span item by [gutter] on both sides so it reaches the screen edges past the grid's padding. */
private fun Modifier.bleed(gutter: Dp) = layout { measurable, constraints ->
    val extra = gutter.roundToPx() * 2
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

@Composable
private fun ImportProgress(stage: String, detail: String?) {
    val c = NotesTokens.colors
    NotesCard(featured = true, padding = PaddingValues(NotesSpace.md)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SquircleTile(Icons.Outlined.FileOpen, size = 40.dp, background = c.canvas)
                Spacer(Modifier.width(NotesSpace.sm))
                Column(Modifier.weight(1f)) {
                    Text(stage, style = NotesTokens.type.bodySm.copy(fontWeight = NotesTokens.type.title.fontWeight), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (detail != null) Text(detail, style = NotesTokens.type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(NotesSpace.xs))
                NotesPill("취소", onClick = { NoteImport.cancel() }, style = PillStyle.Outline, small = true)
            }
            Spacer(Modifier.height(NotesSpace.sm))
            ThinProgress()
        }
    }
}

@Composable
internal fun FolderMenu(expanded: Boolean, onDismiss: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    var armed by remember(expanded) { mutableStateOf(false) }
    NotesMenu(expanded, onDismissRequest = onDismiss) {
        NotesMenuItem("폴더 이름 바꾸기", onClick = onRename, icon = Icons.Outlined.Edit)
        NotesMenuItem(
            if (armed) "정말 삭제 (노트는 남아요)" else "폴더 삭제",
            onClick = { if (armed) onRemove() else armed = true },
            icon = Icons.Outlined.Delete, danger = true,
        )
    }
}

/** Centered empty state: h2, light lead, one pill. */
@Composable
internal fun EmptyState(title: String, lead: String, action: String, onAction: () -> Unit, primary: Boolean = true) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = NotesSpace.md, vertical = NotesSpace.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SquircleTile(Icons.Outlined.EditNote, size = 64.dp)
        Spacer(Modifier.height(NotesSpace.lg))
        Text(title, style = NotesTokens.type.h2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(NotesSpace.sm))
        Text(lead, style = NotesTokens.type.bodyLg, textAlign = TextAlign.Center)
        Spacer(Modifier.height(NotesSpace.lg))
        NotesPill(
            action, onClick = onAction,
            style = if (primary) PillStyle.Primary else PillStyle.Soft,
            icon = if (primary) Icons.Filled.Add else null,
        )
    }
}
