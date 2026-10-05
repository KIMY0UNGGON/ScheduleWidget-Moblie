package com.schedulewidget.mobile.record

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp

@Composable
internal fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    count: Int,
    current: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onReplace: (String, Boolean) -> Unit,
    onReplaceAll: (String, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var with by remember { mutableStateOf("") }
    var alsoFuture by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, onQuery, singleLine = true, placeholder = { Text("찾을 말") }, modifier = Modifier.weight(1f))
            Text(
                if (query.isEmpty()) "" else if (count == 0) "0" else "${current + 1}/$count",
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp),
            )
            IconButton(onClick = onPrev, enabled = count > 0) { Icon(Icons.Filled.KeyboardArrowUp, "이전") }
            IconButton(onClick = onNext, enabled = count > 0) { Icon(Icons.Filled.KeyboardArrowDown, "다음") }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "닫기") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(with, { with = it }, singleLine = true, placeholder = { Text("바꿀 말") }, modifier = Modifier.weight(1f))
            TextButton(onClick = { onReplace(with, alsoFuture) }, enabled = count > 0) { Text("바꾸기") }
            TextButton(onClick = { onReplaceAll(with, alsoFuture) }, enabled = count > 0) { Text("모두") }
        }
        Row(Modifier.clickable { alsoFuture = !alsoFuture }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(alsoFuture, { alsoFuture = it })
            Text("앞으로 받아쓰기에도 자동으로 고치기", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Rename a speaker (everywhere), or move this paragraph to another speaker. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeakerDialog(
    row: Row,
    names: Map<Int, String>,
    speakers: List<Int>,
    onDismiss: () -> Unit,
    onRename: (Int, String) -> Unit,
    onMove: (Int) -> Unit,
) {
    val sp = row.speaker ?: return
    var name by remember { mutableStateOf(names[sp].orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(speakerLabel(sp, names)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("이름 바꾸기 (이 화자의 모든 문단)", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("예: 교수님") }, modifier = Modifier.fillMaxWidth())
                Text("이 문단 화자 바꾸기", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (speakers + (speakers.maxOrNull()?.plus(1) ?: 0)).distinct().forEach { other ->
                        val isNew = other !in speakers
                        FilterChip(
                            selected = other == sp, onClick = { if (other != sp) onMove(other) },
                            label = { Text(if (isNew) "새 화자" else speakerLabel(other, names)) },
                            leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(speakerColor(other))) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRename(sp, name) }) { Text("이름 저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
