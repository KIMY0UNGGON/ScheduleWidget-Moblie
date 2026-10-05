package com.schedulewidget.mobile.notes.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.schedulewidget.mobile.notes.ui.NotesIconButton
import com.schedulewidget.mobile.notes.ui.NotesPill
import com.schedulewidget.mobile.notes.ui.NotesShapes
import com.schedulewidget.mobile.notes.ui.NotesSpace
import com.schedulewidget.mobile.notes.ui.NotesTokens
import com.schedulewidget.mobile.notes.ui.PillStyle

/**
 * Page overview: tap a page to jump there; long-press and drag to reorder; long-press without moving (or "선택")
 * starts selection mode, whose pill toolbar duplicates / rotates / moves / exports / deletes the checked pages.
 * Each page's ⋯ menu has the single-page actions.
 */
@Composable
internal fun PagesSheet(
    st: EditorState,
    renderer: PageRenderer,
    actions: PageActions,
    onDismiss: () -> Unit,
) {
    val c = NotesTokens.colors
    val pages = st.note.pages
    val current = st.currentPage
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = current.coerceAtLeast(0))
    var selecting by remember { mutableStateOf(false) }
    // Checked pages by uid (stable while pages move); indices are derived from the current list.
    var checked by remember { mutableStateOf(setOf<String>()) }
    val checkedIdx = pages.indices.filter { pages[it].uid in checked }
    var confirmDelete by remember { mutableStateOf<List<Int>?>(null) }
    // Drag-to-reorder: the dragged page's uid, its finger offset, and the index it would drop at.
    var dragUid by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragTravel by remember { mutableFloatStateOf(0f) }
    var dropIndex by remember { mutableIntStateOf(-1) }
    var fromIndex by remember { mutableIntStateOf(-1) }
    val order = remember(pages, dragUid, dropIndex) {
        if (dragUid == null || fromIndex < 0 || dropIndex < 0) pages
        else pages.toMutableList().apply { add(dropIndex.coerceIn(0, size - 1), removeAt(fromIndex)) }
    }
    fun toggle(uid: String) { checked = if (uid in checked) checked - uid else checked + uid }
    fun endSelection() { selecting = false; checked = emptySet() }

    NotesSheet(onDismiss) {
        // ---- header ----
        Row(
            Modifier.fillMaxWidth().padding(start = NotesSpace.lg, end = NotesSpace.md, bottom = NotesSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NotesSpace.xs),
        ) {
            if (!selecting) {
                Column(Modifier.weight(1f)) {
                    Text("페이지", style = NotesTokens.type.h3)
                    Text("${pages.size}쪽 · 길게 눌러 끌면 순서를 바꿔요", style = NotesTokens.type.caption)
                }
                NotesPill("선택", onClick = { selecting = true }, style = PillStyle.Soft, small = true)
                NotesPill("추가", onClick = { actions.insert(current) }, icon = Icons.Filled.Add, small = true)
            } else {
                Column(Modifier.weight(1f)) {
                    Text(if (checkedIdx.isEmpty()) "페이지 선택" else "${checkedIdx.size}쪽 선택됨", style = NotesTokens.type.h3)
                    Text("탭해서 고르세요", style = NotesTokens.type.caption)
                }
                val all = checkedIdx.size == pages.size
                NotesPill(
                    if (all) "선택 해제" else "전체 선택",
                    onClick = { checked = if (all) emptySet() else pages.mapTo(HashSet()) { it.uid } },
                    style = PillStyle.Soft, small = true,
                )
                NotesPill("완료", onClick = ::endSelection, style = PillStyle.Outline, small = true)
            }
        }

        // ---- grid ----
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            state = grid,
            contentPadding = PaddingValues(horizontal = NotesSpace.md, vertical = NotesSpace.sm),
            horizontalArrangement = Arrangement.spacedBy(NotesSpace.sm),
            verticalArrangement = Arrangement.spacedBy(NotesSpace.md),
            modifier = Modifier.heightIn(max = 540.dp).pointerInput(pages) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { pos ->
                        val hit = grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                            pos.x >= item.offset.x && pos.x < item.offset.x + item.size.width &&
                                pos.y >= item.offset.y && pos.y < item.offset.y + item.size.height
                        } ?: return@detectDragGesturesAfterLongPress
                        dragUid = hit.key as String
                        fromIndex = hit.index
                        dropIndex = hit.index
                        dragOffset = Offset.Zero
                        dragTravel = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        val uid = dragUid ?: return@detectDragGesturesAfterLongPress
                        dragOffset += amount
                        dragTravel += amount.getDistance()
                        val items = grid.layoutInfo.visibleItemsInfo
                        val me = items.firstOrNull { it.key == uid } ?: return@detectDragGesturesAfterLongPress
                        val cx = me.offset.x + dragOffset.x + me.size.width / 2f
                        val cy = me.offset.y + dragOffset.y + me.size.height / 2f
                        val target = items.firstOrNull { item ->
                            item.key != uid && cx >= item.offset.x && cx < item.offset.x + item.size.width &&
                                cy >= item.offset.y && cy < item.offset.y + item.size.height
                        }
                        if (target != null) {
                            // The dragged cell moves to the target's slot: keep it under the finger.
                            dragOffset += Offset(
                                (me.offset.x - target.offset.x).toFloat(), (me.offset.y - target.offset.y).toFloat(),
                            )
                            dropIndex = target.index
                        }
                    },
                    onDragEnd = {
                        val uid = dragUid
                        if (fromIndex >= 0 && dropIndex >= 0 && fromIndex != dropIndex) {
                            st.movePage(fromIndex, dropIndex)
                        } else if (uid != null && dragTravel < 24f * density) {
                            // A long press that didn't move: select that page.
                            selecting = true
                            checked = checked + uid
                        }
                        dragUid = null; fromIndex = -1; dropIndex = -1; dragOffset = Offset.Zero
                    },
                    onDragCancel = { dragUid = null; fromIndex = -1; dropIndex = -1; dragOffset = Offset.Zero },
                )
            },
        ) {
            itemsIndexed(order, key = { _, p -> p.uid }) { index, page ->
                val dragging = page.uid == dragUid
                val realIndex = pages.indexOfFirst { it.uid == page.uid }
                val isCurrent = realIndex == current
                val isChecked = page.uid in checked
                Column(
                    Modifier.zIndex(if (dragging) 1f else 0f).graphicsLayer {
                        if (dragging) {
                            translationX = dragOffset.x; translationY = dragOffset.y
                            scaleX = 1.06f; scaleY = 1.06f; alpha = 0.92f
                        }
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        PageThumb(
                            st, renderer, realIndex, page.w / page.h, current = isCurrent, checked = isChecked,
                            onClick = {
                                if (selecting) toggle(page.uid)
                                else { st.goToPage(realIndex); onDismiss() }
                            },
                        )
                        if (selecting) CheckCircle(isChecked, Modifier.align(Alignment.TopEnd).padding(6.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${index + 1}",
                            style = NotesTokens.type.label.copy(color = if (isCurrent) c.ink else c.muted),
                            modifier = Modifier.padding(start = if (selecting) 0.dp else 28.dp),
                        )
                        if (!selecting) PageMenu(st, realIndex, actions, onDelete = { st.deletePage(realIndex) })
                    }
                }
            }
        }

        // ---- selection toolbar ----
        if (selecting) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairlineSoft))
            val has = checkedIdx.isNotEmpty()
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = NotesSpace.md, vertical = NotesSpace.sm),
                horizontalArrangement = Arrangement.spacedBy(NotesSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NotesPill("복제", onClick = { st.duplicatePages(checkedIdx) }, style = PillStyle.Soft, icon = Icons.Filled.ContentCopy, enabled = has, small = true)
                NotesPill("회전", onClick = { st.rotatePages(checkedIdx, 1) }, style = PillStyle.Soft, icon = Icons.Filled.RotateRight, enabled = has, small = true)
                MenuPill("이동", Icons.Filled.SwapVert, has) { close ->
                    MenuItem("맨 앞으로") { close(); st.movePagesTo(checkedIdx, toStart = true) }
                    MenuItem("맨 뒤로") { close(); st.movePagesTo(checkedIdx, toStart = false) }
                    MenuItem("다른 노트로 복사 · 새 노트로 추출") { close(); actions.copyToNotebook(checkedIdx) }
                }
                NotesPill("내보내기", onClick = { actions.export(checkedIdx) }, style = PillStyle.Soft, icon = Icons.Filled.PictureAsPdf, enabled = has, small = true)
                MenuPill("더보기", Icons.Filled.MoreHoriz, has) { close ->
                    MenuItem("배경 바꾸기") { close(); actions.changeBackground(checkedIdx) }
                    MenuItem("필기 지우기") { close(); st.clearPages(checkedIdx) }
                }
                NotesPill("삭제", onClick = { confirmDelete = checkedIdx }, style = PillStyle.Danger, icon = Icons.Filled.Delete, enabled = has, small = true)
            }
        }
    }

    confirmDelete?.let { indices ->
        NotesDialog(
            title = "${indices.size}쪽을 지울까요?",
            onDismiss = { confirmDelete = null },
            buttons = {
                NotesPill("취소", onClick = { confirmDelete = null }, style = PillStyle.Outline)
                NotesPill("삭제", onClick = {
                    confirmDelete = null
                    if (st.deletePages(indices)) checked = emptySet()
                }, style = PillStyle.Danger)
            },
        ) {
            Text("실행 취소로 되돌릴 수 있어요", style = NotesTokens.type.bodySm.copy(color = c.muted))
        }
    }
}

