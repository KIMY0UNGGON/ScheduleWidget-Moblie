package com.schedulewidget.mobile.notes.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.notes.NoteMeta
import com.schedulewidget.mobile.notes.NoteStore
import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.notes.render.PageDraw
import com.schedulewidget.mobile.notes.render.PdfThread
import com.schedulewidget.mobile.notes.ui.NotesPill
import com.schedulewidget.mobile.notes.ui.NotesShapes
import com.schedulewidget.mobile.notes.ui.NotesSpace
import com.schedulewidget.mobile.notes.ui.NotesTokens
import com.schedulewidget.mobile.notes.ui.PillStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Notebooks of the library (except [excludeId]), newest first, loaded off the main thread. */
@Composable
private fun rememberNotebooks(excludeId: String): List<NoteMeta>? {
    val context = LocalContext.current
    val list by produceState<List<NoteMeta>?>(null) {
        value = withContext(Dispatchers.IO) { NoteStore.list(context).filter { it.id != excludeId } }
    }
    return list
}

/** A library notebook row: thumbnail, title, page count. */
@Composable
private fun NotebookRow(note: NoteMeta, onClick: () -> Unit) {
    val context = LocalContext.current
    val c = NotesTokens.colors
    val thumb by produceState<Bitmap?>(null, note.id) {
        value = withContext(Dispatchers.IO) { NoteStore.thumbnail(context, note.id)?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() } }
    }
    Row(
        Modifier.fillMaxWidth().clip(NotesShapes.sm).clickable(onClick = onClick).padding(horizontal = NotesSpace.md, vertical = NotesSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 40.dp, height = 52.dp).clip(RoundedCornerShape(6.dp)).background(c.canvasSoft).border(1.dp, c.hairlineSoft, RoundedCornerShape(6.dp))) {
            thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
        }
        Spacer(Modifier.width(NotesSpace.sm))
        Column(Modifier.weight(1f)) {
            Text(note.title, style = NotesTokens.type.bodySm, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${note.pageCount}쪽" + (note.folder?.let { " · $it" } ?: ""), style = NotesTokens.type.caption)
        }
    }
}

/** Choose a notebook, then which of its pages to insert. */
@Composable
internal fun NotebookPagePicker(excludeId: String, onPick: (String, List<Int>) -> Unit, onDismiss: () -> Unit) {
    val c = NotesTokens.colors
    var chosen by remember { mutableStateOf<NoteMeta?>(null) }
    NotesSheet(onDismiss) {
        val note = chosen
        if (note == null) {
            Text("다른 노트에서 가져오기", style = NotesTokens.type.title, modifier = Modifier.padding(horizontal = NotesSpace.lg, vertical = NotesSpace.xs))
            val list = rememberNotebooks(excludeId)
            when {
                list == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = c.ink, trackColor = c.field, strokeWidth = 2.5.dp)
                }
                list.isEmpty() -> Text("가져올 다른 노트가 없어요", style = NotesTokens.type.bodySm.copy(color = c.muted), modifier = Modifier.padding(NotesSpace.lg))
                else -> LazyColumn(Modifier.heightIn(max = 520.dp), contentPadding = PaddingValues(horizontal = NotesSpace.xs, vertical = NotesSpace.xs)) {
                    items(list, key = { it.id }) { n -> NotebookRow(n) { chosen = n } }
                }
            }
        } else {
            OtherNotePages(note, onBack = { chosen = null }, onPick = { onPick(note.id, it) })
        }
        Spacer(Modifier.height(NotesSpace.md))
    }
}

