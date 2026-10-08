package com.schedulewidget.mobile.ui

import androidx.compose.runtime.Composable

/** Main mini screen: calendar board + draggable pets + music bar + menu. */
@Composable
fun MiniScreen(navigate: (Route) -> Unit) = MiniBoardScreen(navigate)

@Composable
fun MiniScreen(navigate: (Route) -> Unit, onNotes: (() -> Unit)?) = MiniBoardScreen(navigate, onNotes)
