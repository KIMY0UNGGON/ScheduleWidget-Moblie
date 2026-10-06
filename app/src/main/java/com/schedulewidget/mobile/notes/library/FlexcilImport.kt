package com.schedulewidget.mobile.notes.library

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.schedulewidget.mobile.notes.NoteImport
import com.schedulewidget.mobile.notes.ui.NotesPill
import com.schedulewidget.mobile.notes.ui.NotesSpace
import com.schedulewidget.mobile.notes.ui.NotesTokens
import com.schedulewidget.mobile.notes.ui.PillStyle
import java.io.File

/**
 * The outcome of an import the user should read (ink not imported, failed files), with "열기" (or "폴더 열기" for a
 * restored backup, which [onOpen] shows as its folder) and the diagnostics.
 */
@Composable
internal fun ImportReportDialog(done: NoteImport.Done, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    NotesDialog(
        onDismissRequest = onDismiss,
        title = done.message,
        confirmButton = {
            NotesPill(when { done.importedFolder != null -> "폴더 열기"; done.count > 1 -> "노트 목록"; else -> "열기" }, onClick = { onDismiss(); onOpen() }, small = true)
        },
        dismissButton = { NotesPill("확인", onClick = onDismiss, style = PillStyle.Soft, small = true) },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            done.details?.let { Text(it, style = NotesTokens.type.body.copy(color = NotesTokens.colors.muted)) }
            done.diagnostic?.let { file ->
                NotesPill(
                    "진단 정보 공유", onClick = { shareDiagnostic(context, file) },
                    style = PillStyle.Outline, icon = Icons.Outlined.Share, small = true,
                    modifier = Modifier.padding(top = NotesSpace.md),
                )
            }
        }
    }
}

/** Shares the diagnostic text file (archive entry names and sizes only) so the user can send it to us. */
internal fun shareDiagnostic(context: Context, file: File) {
    val uri = runCatching { FileProvider.getUriForFile(context, context.packageName + ".files", file) }.getOrNull()
    val text = runCatching { file.readText() }.getOrNull()?.take(60_000)
    if (uri == null && text == null) {
        Toast.makeText(context, "진단 정보를 찾을 수 없어요. 다시 가져오기를 시도해 주세요", Toast.LENGTH_SHORT).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "ScheduleWidget 노트 · .flex/.flx 가져오기 진단 정보")
        if (text != null) putExtra(Intent.EXTRA_TEXT, text)
        if (uri != null) {
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    try {
        context.startActivity(Intent.createChooser(send, "진단 정보 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "공유할 앱이 없어요", Toast.LENGTH_SHORT).show()
    }
}
