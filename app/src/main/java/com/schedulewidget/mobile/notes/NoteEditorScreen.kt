package com.schedulewidget.mobile.notes

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import com.schedulewidget.mobile.notes.editor.EditorLoader
import com.schedulewidget.mobile.notes.ui.NotesTheme

/**
 * The notebook editor (pages, S Pen handwriting, tools). Changes save themselves (about a second after each edit, on
 * pause and on leaving); the library thumbnail refreshes on leaving. Styled by the notes design system ([NotesTheme]).
 */
@Composable
fun NoteEditorScreen(
    noteId: String,
    onBack: () -> Unit,
    registerExitHandler: (((() -> Unit) -> Unit)?) -> Unit = {},
    registerStylusKeyHandler: (((KeyEvent) -> Boolean)?) -> Unit = {},
) {
    BackHandler(onBack = onBack)
    NotesTheme { EditorLoader(noteId, onBack, registerExitHandler, registerStylusKeyHandler) }
}
