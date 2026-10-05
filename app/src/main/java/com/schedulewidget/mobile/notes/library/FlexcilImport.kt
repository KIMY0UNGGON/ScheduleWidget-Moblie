package com.schedulewidget.mobile.notes.library

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.schedulewidget.mobile.notes.NoteImport
import com.schedulewidget.mobile.notes.ui.NotesCard
import com.schedulewidget.mobile.notes.ui.NotesPill
import com.schedulewidget.mobile.notes.ui.NotesShapes
import com.schedulewidget.mobile.notes.ui.NotesSpace
import com.schedulewidget.mobile.notes.ui.NotesTokens
import com.schedulewidget.mobile.notes.ui.PillStyle
import java.io.File

/** Flexcil's Android package (its notebooks live in its private storage, so we import its exports). */
internal const val FLEXCIL_PACKAGE = "com.flexcil.flexcilnote"

/** ".flex/.flx 노트 가져오기": how to export notebooks from the original notes app, with the picker and a shortcut into it. */
@Composable
internal fun FlexcilGuideDialog(onDismiss: () -> Unit, onPickFile: () -> Unit) {
    val context = LocalContext.current
    val c = NotesTokens.colors
    val launch = remember { context.packageManager.getLaunchIntentForPackage(FLEXCIL_PACKAGE) }
    NotesDialog(
        onDismissRequest = onDismiss,
        title = ".flex/.flx 노트 가져오기",
        confirmButton = { NotesPill("닫기", onClick = onDismiss, style = PillStyle.Soft, small = true) },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(NotesSpace.sm)) {
            Text(
                "원본 노트 앱의 노트는 그 앱 안에 저장돼 있어서 바로 읽을 수 없어요. 원본 노트 앱의 백업·내보내기 메뉴로 아래 방법 중 하나를 따라 내보내 주세요.",
                style = NotesTokens.type.body.copy(color = c.muted),
            )
            GuideStep(
                1, "원본 노트 복원 (추천)",
                "원본 노트 앱 설정 → 백업 → .flex 파일 만들기 → 여기서 ‘파일 선택’. 노트별 폴더·페이지 순서·빈 페이지·필기를 복원합니다. 가져온 필기는 수정하거나 지울 수 있어요.",
            )
            GuideStep(
                2, "문서 하나만 가져오기",
                "원본 노트 앱에서 문서를 .flx 원본으로 내보내 ‘파일 선택’으로 가져오세요. PDF 배경과 필기를 별도로 복원하고 원본 .flx도 보관합니다.",
            )
            Text("PDF로 내보낸 파일도 열 수 있습니다. PDF에 합쳐진 기존 필기는 별도로 지우거나 선택할 수 없어요.", style = NotesTokens.type.caption)
            Row(horizontalArrangement = Arrangement.spacedBy(NotesSpace.xs), modifier = Modifier.fillMaxWidth().padding(top = NotesSpace.xxs)) {
                NotesPill("파일 선택", onClick = { onDismiss(); onPickFile() }, modifier = Modifier.weight(1f))
                if (launch != null) {
                    NotesPill(
                        "원본 노트 앱 열기",
                        onClick = {
                            onDismiss()
                            runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        },
                        style = PillStyle.Outline,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** A numbered step on a soft card: ink number dot, title, muted body. */
@Composable
private fun GuideStep(number: Int, title: String, body: String) {
    val c = NotesTokens.colors
    NotesCard(featured = true, shape = NotesShapes.sm, padding = PaddingValues(NotesSpace.md), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(24.dp).clip(NotesShapes.full).background(c.ink), contentAlignment = Alignment.Center) {
                Text(number.toString(), style = NotesTokens.type.label.copy(color = c.onInk))
            }
            Spacer(Modifier.width(NotesSpace.sm))
            Column(Modifier.weight(1f)) {
                Text(title, style = NotesTokens.type.link)
                Text(body, style = NotesTokens.type.bodySm.copy(color = c.muted), modifier = Modifier.padding(top = NotesSpace.xxs))
            }
        }
    }
}

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
