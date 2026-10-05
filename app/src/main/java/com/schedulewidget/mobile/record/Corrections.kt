package com.schedulewidget.mobile.record

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.data.SttCorrection

/** Adds "from → to" to the auto-correction list (AppData.stt.corrections), replacing an entry with the same "from". */
fun addCorrection(context: android.content.Context, from: String, to: String) {
    val f = from.trim()
    if (f.isEmpty() || f == to.trim()) return
    Repository.get(context).update { d ->
        val list = d.stt.corrections.filter { it.from != f } + SttCorrection(f, to.trim())
        d.copy(stt = d.stt.copy(corrections = list))
    }
}

/**
 * "자동 고치기 단어": pairs applied to every new transcription (and, on demand, to an existing one). Add, edit (tap)
 * and delete. Used in the 수업 녹음 settings and from the transcript screen.
 */
@Composable
fun CorrectionsEditor(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val data by repo.data.collectAsStateWithLifecycle()
    val list = data.stt.corrections
    // null = closed; index -1 = new entry.
    var editing by remember { mutableStateOf<Int?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("자동 고치기 단어", style = MaterialTheme.typography.bodyLarge)
        Text(
            "받아쓰기에서 자주 틀리는 말을 적어 두면 다음 받아쓰기부터 자동으로 고쳐요 (예: 미분 방정 → 미분방정식).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        list.forEachIndexed { i, c ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { editing = i }.padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(c.from, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(c.to.ifEmpty { "(지우기)" }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
                IconButton(onClick = {
                    repo.update { d -> d.copy(stt = d.stt.copy(corrections = d.stt.corrections.filterIndexed { j, _ -> j != i })) }
                }) { Icon(Icons.Outlined.Delete, "삭제") }
            }
        }
        OutlinedButton(onClick = { editing = -1 }) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("단어 추가")
        }
    }
    editing?.let { i ->
        val old = list.getOrNull(i)
        CorrectionDialog(old, onDismiss = { editing = null }) { from, to ->
            repo.update { d ->
                val current = d.stt.corrections
                val updated = if (old != null && i in current.indices) current.toMutableList().also { it[i] = SttCorrection(from, to) }
                else current.filter { it.from != from } + SttCorrection(from, to)
                d.copy(stt = d.stt.copy(corrections = updated))
            }
            editing = null
        }
    }
}

@Composable
private fun CorrectionDialog(old: SttCorrection?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var from by remember { mutableStateOf(old?.from.orEmpty()) }
    var to by remember { mutableStateOf(old?.to.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (old == null) "자동 고치기 추가" else "자동 고치기 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(from, { from = it }, label = { Text("받아쓰기에 나온 말") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(to, { to = it }, label = { Text("이렇게 고치기") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(from.trim(), to.trim()) }, enabled = from.isNotBlank()) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** The corrections list in a dialog (from the transcript screen), with "이 받아쓰기에 지금 적용". */
@Composable
fun CorrectionsDialog(onDismiss: () -> Unit, onApplyNow: (() -> Unit)?) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("자동 고치기 단어") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { CorrectionsEditor() } },
        confirmButton = {
            if (onApplyNow != null) TextButton(onClick = onApplyNow) { Text("이 받아쓰기에 지금 적용") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
