package com.schedulewidget.mobile.notes

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.notes.library.NotesMenu
import com.schedulewidget.mobile.notes.library.NotesMenuItem
import com.schedulewidget.mobile.notes.library.PaperColor
import com.schedulewidget.mobile.notes.ui.NotesChip
import com.schedulewidget.mobile.notes.ui.NotesIconButton
import com.schedulewidget.mobile.notes.ui.NotesShapes
import com.schedulewidget.mobile.notes.ui.NotesSpace
import com.schedulewidget.mobile.notes.ui.NotesTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---- notebook card ----

private val BADGES = mapOf("pdf" to "PDF", "pptx" to "PPT", "docx" to "Word", "image" to "사진", "blank" to "노트", "flexcil" to "Flexcil")

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NotebookCard(
    note: NoteMeta, version: Int, onOpen: () -> Unit, onRename: () -> Unit, onMove: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val c = NotesTokens.colors
    var menu by remember { mutableStateOf(false) }
    var armedDelete by remember(menu) { mutableStateOf(false) }
    val thumb by produceState<ImageBitmap?>(null, note.id, note.updatedAt, version) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { NoteStore.thumbnail(context, note.id)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }.getOrNull()
        }
        if (loaded != null || value == null) value = loaded
    }
    Column(Modifier.clip(NotesShapes.sm).combinedClickable(onClick = onOpen, onLongClick = { menu = true })) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.75f).clip(NotesShapes.sm)
                .background(c.canvasSoft)
                .border(1.dp, c.hairlineSoft, NotesShapes.sm),
            contentAlignment = Alignment.Center,
        ) {
            val t = thumb
            if (t != null) {
                Image(t, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().background(PaperColor))
            } else {
                Icon(Icons.Outlined.Description, null, tint = c.faint, modifier = Modifier.size(36.dp))
            }
            NotesChip(BADGES[note.source] ?: note.source.uppercase(), overlay = true, modifier = Modifier.align(Alignment.TopStart).padding(NotesSpace.xs))
        }
        Row(Modifier.padding(top = NotesSpace.sm), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(start = NotesSpace.xxs)) {
                Text(note.title, style = NotesTokens.type.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${note.pageCount}쪽 · ${shortDate(note.updatedAt)}" + (note.folder?.let { " · $it" } ?: ""),
                    style = NotesTokens.type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                NotesIconButton(Icons.Filled.MoreVert, "더보기", onClick = { menu = true }, size = 32.dp, tint = c.muted)
                NotesMenu(menu, onDismissRequest = { menu = false }) {
                    NotesMenuItem("이름 바꾸기", onClick = { menu = false; onRename() }, icon = Icons.Outlined.Edit)
                    NotesMenuItem("폴더로 이동", onClick = { menu = false; onMove() }, icon = Icons.AutoMirrored.Outlined.DriveFileMove)
                    NotesMenuItem("PDF로 내보내기", onClick = { menu = false; onExport() }, icon = Icons.Outlined.PictureAsPdf)
                    NotesMenuItem(
                        if (armedDelete) "정말 삭제" else "삭제",
                        onClick = { if (armedDelete) { menu = false; onDelete() } else armedDelete = true },
                        icon = Icons.Outlined.Delete, danger = true,
                    )
                }
            }
        }
    }
}

private fun shortDate(ms: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val pattern = when {
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) -> "HH:mm"
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> "M월 d일"
        else -> "yyyy. M. d."
    }
    return SimpleDateFormat(pattern, Locale.KOREAN).format(Date(ms))
}
