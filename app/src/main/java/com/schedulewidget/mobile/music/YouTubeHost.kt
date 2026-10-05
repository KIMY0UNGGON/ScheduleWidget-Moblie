package com.schedulewidget.mobile.music

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Invisible WebView hosting the YouTube IFrame player. Placed once at the activity root. */
@Composable
internal fun YouTubeHostContent() {
    val requested by YouTubeEngine.requested.collectAsStateWithLifecycle()
    if (!requested) return
    // The IFrame API wants a >=200x200 viewport; keep it that size but off-screen and transparent.
    // Re-created (new WebView attached) when the engine replaces a crashed WebView.
    val generation by YouTubeEngine.generation.collectAsStateWithLifecycle()
    androidx.compose.runtime.key(generation) {
        AndroidView(
            factory = { ctx -> YouTubeEngine.detach(); YouTubeEngine.view(ctx) },
            onRelease = { v -> (v.parent as? android.view.ViewGroup)?.removeView(v) },
            modifier = Modifier.requiredSize(200.dp).offset(x = (-4000).dp).alpha(0f),
        )
    }
}