/** Page grid of another notebook with check circles. */
@Composable
private fun OtherNotePages(note: NoteMeta, onBack: () -> Unit, onPick: (List<Int>) -> Unit) {
    val context = LocalContext.current
    val c = NotesTokens.colors
    var loadFailed by remember(note.id) { mutableStateOf(false) }
    val stored by produceState<Pair<List<PageInfo>, Map<String, PageInk>>?>(null, note.id) {
        try {
            val result = withContext(Dispatchers.IO) {
                NoteStore.pendingSaves[note.id]?.join()
                NoteStore.load(context, note.id)?.let { n -> n.pages to n.pages.associate { it.uid to NoteStore.loadInk(context, note.id, it.uid) } }
            }
            if (result == null) loadFailed = true else value = result
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            loadFailed = true
        }
    }
    var picked by remember { mutableStateOf(setOf<Int>()) }
    val pool = remember(note.id) { PdfPool(NoteStore.dir(context, note.id)) }
    DisposableEffect(pool) { onDispose { NoteStore.scope.launch(PdfThread.dispatcher) { pool.close() } } }
    val pages = stored?.first.orEmpty()
    Row(Modifier.fillMaxWidth().padding(horizontal = NotesSpace.md), verticalAlignment = Alignment.CenterVertically) {
        NotesPill("목록", onClick = onBack, style = PillStyle.Soft, small = true)
        Spacer(Modifier.width(NotesSpace.sm))
        Text(note.title, style = NotesTokens.type.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (pages.isNotEmpty()) NotesPill(
            if (picked.size == pages.size) "선택 해제" else "전체 선택",
            onClick = { picked = if (picked.size == pages.size) emptySet() else pages.indices.toSet() },
            style = PillStyle.Soft, small = true,
        )
    }
    if (loadFailed) {
        Text(
            "필기 파일을 읽을 수 없어 페이지를 가져오지 못했어요. 원본 파일은 그대로 있습니다.",
            style = NotesTokens.type.bodySm.copy(color = c.muted),
            modifier = Modifier.fillMaxWidth().padding(NotesSpace.lg),
        )
        return
    }
    if (stored == null) {
        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), color = c.ink, trackColor = c.field, strokeWidth = 2.5.dp)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        contentPadding = PaddingValues(NotesSpace.md),
        horizontalArrangement = Arrangement.spacedBy(NotesSpace.sm),
        verticalArrangement = Arrangement.spacedBy(NotesSpace.sm),
        modifier = Modifier.heightIn(max = 460.dp),
    ) {
        itemsIndexed(pages, key = { _, p -> p.uid }) { i, p ->
            val on = i in picked
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio((p.w / p.h).coerceIn(0.3f, 3f)).clip(RoundedCornerShape(8.dp))
                        .border(if (on) 2.dp else 1.dp, if (on) c.ink else c.hairline, RoundedCornerShape(8.dp))
                        .clickable { picked = if (on) picked - i else picked + i },
                ) {
                    OtherPageImage(note.id, p, stored!!.second[p.uid] ?: PageInk.EMPTY, pool)
                    CheckCircle(on, Modifier.align(Alignment.TopEnd).padding(6.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text("${i + 1}", style = NotesTokens.type.label.copy(color = if (on) c.ink else c.muted))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = NotesSpace.md), horizontalArrangement = Arrangement.End) {
        NotesPill(
            if (picked.isEmpty()) "페이지를 고르세요" else "${picked.size}쪽 넣기",
            onClick = { onPick(picked.sorted()) }, enabled = picked.isNotEmpty(),
        )
    }
}

/** A page of another notebook rendered (PDF + template + handwriting) on the PDF thread. */
@Composable
private fun BoxScope.OtherPageImage(noteId: String, page: PageInfo, ink: PageInk, pool: PdfPool) {
    val bmp by produceState<Bitmap?>(null, noteId, page.uid) {
        value = withContext(PdfThread.dispatcher) {
            runCatching {
                val wPx = 240
                val scale = wPx / page.w
                val hPx = (page.h * scale).roundToInt().coerceIn(1, wPx * 4)
                val b = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
                b.eraseColor(AColor.WHITE)
                if (page.isPdf) pool.doc(page)?.takeIf { page.pdf < it.pageCount }?.render(page.pdf, b, PageSources.pdfMatrix(page, scale, scale))
                val cv = android.graphics.Canvas(b)
                cv.scale(scale, scale)
                PageDraw.drawTemplate(cv, page, scale)
                PageDraw.drawInk(cv, ink, cache = false)
                b
            }.getOrNull()
        }
    }
    Box(Modifier.matchParentSize().background(Color.White)) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.matchParentSize()) }
    }
}

/** Copy pages to an existing notebook, or extract them into a new one. [onCopy] gets (target id or null, new title). */
@Composable
internal fun CopyToNotebookSheet(excludeId: String, count: Int, defaultTitle: String, onCopy: (String?, String) -> Unit, onDismiss: () -> Unit) {
    val c = NotesTokens.colors
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        var value by remember { mutableStateOf(TextFieldValue(defaultTitle, TextRange(0, defaultTitle.length))) }
        NotesDialog(
            title = "새 노트로 추출",
            onDismiss = onDismiss,
            buttons = {
                NotesPill("취소", onClick = onDismiss, style = PillStyle.Outline)
                NotesPill("만들기", onClick = { onCopy(null, value.text.trim().ifEmpty { defaultTitle }) })
            },
        ) {
            Text("선택한 ${count}쪽으로 새 노트를 만들어요", style = NotesTokens.type.bodySm.copy(color = c.muted))
            Spacer(Modifier.height(NotesSpace.sm))
            NotesField(value, { value = it }, placeholder = "노트 이름", singleLine = true, modifier = Modifier.fillMaxWidth(),
                onDone = { onCopy(null, value.text.trim().ifEmpty { defaultTitle }) })
        }
        return
    }
    NotesSheet(onDismiss) {
        Text("${count}쪽 복사하기", style = NotesTokens.type.title, modifier = Modifier.padding(horizontal = NotesSpace.lg, vertical = NotesSpace.xs))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = NotesSpace.xs).clip(NotesShapes.sm).clickable { naming = true }
                .padding(horizontal = NotesSpace.md, vertical = NotesSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 40.dp, height = 40.dp).clip(NotesShapes.squircle).background(c.canvasSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.NoteAdd, null, tint = c.ink, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(NotesSpace.sm))
            Column(Modifier.weight(1f)) {
                Text("새 노트로 추출", style = NotesTokens.type.bodySm)
                Text("선택한 페이지만으로 노트를 만들어요", style = NotesTokens.type.caption)
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = NotesSpace.lg, vertical = NotesSpace.xs).height(1.dp).background(c.hairlineSoft))
        Text("다른 노트 끝에 붙이기", style = NotesTokens.type.label.copy(color = c.muted), modifier = Modifier.padding(horizontal = NotesSpace.lg, vertical = NotesSpace.xxs))
        val list = rememberNotebooks(excludeId)
        when {
            list == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = c.ink, trackColor = c.field, strokeWidth = 2.5.dp)
            }
            list.isEmpty() -> Text("다른 노트가 없어요", style = NotesTokens.type.bodySm.copy(color = c.muted), modifier = Modifier.padding(NotesSpace.lg))
            else -> LazyColumn(Modifier.heightIn(max = 440.dp), contentPadding = PaddingValues(horizontal = NotesSpace.xs, vertical = NotesSpace.xs)) {
                items(list, key = { it.id }) { n -> NotebookRow(n) { onCopy(n.id, n.title) } }
            }
        }
        Spacer(Modifier.height(NotesSpace.md))
    }
}
