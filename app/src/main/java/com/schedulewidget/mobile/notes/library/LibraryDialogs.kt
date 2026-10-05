package com.schedulewidget.mobile.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.notes.ink.Template
import com.schedulewidget.mobile.notes.library.FieldLabel
import com.schedulewidget.mobile.notes.library.NotesDialog
import com.schedulewidget.mobile.notes.library.NotesField
import com.schedulewidget.mobile.notes.library.NotesSheet
import com.schedulewidget.mobile.notes.library.SquircleTile
import com.schedulewidget.mobile.notes.library.TemplatePreview
import com.schedulewidget.mobile.notes.library.TileRow
import com.schedulewidget.mobile.notes.ui.NotesPill
import com.schedulewidget.mobile.notes.ui.NotesSegmented
import com.schedulewidget.mobile.notes.ui.NotesShapes
import com.schedulewidget.mobile.notes.ui.NotesSpace
import com.schedulewidget.mobile.notes.ui.NotesTokens
import com.schedulewidget.mobile.notes.ui.PillStyle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- add sheet ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddSheet(onDismiss: () -> Unit, onNewNote: () -> Unit, onPickFiles: () -> Unit, onFlexcil: () -> Unit, onPickTree: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Hide with the sheet's animation, then run the action (pickers open over the closed sheet).
    fun pick(action: () -> Unit) {
        scope.launch { runCatching { state.hide() } }.invokeOnCompletion { onDismiss(); action() }
    }
    NotesSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.fillMaxWidth().padding(start = NotesSpace.lg, end = NotesSpace.lg, bottom = NotesSpace.lg)) {
            Text("추가", style = NotesTokens.type.h3, modifier = Modifier.padding(top = NotesSpace.xs, bottom = NotesSpace.md))
            AddRow(Icons.Outlined.EditNote, "새 노트", "무지·줄·모눈·점 속지에 바로 필기") { pick(onNewNote) }
            AddRow(Icons.Outlined.FileOpen, "파일 가져오기", "PDF·PPT·Word·사진, 여러 개도 한 번에") { pick(onPickFiles) }
            AddRow(Icons.Outlined.Draw, ".flex/.flx 노트 가져오기", "필기 포함 PDF나 .flex 백업·.flx 문서 파일") { pick(onFlexcil) }
            AddRow(Icons.Outlined.DriveFolderUpload, "폴더 통째로 가져오기", "폴더 안의 파일을 폴더 구조대로") { pick(onPickTree) }
        }
    }
}

@Composable
private fun AddRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(NotesShapes.sm).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = NotesSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SquircleTile(icon, size = 48.dp)
        Spacer(Modifier.width(NotesSpace.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = NotesTokens.type.body.copy(fontWeight = NotesTokens.type.title.fontWeight))
            Text(subtitle, style = NotesTokens.type.bodySm.copy(color = NotesTokens.colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}


// ---- dialogs ----

@Composable
internal fun NameDialog(title: String, initial: String, confirm: String, placeholder: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val name = text.trim()
    NotesDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmButton = { NotesPill(confirm, onClick = { onDone(name) }, enabled = name.isNotEmpty(), small = true) },
        dismissButton = { NotesPill("취소", onClick = onDismiss, style = PillStyle.Soft, small = true) },
    ) {
        NotesField(
            value = text, onValueChange = { text = it.take(80) }, placeholder = placeholder, autoFocus = true,
            onImeAction = { if (name.isNotEmpty()) onDone(name) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun MoveDialog(note: NoteMeta, folders: List<String>, onDismiss: () -> Unit, onMove: (String?) -> Unit) {
    val c = NotesTokens.colors
    var choice by remember { mutableStateOf(note.folder) }
    var fresh by rememberSaveable { mutableStateOf("") }
    NotesDialog(
        onDismissRequest = onDismiss,
        title = "폴더로 이동",
        confirmButton = { NotesPill("이동", onClick = { onMove(fresh.trim().ifEmpty { null } ?: choice) }, small = true) },
        dismissButton = { NotesPill("취소", onClick = onDismiss, style = PillStyle.Soft, small = true) },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(NotesSpace.xxs)) {
            (listOf<String?>(null) + folders).forEach { f ->
                val selected = choice == f && fresh.isBlank()
                Row(
                    Modifier.fillMaxWidth().height(48.dp).clip(NotesShapes.full)
                        .background(if (selected) c.canvasSoft else c.canvas)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { choice = f; fresh = "" })
                        .padding(horizontal = NotesSpace.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (f == null) Icons.Outlined.FolderOpen else Icons.Outlined.Folder, null, tint = if (selected) c.ink else c.muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(NotesSpace.sm))
                    Text(f ?: "폴더 없음 (전체)", style = NotesTokens.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (selected) Icon(Icons.Filled.Check, null, tint = c.ink, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(NotesSpace.xs))
            NotesField(
                value = fresh, onValueChange = { fresh = it.take(40) }, placeholder = "새 폴더 이름",
                leadingIcon = Icons.Outlined.CreateNewFolder, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun NewNoteDialog(onDismiss: () -> Unit, onCreate: (title: String, template: String, landscape: Boolean) -> Unit) {
    val c = NotesTokens.colors
    val defaultTitle = remember { "새 노트 " + SimpleDateFormat("M월 d일", Locale.KOREAN).format(Date()) }
    var title by rememberSaveable { mutableStateOf(defaultTitle) }
    var template by rememberSaveable { mutableStateOf(Template.PLAIN) }
    var landscape by rememberSaveable { mutableStateOf(false) }
    val create = { onCreate(title.trim().ifEmpty { defaultTitle }, template, landscape) }
    NotesDialog(
        onDismissRequest = onDismiss,
        title = "새 노트",
        confirmButton = { NotesPill("만들기", onClick = create, small = true) },
        dismissButton = { NotesPill("취소", onClick = onDismiss, style = PillStyle.Soft, small = true) },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            FieldLabel("제목")
            NotesField(value = title, onValueChange = { title = it.take(80) }, placeholder = defaultTitle, onImeAction = create, modifier = Modifier.fillMaxWidth())
            FieldLabel("속지", Modifier.padding(top = NotesSpace.lg))
            TileRow(Template.all) { t, mod ->
                val selected = template == t
                Column(
                    mod.clip(NotesShapes.sm)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { template = t })
                        .padding(vertical = NotesSpace.xxs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Landscape previews get a fixed box height so the row does not jump when the size changes.
                    Box(Modifier.fillMaxWidth().aspectRatio(0.707f), contentAlignment = Alignment.Center) {
                        TemplatePreview(t, landscape, selected, Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(Template.label(t), style = NotesTokens.type.label.copy(color = if (selected) c.ink else c.muted))
                }
            }
            FieldLabel("크기", Modifier.padding(top = NotesSpace.lg))
            NotesSegmented(
                options = listOf(false to "A4 세로", true to "A4 가로"),
                selected = landscape, onSelect = { landscape = it },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
