package com.schedulewidget.mobile.notes

import android.content.Context
import com.schedulewidget.mobile.notes.ink.InkJson
import com.schedulewidget.mobile.notes.ink.PageInk
import java.io.IOException

/** Ink file reads, writes, and cleanup for notebook storage. */
internal object NoteStoreInk {
    fun load(context: Context, id: String, uid: String): PageInk {
        val file = NoteStoreFileIO.inkFile(context, id, uid)
        if (!file.exists()) return PageInk.EMPTY
        return try {
            InkJson.decodeInk(file.readText())
        } catch (e: Exception) {
            throw IOException("저장된 필기를 읽을 수 없어요. 원본 파일은 그대로 남아 있어요.", e)
        }
    }

    fun save(context: Context, id: String, uid: String, ink: PageInk) {
        NoteStore.ensureNotDeleted(id)
        val file = NoteStoreFileIO.inkFile(context, id, uid)
        if (ink.isEmpty) {
            if (file.exists() && !file.delete()) throw IOException("could not delete ${file.name}")
            return
        }
        file.parentFile?.mkdirs()
        NoteStoreFileIO.writeAtomic(file, InkJson.encodeInk(ink).toByteArray())
    }

    fun prune(context: Context, id: String) {
        val note = NoteStore.load(context, id) ?: return
        val keep = note.pages.mapTo(HashSet()) { "${it.uid}.json" }
        NoteStoreFileIO.inkDir(context, id).listFiles().orEmpty().forEach { if (it.name !in keep) it.delete() }
        val sources = note.pages.mapNotNullTo(HashSet()) { it.src }
        NoteStoreFileIO.dir(context, id)
            .listFiles { file -> file.isFile && file.name.startsWith("src-") && file.name.endsWith(".pdf") }
            .orEmpty().forEach { if (it.name !in sources) it.delete() }
    }
}
