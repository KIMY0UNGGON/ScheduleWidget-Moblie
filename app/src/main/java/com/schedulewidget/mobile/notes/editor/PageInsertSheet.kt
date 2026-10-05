package com.schedulewidget.mobile.notes.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.ink.Template
import com.schedulewidget.mobile.notes.render.PageDraw
import com.schedulewidget.mobile.notes.render.PageTemplates
import com.schedulewidget.mobile.notes.ui.NotesPill
import com.schedulewidget.mobile.notes.ui.NotesSegmented
import com.schedulewidget.mobile.notes.ui.NotesShapes
import com.schedulewidget.mobile.notes.ui.NotesSpace
import com.schedulewidget.mobile.notes.ui.NotesTokens

private enum class InsertPos { BEFORE, AFTER, END }

/**
 * "페이지 추가" sheet: where (before / after page [anchor], or at the end), a blank page (template previews, size,
 * paper colour), or pages from another file (PDF, photos, camera, another notebook, PPT / Word).
 */
@Composable
internal fun PageInsertSheet(
    st: EditorState,
    anchor: Int,
    onBlank: (at: Int, template: String, size: PageSize, paper: String?) -> Unit,
    onSource: (InsertSource, at: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = NotesTokens.colors
    val pages = st.note.pages
    val ref = pages.getOrNull(anchor)
    var pos by remember { mutableStateOf(InsertPos.AFTER) }
    var template by remember { mutableStateOf(ref?.takeIf { !it.isPdf }?.template ?: Template.PLAIN) }
    var size by remember { mutableStateOf(PageSize.SAME) }
    var paper by remember { mutableStateOf(ref?.takeIf { !it.isPdf }?.paper ?: PageTemplates.PAPER_WHITE) }
    val at = when (pos) {
        InsertPos.BEFORE -> anchor.coerceIn(0, pages.size)
        InsertPos.AFTER -> (anchor + 1).coerceIn(0, pages.size)
        InsertPos.END -> pages.size
    }
    val (pw, ph) = size.size(ref)

    NotesSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = NotesSpace.lg)) {
            Text("페이지 추가", style = NotesTokens.type.h3)
            Spacer(Modifier.height(NotesSpace.xxs))
            Text("${anchor + 1}쪽 기준", style = NotesTokens.type.caption)
            Spacer(Modifier.height(NotesSpace.md))
            NotesSegmented(
                listOf(InsertPos.BEFORE to "앞에", InsertPos.AFTER to "뒤에", InsertPos.END to "맨 끝에"),
                pos, { pos = it }, Modifier.fillMaxWidth(),
            )

            SectionLabel("빈 페이지")
            TemplateStrip(template, paper, pw / ph) { template = it }
            Spacer(Modifier.height(NotesSpace.md))
            NotesSegmented(
                PageSize.entries.map { it to if (it == PageSize.SAME) "현재와 같게" else it.label },
                size, { size = it }, Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(NotesSpace.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PaperPicker(paper, Modifier.weight(1f)) { paper = it }
                NotesPill("빈 페이지 추가", onClick = { onBlank(at, template, size, paper) })
            }

            SectionLabel("다른 파일에서")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SourceTile(Icons.Filled.PictureAsPdf, "PDF") { onSource(InsertSource.PDF, at) }
                SourceTile(Icons.Filled.Image, "사진") { onSource(InsertSource.IMAGES, at) }
                SourceTile(Icons.Filled.PhotoCamera, "카메라") { onSource(InsertSource.CAMERA, at) }
                SourceTile(Icons.AutoMirrored.Filled.MenuBook, "다른 노트") { onSource(InsertSource.NOTEBOOK, at) }
                SourceTile(Icons.Filled.Slideshow, "PPT·Word") { onSource(InsertSource.OFFICE, at) }
            }
            Spacer(Modifier.height(NotesSpace.lg))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(NotesSpace.lg))
    Text(text, style = NotesTokens.type.label.copy(color = NotesTokens.colors.muted))
    Spacer(Modifier.height(NotesSpace.sm))
}

/** Template previews (drawn with the real template code on [paper]); the chosen one gets an accent ring. */
@Composable
internal fun TemplateStrip(selected: String, paper: String?, aspect: Float, onSelect: (String) -> Unit) {
    val c = NotesTokens.colors
    val a = aspect.coerceIn(0.5f, 1.6f)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(NotesSpace.sm)) {
        PageTemplates.all.forEach { t ->
            val on = t == selected
            Column(
                Modifier.clip(NotesShapes.sm).clickable { onSelect(t) }.padding(NotesSpace.xxs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Paper on the desk tint, edged by a hairline; the selected one ringed in accent (no shadow).
                val shape = RoundedCornerShape(12.dp)
                Box(
                    Modifier.clip(shape).background(c.desk)
                        .border(if (on) 2.dp else 1.dp, if (on) c.ink else c.hairlineSoft, shape)
                        .padding(NotesSpace.xs),
                    contentAlignment = Alignment.Center,
                ) {
                    val boxW = 64.dp
                    val (w, h) = if (a <= 1f) boxW to boxW / a else (boxW * 1.4f) to (boxW * 1.4f) / a
                    val page = remember(t, paper, a) {
                        PageInfo("preview", PageInfo.KIND_BLANK, template = t, w = 595f, h = 595f / a, paper = PageTemplates.paperValue(paper))
                    }
                    Canvas(Modifier.size(w, h).background(Color.White).border(1.dp, c.hairline)) {
                        drawIntoCanvas { cv ->
                            val sc = size.width / page.w
                            cv.nativeCanvas.save()
                            cv.nativeCanvas.scale(sc, sc)
                            PageDraw.drawTemplate(cv.nativeCanvas, page, sc)
                            cv.nativeCanvas.restore()
                        }
                    }
                }
                Spacer(Modifier.height(NotesSpace.xs))
                Text(PageTemplates.label(t), style = NotesTokens.type.label.copy(color = if (on) c.ink else c.muted))
            }
        }
    }
}

/** Paper colour swatches (흰색 / 아이보리 / 다크). */
@Composable
internal fun PaperPicker(selected: String?, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    val c = NotesTokens.colors
    val current = selected ?: PageTemplates.PAPER_WHITE
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(NotesSpace.sm), verticalAlignment = Alignment.CenterVertically) {
        PageTemplates.papers.forEach { p ->
            val on = p == current
            Column(
                Modifier.clip(NotesShapes.sm).clickable { onSelect(p) }.padding(NotesSpace.xxs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape)
                        .then(if (on) Modifier.border(2.dp, c.ink, CircleShape) else Modifier)
                        .padding(4.dp)
                        .background(Color(PageTemplates.paperColor(p)), CircleShape)
                        .border(1.dp, c.hairline, CircleShape)
                )
                Text(PageTemplates.paperLabel(p), style = NotesTokens.type.caption.copy(color = if (on) c.ink else c.muted))
            }
        }
    }
}

@Composable
private fun SourceTile(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = NotesTokens.colors
    Column(
        Modifier.width(64.dp).clip(NotesShapes.sm).clickable(onClick = onClick).padding(vertical = NotesSpace.xxs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp).clip(NotesShapes.squircle).background(c.canvasSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.ink, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(NotesSpace.xxs))
        Text(label, style = NotesTokens.type.caption.copy(color = c.ink), maxLines = 1)
    }
}