@Composable
private fun PageThumb(
    st: EditorState, renderer: PageRenderer, index: Int, aspect: Float,
    current: Boolean, checked: Boolean, onClick: () -> Unit,
) {
    val c = NotesTokens.colors
    // The cell is composed while visible; its bitmap request is dropped once it scrolls away.
    val active = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { active.set(false) } }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.3f, 3f))
            .clip(shape)
            .border(
                if (current || checked) 2.dp else 1.dp,
                when { current -> c.ink; checked -> c.ink; else -> c.hairline },
                shape,
            )
            .clickable(onClick = onClick)
    ) {
        Canvas(Modifier.matchParentSize()) {
            st.inkVersion
            renderer.version
            drawIntoCanvas { cv -> EditorDraw.drawThumb(cv.nativeCanvas, st, renderer, index, size.width, size.height) { active.get() } }
        }
        if (checked) Box(Modifier.matchParentSize().background(c.ink.copy(alpha = 0.08f)))
    }
}

/** ⋯ menu of one page. */
@Composable
private fun PageMenu(st: EditorState, index: Int, actions: PageActions, onDelete: () -> Unit) {
    val c = NotesTokens.colors
    var open by remember { mutableStateOf(false) }
    val page = st.note.pages.getOrNull(index)
    Box {
        NotesIconButton(Icons.Filled.MoreVert, "페이지 메뉴", onClick = { open = true }, size = 28.dp, tint = c.muted)
        NotesMenu(open, onDismiss = { open = false }) {
            MenuItem("페이지 추가…") { open = false; actions.insert(index) }
            MenuItem("복제") { open = false; st.duplicatePage(index) }
            MenuItem("오른쪽으로 회전") { open = false; st.rotatePages(listOf(index), 1) }
            MenuItem("왼쪽으로 회전") { open = false; st.rotatePages(listOf(index), -1) }
            if (page != null && !page.isPdf) MenuItem("배경 바꾸기") { open = false; actions.changeBackground(listOf(index)) }
            MenuItem("필기 지우기") { open = false; st.clearPages(listOf(index)) }
            MenuItem("PDF로 내보내기") { open = false; actions.export(listOf(index)) }
            MenuItem("다른 노트로 복사") { open = false; actions.copyToNotebook(listOf(index)) }
            MenuItem("삭제", danger = true) { open = false; onDelete() }
        }
    }
}

/** A Soft pill that opens a menu; [content] gets a `close` callback. */
@Composable
private fun MenuPill(text: String, icon: ImageVector, enabled: Boolean, content: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        NotesPill(text, onClick = { open = true }, style = PillStyle.Soft, icon = icon, enabled = enabled, small = true)
        NotesMenu(open, onDismiss = { open = false }) { content { open = false } }
    }
}

/** Dropdown in the notes system: canvas fill, hairline edge, 16dp corners, no elevation. */
@Composable
private fun NotesMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = NotesTokens.colors
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss,
        shape = NotesShapes.sm, containerColor = c.canvas, tonalElevation = 0.dp, shadowElevation = 0.dp,
        border = BorderStroke(1.dp, c.hairline),
    ) { content() }
}

@Composable
private fun MenuItem(text: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = NotesTokens.colors
    DropdownMenuItem(
        text = { Text(text, style = NotesTokens.type.bodySm.copy(color = if (danger) c.danger else c.ink)) },
        onClick = onClick,
    )
}
