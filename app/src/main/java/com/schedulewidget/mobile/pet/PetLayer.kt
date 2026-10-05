package com.schedulewidget.mobile.pet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.schedulewidget.mobile.data.Repository

/** Animated character drawn next to the mini calendar. Tap plays a reaction (jump / wave). */
@Composable
fun PetLayer(modifier: Modifier = Modifier, heightDp: Int = 96) {
    val context = LocalContext.current
    val data by remember { Repository.get(context) }.data.collectAsState()
    if (!data.miniCharacterVisible || data.miniFirstPetHidden) return
    val height = (heightDp * data.miniCharacterScale.coerceIn(50, 300) / 100f).dp
    CharacterSprite(
        data.characterManifest, data.characterAnimation, height, modifier,
        flipped = data.miniFirstPetFlipped,
    )
}
