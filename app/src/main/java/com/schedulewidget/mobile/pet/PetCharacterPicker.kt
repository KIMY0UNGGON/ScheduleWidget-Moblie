package com.schedulewidget.mobile.pet

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun PetCharacterPicker(
    characters: List<CharacterInfo>,
    active: PetSlot,
    onSelect: (String) -> Unit,
    onPickImage: () -> Unit,
    onDelete: (CharacterInfo) -> Unit,
) {
    Text("캐릭터", style = MaterialTheme.typography.titleMedium)
    val tiles: List<String?> = characters.map { it.manifest } + listOf(null)
    tiles.chunked(3).forEach { rowItems ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            rowItems.forEach { manifest ->
                val selected = if (manifest == null) active.manifest.startsWith(Characters.URI_PREFIX) else manifest == active.manifest
                val select: () -> Unit = { manifest?.let(onSelect) }
                val info = manifest?.let { m -> characters.first { it.manifest == m } }
                CharacterTile(
                    Modifier.weight(1f), selected, info?.name ?: "내 이미지",
                    onClick = if (manifest == null) onPickImage else select,
                    onDelete = if (info?.imported == true) ({ onDelete(info) }) else null,
                ) {
                    when {
                        manifest != null -> CharacterSprite(
                            manifest, if (selected) active.animation else "idle", 72.dp,
                            onTap = select, flipped = selected && active.flipped, animated = false,
                        )
                        selected -> CharacterSprite(active.manifest, "idle", 72.dp, onTap = onPickImage, react = false, flipped = active.flipped, animated = false)
                        else -> Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, Modifier.size(36.dp))
                    }
                }
            }
            repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun CharacterTile(
    modifier: Modifier, selected: Boolean, label: String, onClick: () -> Unit,
    onDelete: (() -> Unit)? = null, content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Box {
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.height(84.dp), contentAlignment = Alignment.BottomCenter) { content() }
                Spacer(Modifier.height(4.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
            if (onDelete != null) IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) {
                Icon(Icons.Default.Close, "삭제", Modifier.size(16.dp))
            }
        }
    }
}
