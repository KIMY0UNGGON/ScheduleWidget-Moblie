package com.schedulewidget.mobile.notes

// Contract between the notes editor/storage (NoteStore, NoteEditorScreen) and the library/import side
// (NoteImport, NotesLibraryScreen). Each side fills in its own bodies; signatures stay as declared.

/** One notebook: an imported PDF / converted PPT·Word / blank note, with handwriting on its pages. */
data class NoteMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int,
    /** "pdf", "pptx", "docx", "image" or "blank". */
    val source: String,
    /** Optional folder name for grouping in the library; null = top level. */
    val folder: String? = null,
)

// NoteStore (storage, notes/NoteStore.kt) and NoteEditorScreen (notes/NoteEditorScreen.kt) live in their own files.
// NoteImport (notes/NoteImport.kt, converters in notes/importer/) and NotesLibraryScreen (notes/library/) likewise.
