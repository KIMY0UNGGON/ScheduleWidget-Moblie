package com.schedulewidget.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 노트 options. */
@Serializable
data class NotesSettings(
    @SerialName("Enabled") val enabled: Boolean = true,
    // How PPT / Word files become pages: "drive" (Google Drive converts, exact), "offline" (on the phone, simplified),
    // "auto" (Drive when signed in and online, otherwise offline).
    @SerialName("Convert") val convert: String = "auto",
    // Finger draws too (off = finger scrolls/zooms, only the S Pen / stylus draws).
    @SerialName("FingerDraws") val fingerDraws: Boolean = false,
    // Flexcil-style pen slots shown in the editor's tool strip; empty = NotesPens.defaults (notes/editor).
    @SerialName("Pens") val pens: List<PenPreset> = emptyList(),
    // PenPreset.id of the slot last used.
    @SerialName("SelectedPen") val selectedPen: String = "",
)

/** One pen slot of the notes editor (like Flexcil's pen case): its kind, colour and width. */
@Serializable
data class PenPreset(
    @SerialName("Id") val id: String = "",
    // notes.ink.Tool constant: PEN (fountain, pressure), BALLPOINT, PENCIL, BRUSH, HIGHLIGHTER.
    @SerialName("Tool") val tool: Int = 0,
    // ARGB.
    @SerialName("Color") val color: Int = 0xFF141414.toInt(),
    // Nominal width in page points.
    @SerialName("Width") val width: Float = 1.6f,
)
