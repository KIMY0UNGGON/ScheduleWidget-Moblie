package com.schedulewidget.mobile.notes.editor

import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.notes.ink.StoredNote

/** Recording ids already linked by handwriting on any page of this notebook. */
internal fun linkedRecordingIds(note: StoredNote, inks: Map<String, PageInk>): Set<String> =
    note.pages.asSequence()
        .flatMap { inks[it.uid]?.strokes.orEmpty().asSequence() }
        .mapNotNull { it.rec?.takeIf(String::isNotBlank) }
        .toSet()

internal fun recordingBelongsToNote(
    recordingId: String,
    recordingNoteId: String?,
    noteId: String,
    inkRecordingIds: Set<String>,
): Boolean = recordingNoteId == noteId || recordingId in inkRecordingIds
