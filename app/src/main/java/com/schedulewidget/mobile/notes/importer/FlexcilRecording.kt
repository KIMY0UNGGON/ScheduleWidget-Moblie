package com.schedulewidget.mobile.notes.importer

import java.io.File

/** One Flexcil recording; [audio] exists only while the import callback is running. */
class FlexcilRecording(
    val sourceKey: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val documentKeys: Set<String>,
    val audio: File,
)
