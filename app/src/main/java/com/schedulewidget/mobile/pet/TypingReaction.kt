package com.schedulewidget.mobile.pet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Counts app-local key events; it never receives or stores the typed content. */
object TypingReaction {
    private val _event = MutableStateFlow(0)
    val event: StateFlow<Int> = _event.asStateFlow()

    fun notifyInput() {
        _event.update { if (it == Int.MAX_VALUE) 0 else it + 1 }
    }
}
