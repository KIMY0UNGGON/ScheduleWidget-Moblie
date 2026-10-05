package com.schedulewidget.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import com.schedulewidget.mobile.notes.NoteStoreExport
import com.schedulewidget.mobile.notes.NoteStore
import com.schedulewidget.mobile.notes.ink.InkJson
import com.schedulewidget.mobile.notes.ink.PageInfo
import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.notes.ink.TextBox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/** Checks that damaged note metadata cannot redirect store operations outside its own notebook. */
class NoteStoreSafetyInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        var failure: Throwable? = null
        try {
            runBlocking {
                val scratch = File(targetContext.cacheDir, "note-store-safety-${System.nanoTime()}")
                val files = File(scratch, "files").apply { mkdirs() }
                val context = object : ContextWrapper(targetContext) {
                    override fun getFilesDir() = files
                    override fun getApplicationContext() = this
                }
                val first = NoteStore.createBlank(context, "First", "plain")
                val second = NoteStore.createBlank(context, "Second", "plain")
                val damaged = NoteStore.createBlank(context, "Damaged metadata", "plain")
                try {
                    val firstMeta = File(files, "notes/${first.id}/meta.json")
                    val original = checkNotNull(NoteStore.load(context, first.id))
                    firstMeta.writeText(InkJson.encodeNote(original.copy(id = second.id)))

                    val visibleIds = NoteStore.list(context).map { it.id }
                    check(visibleIds.size == 2 && visibleIds.toSet() == setOf(second.id, damaged.id)) {
                        "Metadata with a mismatched id was exposed as another notebook"
                    }
                    NoteStore.rename(context, first.id, "Redirected rename")
                    check(NoteStore.load(context, second.id)?.title == "Second") {
                        "A mismatched metadata id redirected rename to another notebook"
                    }

                    val victim = File(files, "victim.txt").apply { writeText("keep") }
                    check(runCatching { NoteStore.delete(context, "../victim.txt") }.isFailure) {
                        "A path-like notebook id was accepted for deletion"
                    }
                    check(victim.readText() == "keep") { "A rejected notebook id changed a file outside notes/" }

                    val victimInk = File(files, "victim.json").apply { writeText("keep") }
                    check(runCatching { NoteStore.saveInk(context, second.id, "../../../victim", PageInk.EMPTY) }.isFailure) {
                        "A path-like page uid was accepted for an ink write"
                    }
                    check(runCatching { NoteStore.loadInk(context, second.id, "../../../victim") }.isFailure) {
                        "A path-like page uid was accepted for an ink read"
                    }
                    check(victimInk.readText() == "keep") { "A rejected page uid changed a file outside the notebook" }

                    val brokenPdf = File(scratch, "broken.pdf").apply { writeText("not a PDF") }
                    check(runCatching { NoteStore.addSourcePdf(context, second.id, brokenPdf) }.isFailure) {
                        "A damaged PDF was accepted as a page source"
                    }
                    val sources = File(files, "notes/${second.id}").listFiles { file ->
                        file.name.startsWith("src-") && file.name.endsWith(".pdf")
                    }.orEmpty()
                    check(sources.isEmpty()) { "A rejected PDF left a permanent page source" }

                    val missingPdfPage = checkNotNull(NoteStore.load(context, second.id)).pages.single().copy(
                        kind = PageInfo.KIND_PDF, pdf = 0,
                    )
                    NoteStore.savePages(context, second.id, listOf(missingPdfPage))
                    val output = File(scratch, "existing-output.pdf").apply { writeText("keep existing export") }
                    val before = output.readBytes()
                    check(runCatching { NoteStore.exportPdf(context, second.id, Uri.fromFile(output)) }.isFailure) {
                        "An export with a missing PDF source reported success"
                    }
                    check(output.readBytes().contentEquals(before)) {
                        "A failed notebook export truncated the user's existing output"
                    }

                    val pageOutput = File(scratch, "existing-page-output.pdf").apply { writeText("keep page export") }
                    val pageBefore = pageOutput.readBytes()
                    check(runCatching {
                        NoteStore.exportPages(context, second.id, listOf(missingPdfPage), { PageInk.EMPTY }, Uri.fromFile(pageOutput))
                    }.isFailure) { "An export of a page with a missing PDF source reported success" }
                    check(pageOutput.readBytes().contentEquals(pageBefore)) {
                        "A failed page export truncated the user's existing output"
                    }

                    val cancelledOutput = File(scratch, "cancelled-output.pdf").apply { writeText("keep cancelled export") }
                    val cancelledBefore = cancelledOutput.readBytes()
                    val tempNamesBefore = context.cacheDir.listFiles { file -> file.name.startsWith("notes-export-") }
                        .orEmpty().mapTo(HashSet()) { it.name }
                    val rendering = CompletableDeferred<Unit>()
                    val neverComplete = CompletableDeferred<Unit>()
                    val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    val exportJob = exportScope.launch {
                        NoteStoreExport.write(context, Uri.fromFile(cancelledOutput)) {
                            rendering.complete(Unit)
                            neverComplete.await()
                        }
                    }
                    try {
                        withTimeout(5_000) { rendering.await() }
                        exportJob.cancelAndJoin()
                    } finally {
                        exportJob.cancel()
                        exportScope.cancel()
                        neverComplete.cancel()
                    }
                    check(cancelledOutput.readBytes().contentEquals(cancelledBefore)) {
                        "A cancelled notebook export changed the user's existing output"
                    }
                    val tempNamesAfter = context.cacheDir.listFiles { file -> file.name.startsWith("notes-export-") }
                        .orEmpty().mapTo(HashSet()) { it.name }
                    check(tempNamesAfter == tempNamesBefore) { "A cancelled notebook export left its staging PDF" }

                    val secondMeta = File(files, "notes/${second.id}/meta.json")
                    val secondNote = checkNotNull(NoteStore.load(context, second.id))
                    secondMeta.writeText(InkJson.encodeNote(secondNote.copy(
                        pages = secondNote.pages.map { it.copy(src = "../../victim.pdf") },
                    )))
                    check(NoteStore.load(context, second.id) == null) {
                        "A notebook with a path-like PDF source was accepted"
                    }

                    val damagedNote = checkNotNull(NoteStore.load(context, damaged.id))
                    val uid = damagedNote.pages.single().uid
                    val ink = PageInk(texts = listOf(TextBox(1, 10f, 10f, "keep")))
                    NoteStore.saveInk(context, damaged.id, uid, ink)
                    val damagedMeta = File(files, "notes/${damaged.id}/meta.json")
                    damagedMeta.writeText("{")
                    NoteStore.pruneInk(context, damaged.id)
                    check(NoteStore.loadInk(context, damaged.id, uid) == ink) {
                        "Pruning with corrupt metadata deleted saved handwriting"
                    }
                    damagedMeta.delete()
                    NoteStore.pruneInk(context, damaged.id)
                    check(NoteStore.loadInk(context, damaged.id, uid) == ink) {
                        "Pruning with missing metadata deleted saved handwriting"
                    }
                } finally {
                    runCatching { NoteStore.delete(context, first.id) }
                    runCatching { NoteStore.delete(context, second.id) }
                    runCatching { NoteStore.delete(context, damaged.id) }
                    scratch.deleteRecursively()
                }
            }
        } catch (e: Throwable) {
            failure = e
        }
        finish(
            if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", failure?.stackTraceToString() ?: "PASS: notebook metadata cannot redirect file paths\n") },
        )
    }
}
