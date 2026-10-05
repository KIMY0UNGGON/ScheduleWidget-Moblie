package com.schedulewidget.mobile.ui

/** Top-level screens. MainActivity switches on this; widgets open one via MainActivity.EXTRA_ROUTE. */
enum class Route {
    Mini, Schedules, Playlists, MusicApps, CalendarSync, Pets, Settings, Recordings,
    // notes: the 노트 tab and the full-screen notebook editor (MainActivity keeps the open note's id).
    Notes, NoteEditor, Privacy,
}
