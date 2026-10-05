package com.schedulewidget.mobile.notes.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import kotlinx.coroutines.launch

internal fun EditorState.animateEndPullBack() {
    endPullJob?.cancel()
    if (endPullOffsetPx == 0f) {
        endPullJob = null
        return
    }
    endPullJob = scope.launch {
        Animatable(endPullOffsetPx).animateTo(0f, spring()) { endPullOffsetPx = value }
        endPullOffsetPx = 0f
        endPullJob = null
    }
}
