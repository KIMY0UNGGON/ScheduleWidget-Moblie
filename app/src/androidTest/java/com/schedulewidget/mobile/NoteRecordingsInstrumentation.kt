package com.schedulewidget.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.notes.NoteStore
import com.schedulewidget.mobile.notes.ink.PageInk
import com.schedulewidget.mobile.notes.ink.Stroke
import com.schedulewidget.mobile.record.Recorder
import com.schedulewidget.mobile.record.RecordingMeta
import com.schedulewidget.mobile.record.Recordings
import com.schedulewidget.mobile.stt.Transcriber
import com.schedulewidget.mobile.stt.Transcript
import com.schedulewidget.mobile.stt.TranscriptSegment
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream

/** Real PDF-editor UI regression for explicit note links and legacy page-stroke recording links. */
class NoteRecordingsInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val repo = Repository.get(targetContext)
        val original = repo.data.value
        val previousOpenTranscript = Recorder.openTranscript.value
        val runId = System.nanoTime().toString(36)
        val title = "Note recordings $runId"
        val scratch = File(targetContext.cacheDir, "note-recordings-$runId").apply { mkdirs() }
        val pdf = File(scratch, "fixture.pdf")
        val audioFiles = mutableListOf<File>()
        val recordingIds = mutableListOf<String>()
        var noteId: String? = null
        var activity: Activity? = null
        var screenshot: File? = null
        var failure: Throwable? = null
        val results = mutableListOf<String>()

        try {
            check(!Recorder.state.value.isRecording) { "Refusing to disturb an active recording" }
            repo.update { it.copy(notes = it.notes.copy(enabled = true), stt = it.stt.copy(enabled = false)) }
            Recorder.openTranscript.value = null
            createPdf(pdf)
            val noteMeta = NoteStore.createFromPdf(targetContext, pdf, title, "pdf")
            noteId = noteMeta.id
            val note = checkNotNull(NoteStore.load(targetContext, noteMeta.id))
            check(note.pages.size == 2) { "Expected two pages in the PDF notebook" }

            val now = System.currentTimeMillis()
            val explicit = createRecording("Explicit recording $runId", now, note.id, audioFiles, recordingIds)
            val legacy = createRecording("Legacy page recording $runId", now + 1, null, audioFiles, recordingIds)
            createRecording("Unrelated recording $runId", now + 2, null, audioFiles, recordingIds)
            NoteStore.saveInk(
                targetContext,
                note.id,
                note.pages[1].uid,
                PageInk(strokes = listOf(Stroke(
                    id = 1, color = Color.BLACK, width = 1f,
                    pts = floatArrayOf(12f, 12f, 1f, 30f, 30f, 1f), rec = legacy.id,
                ))),
            )
            Transcriber.save(
                targetContext,
                Transcript(explicit.id, "local-fixture", now, listOf(TranscriptSegment(0, 1200, null, "fixture transcript"))),
            )
            Recordings.notifyChanged()

            val reloaded = Recordings.list(targetContext).firstOrNull { it.id == explicit.id }
            check(reloaded?.meta?.noteId == note.id) { "Recording noteId did not persist in its sidecar" }
            check(!repo.data.value.stt.enabled) { "The test must leave recording disabled" }
            results += "PASS: noteId sidecar persists; STT recording remains disabled"

            activity = startActivitySync(
                Intent(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra(MainActivity.EXTRA_ROUTE, "Notes"),
            )
            await("PDF notebook in Notes library") { nodes().any { it.text?.toString() == title } }
            clickText(title)
            await("PDF editor") { nodes().any { it.contentDescription?.toString() == "메뉴" } }
            clickDescription("메뉴")
            await("note recordings action") { nodes().any { it.text?.toString() == "노트 녹음 (2)" } }
            clickText("노트 녹음 (2)")
            await("note recordings list") { nodes().any { it.text?.toString() == explicit.title } }
            check(nodes().any { it.text?.toString() == legacy.title }) { "Legacy second-page ink link was not shown" }
            check(nodes().none { it.text?.toString() == "Unrelated recording $runId" }) { "An unrelated recording was shown" }
            check(nodes().any { it.text?.toString() == "녹음 기능을 켜면 시작할 수 있어요" }) {
                "The linked list should remain accessible while new recording is disabled"
            }
            results += "PASS: explicit and second-page legacy links are listed; unrelated recording is filtered"

            clickText(explicit.title)
            await("expanded audio controls") { nodes().any { it.contentDescription?.toString() == "재생" } }
            check(nodes().any { it.contentDescription?.toString() == "재생" && hasClickableParent(it) }) {
                "Play control is missing or disabled"
            }
            check(nodes().any { it.text?.toString() == "파일로 저장" && hasClickableParent(it) }) {
                "M4A Save As control is missing or disabled"
            }
            check(nodes().any { it.text?.toString() == "받아쓰기 보기" }) { "Saved local transcript is not available" }
            val image = checkNotNull(uiAutomation.takeScreenshot())
            screenshot = File(targetContext.cacheDir, "note-recordings-$runId.png")
            FileOutputStream(screenshot).use { out ->
                check(image.compress(Bitmap.CompressFormat.PNG, 100, out)) { "Could not write UI screenshot" }
            }
            image.recycle()
            results += "PASS: playback controls and M4A Save As are visible"

            clickText("받아쓰기 보기")
            await("local transcript screen") { nodes().any { it.text?.toString() == "fixture transcript" } }
            clickDescription("더보기")
            await("transcript TXT export option") { nodes().any { it.text?.toString() == "텍스트 파일로 저장" } }
            results += "PASS: saved transcript is viewable and its TXT export option is present"
        } catch (e: Throwable) {
            failure = e
        } finally {
            activity?.let { runCatching { runOnMainSync { it.finish() } } }
            recordingIds.forEach { id -> runCatching { Transcriber.delete(targetContext, id) } }
            audioFiles.forEach { audio ->
                runCatching { File(audio.parentFile, audio.nameWithoutExtension + ".json").delete() }
                runCatching { audio.delete() }
            }
            noteId?.let { id -> runCatching { runBlocking { NoteStore.delete(targetContext, id) } } }
            Recordings.notifyChanged()
            Recorder.openTranscript.value = previousOpenTranscript
            repo.update { original }
            pdf.delete()
            scratch.deleteRecursively()
        }

        finish(
            if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply {
                putString("stream", buildString {
                    results.forEach { append(it).append('\n') }
                    screenshot?.let { append("SCREENSHOT: ").append(it.absolutePath).append('\n') }
                    failure?.let { append(it.stackTraceToString()).append('\n') }
                })
            },
        )
    }

    private fun createPdf(file: File) {
        val pdf = PdfDocument()
        try {
            repeat(2) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(100, 120, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText("fixture ${index + 1}", 8f, 20f, Paint())
                pdf.finishPage(page)
            }
            FileOutputStream(file).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    private fun createRecording(
        title: String,
        createdAt: Long,
        noteId: String?,
        audioFiles: MutableList<File>,
        recordingIds: MutableList<String>,
    ): RecordingMeta {
        val audio = Recordings.newFile(targetContext, createdAt)
        audioFiles += audio
        audio.writeBytes("fixture audio; playback is never started".toByteArray())
        val meta = RecordingMeta(
            id = audio.nameWithoutExtension, title = title, createdAt = createdAt,
            durationMs = 1200, file = audio.name, noteId = noteId,
        )
        recordingIds += meta.id
        Recordings.save(targetContext, meta)
        return meta
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            waitForIdleSync()
            if (condition()) return
            Thread.sleep(80)
        }
        error("Timed out waiting for $label")
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            out += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        uiAutomation.rootInActiveWindow?.let(::walk)
        return out
    }

    private fun clickText(text: String) = click(checkNotNull(nodes().firstOrNull { it.text?.toString() == text }) { "Missing text: $text" })

    private fun clickDescription(description: String) = click(
        checkNotNull(nodes().firstOrNull { it.contentDescription?.toString() == description }) { "Missing control: $description" },
    )

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "No clickable parent for ${node.text ?: node.contentDescription}" }
    }

    private fun hasClickableParent(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return target?.isEnabled == true
    }
}
