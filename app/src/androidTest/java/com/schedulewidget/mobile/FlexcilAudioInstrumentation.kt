package com.schedulewidget.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.IntentCompat
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.notes.NoteFlexcilImport
import com.schedulewidget.mobile.notes.NoteImport
import com.schedulewidget.mobile.notes.NoteStore
import com.schedulewidget.mobile.notes.library.NoteFolders
import com.schedulewidget.mobile.record.Recorder
import com.schedulewidget.mobile.record.Recordings
import com.schedulewidget.mobile.stt.Transcriber
import com.schedulewidget.mobile.stt.Transcript
import com.schedulewidget.mobile.stt.TranscriptSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/** Synthetic PDF/AAC fixtures and an optional external backup; no microphone, transcription model or network. */
class FlexcilAudioInstrumentation : Instrumentation() {
    private val docA = "00000000-0000-4000-8000-000000000001"
    private val docB = "00000000-0000-4000-8000-000000000002"
    private val pageA = "00000000-0000-4000-8000-000000000003"
    private val pageB = "00000000-0000-4000-8000-000000000004"
    private val strokeA = "00000000-0000-4000-8000-000000000005"
    private val strokeB = "00000000-0000-4000-8000-000000000006"
    private val recordingKey = "00000000-0000-4000-8000-000000000007"
    private val unknownDoc = "00000000-0000-4000-8000-000000000099"
    private val epochSeconds = 1_800_000_000.0
    private var realFixture: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        realFixture = arguments?.getString("realFixture")?.takeIf { it.isNotBlank() }
        start()
    }

    override fun onStart() {
        val runId = System.nanoTime().toString(36)
        val root = File(targetContext.cacheDir, "flex-audio-test-$runId").apply { mkdirs() }
        val repo = Repository.get(targetContext)
        val original = repo.data.value
        val originalTranscript = Recorder.openTranscript.value
        val priorNotes = NoteStore.list(targetContext).map { it.id }.toSet()
        val priorAudio = Recordings.dir(targetContext).listFiles().orEmpty().map { it.name }.toSet()
        val results = mutableListOf<String>()
        val isolated = mutableListOf<Pair<Context, Set<String>>>()
        var activity: Activity? = null
        var importedFolder: String? = null
        var failure: Throwable? = null
        try {
            check(!Recorder.state.value.isRecording) { "An active recording must not be disturbed" }
            repo.update { it.copy(notes = it.notes.copy(enabled = true), stt = it.stt.copy(enabled = false),
                floatingPet = false, googleCalendar = it.googleCalendar.copy(enabled = false),
                calendar = it.calendar.copy(showEvents = false), petDrive = it.petDrive.copy(enabled = false)) }
            Recorder.openTranscript.value = null
            val pdf = File(root, "source.pdf").also(::createPdf)
            val audio = File(root, "silence.m4a").also(::createSilence)
            val titleA = "Flex audio A $runId"
            val titleB = "Flex audio B $runId"
            val audioTitle = "Flex imported audio $runId"
            val archive = File(root, "synthetic.flex")
            backup(archive, pdf.readBytes(), audio.readBytes(), titleA, titleB, audioTitle)
            val sourceHash = sha256(audio)
            val imported = runBlocking { NoteFlexcilImport.restore(targetContext, archive, archive.name, null,
                File(root, "success-work").apply { mkdirs() }) {} }
            importedFolder = imported.importedFolder
            check(imported.notes.size == 2) { "Expected two restored PDF notebooks" }
            val notes = imported.notes.map { checkNotNull(NoteStore.load(targetContext, it.id)) }
            val items = Recordings.list(targetContext).filter { it.audio.name !in priorAudio }
            check(items.size == 1) { "Expected one original recording, shared rather than duplicated" }
            val item = items.single()
            check(sha256(item.audio) == sourceHash) { "Original audio bytes changed" }
            check(item.meta.noteIds.toSet() == notes.map { it.id }.toSet()) { "Both native document keys must map to their new note ids" }
            check(item.meta.durationMs in 2800L..3500L)
            for (note in notes) {
                check(note.pages.size == 1 && note.pages.single().isPdf)
                val page = note.pages.single()
                PdfRenderer(ParcelFileDescriptor.open(NoteStore.sourceFile(targetContext, note.id, page), ParcelFileDescriptor.MODE_READ_ONLY)).use {
                    check(it.pageCount == 1)
                    it.openPage(0).use { opened -> check(opened.width > 0 && opened.height > 0) }
                }
                val stroke = NoteStore.loadInk(targetContext, note.id, page.uid).strokes.single()
                check(stroke.rec == item.id) { "Imported stroke does not use the generated recording id" }
                check(stroke.recMs == if (note.title == titleA) 1020L else 2100L) { "Absolute Flexcil time was not converted to a recording offset" }
            }
            results += "PASS: two PDF notebooks share one byte-identical original recording; both stroke offsets and noteIds persist"
            verifyPlayback(item.audio)
            val chooser = Recordings.shareIntent(targetContext, item)
            check(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)?.type == "audio/mp4")
            check(Recordings.uri(targetContext, item).scheme == "content")
            results += "PASS: MediaExtractor recognizes AAC; MediaPlayer prepares, seeks to 1020ms, and plays generated silence"

            Transcriber.save(targetContext, Transcript(item.id, "local-fixture", System.currentTimeMillis(),
                listOf(TranscriptSegment(0, 2400, null, "synthetic flex transcript"))))
            Recordings.notifyChanged()
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra(MainActivity.EXTRA_ROUTE, "Notes"))
            val folderLabel = checkNotNull(importedFolder).let(NoteFolders::leaf)
            await("imported backup folder") { nodes().any { it.text?.toString() == folderLabel } }
            clickText(folderLabel)
            await("imported PDF in library") { nodes().any { it.text?.toString() == titleA } }
            clickText(titleA)
            await("PDF editor") { nodes().any { it.contentDescription?.toString() == "메뉴" } }
            clickDescription("메뉴")
            await("automatic recording link") { nodes().any { it.text?.toString() == "노트 녹음 (1)" } }
            clickText("노트 녹음 (1)")
            await("original recording in notebook") { nodes().any { it.text?.toString() == audioTitle } }
            clickText(audioTitle)
            await("recording actions") { nodes().any { it.text?.toString() == "파일로 저장" } }
            check(nodes().any { it.text?.toString() == "파일로 저장" && clickable(it) })
            check(nodes().any { it.contentDescription?.toString() == "재생" && clickable(it) })
            check(nodes().any { it.text?.toString() == "받아쓰기 보기" && clickable(it) })
            clickText("받아쓰기 보기")
            await("saved local transcript") { nodes().any { it.text?.toString() == "synthetic flex transcript" } }
            clickDescription("더보기")
            await("existing TXT export") { nodes().any { it.text?.toString() == "텍스트 파일로 저장" } }
            results += "PASS: imported PDF exposes its original recording, playback, M4A export, local transcript, and TXT export with recording disabled"
            runOnMainSync { checkNotNull(activity).finish() }
            activity = null

            fun isolatedContext(label: String): Context {
                val prefs = mutableSetOf<String>()
                val context = scratchContext(File(root, label), "flex_audio_${runId}_${label}_", prefs)
                isolated += context to prefs
                return context
            }
            val invalidAudio = File(root, "invalid-audio.flex")
            val fakeMp4 = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(24); put("ftyp".toByteArray()); put("mp42".toByteArray()); putInt(0)
                put("mp42".toByteArray()); put("isom".toByteArray())
            }.array()
            backup(invalidAudio, pdf.readBytes(), fakeMp4, titleA, titleB, audioTitle)
            val badContext = isolatedContext("bad-audio")
            val bad = runBlocking { NoteFlexcilImport.restore(badContext, invalidAudio, invalidAudio.name, null,
                File(badContext.cacheDir, "work").apply { mkdirs() }) {} }
            check(bad.notes.size == 2 && !bad.details.isNullOrBlank()) { "Bad audio should warn while retaining valid PDF notebooks" }
            check(Recordings.dir(badContext).listFiles().orEmpty().isEmpty()) { "Invalid audio left recording or temporary files" }
            results += "PASS: invalid audio is skipped with a warning; valid notes remain and no recording assets leak"

            val invalidRefs = File(root, "invalid-refs.flex")
            backup(invalidRefs, pdf.readBytes(), audio.readBytes(), titleA, titleB, audioTitle, invalidRefs = true)
            val refContext = isolatedContext("bad-refs")
            val refs = runBlocking { NoteFlexcilImport.restore(refContext, invalidRefs, invalidRefs.name, null,
                File(refContext.cacheDir, "work").apply { mkdirs() }) {} }
            check(refs.notes.size == 2 && !refs.details.isNullOrBlank())
            check(Recordings.dir(refContext).listFiles().orEmpty().isEmpty()) { "Unresolved declared document refs left an orphan recording" }
            for (meta in refs.notes) {
                val note = checkNotNull(NoteStore.load(refContext, meta.id))
                check(NoteStore.loadInk(refContext, note.id, note.pages.single().uid).strokes.single().rec == null)
            }
            results += "PASS: unknown native document ids are never guessed from outer filenames; unclaimed imported audio is rolled back"

            val cancelContext = isolatedContext("cancel")
            val job = Job()
            var sawDocumentPhase = false
            try {
                runBlocking(job) { NoteFlexcilImport.restore(cancelContext, archive, archive.name, null,
                    File(cancelContext.cacheDir, "work").apply { mkdirs() }) { stage ->
                    if (stage.startsWith("노트 복원 중")) { sawDocumentPhase = true; job.cancel() }
                } }
                error("Cancellation was not propagated")
            } catch (_: CancellationException) { }
            check(sawDocumentPhase) { "Cancellation did not reach the phase after audio staging" }
            check(NoteStore.list(cancelContext).isEmpty())
            check(Recordings.dir(cancelContext).listFiles().orEmpty().isEmpty()) { "Cancelled restore left imported recording assets" }
            results += "PASS: cancellation before the first note removes session-owned audio; committed user data is not touched"

            val partialContext = isolatedContext("cancel-partial")
            val partialJob = Job()
            var documentStages = 0
            try {
                runBlocking(partialJob) { NoteFlexcilImport.restore(partialContext, archive, archive.name, null,
                    File(partialContext.cacheDir, "work").apply { mkdirs() }) { stage ->
                    if (stage.startsWith("노트 복원 중") && ++documentStages == 2) partialJob.cancel()
                } }
            } catch (_: CancellationException) { }
            check(documentStages == 2)
            val completed = NoteStore.list(partialContext).single()
            val kept = Recordings.list(partialContext).single()
            check(kept.meta.noteIds == listOf(completed.id)) { "Partial cancel must finalize only the committed note link" }
            val completedNote = checkNotNull(NoteStore.load(partialContext, completed.id))
            check(NoteStore.loadInk(partialContext, completed.id, completedNote.pages.single().uid).strokes.single().rec == kept.id)
            check(Recordings.dir(partialContext).listFiles().orEmpty().all { it.name == kept.audio.name || it.name == "${kept.id}.json" })
            results += "PASS: partial cancellation keeps the completed notebook and its audio, finalizes noteIds, and leaves no temporary recording files"

            val copyContext = isolatedContext("copy-cancel")
            var copyChecks = 0
            try {
                Recordings.importAudio(copyContext, audio, "synthetic", System.currentTimeMillis(), 3000) {
                    if (++copyChecks == 2) throw CancellationException("synthetic copy cancellation")
                }
                error("Audio copy cancellation was not propagated")
            } catch (_: CancellationException) { }
            check(copyChecks >= 2 && Recordings.dir(copyContext).listFiles().orEmpty().isEmpty())
            results += "PASS: cancellation during audio copying removes its temporary data and publishes no sidecar or audio"

            val fatalContext = isolatedContext("fatal")
            try {
                runBlocking { NoteFlexcilImport.restore(fatalContext, archive, archive.name, null,
                    File(fatalContext.cacheDir, "work").apply { mkdirs() }) { stage ->
                    if (stage.startsWith("노트 복원 중")) throw ForcedImportFailure()
                } }
                error("Injected fatal restore failure was ignored")
            } catch (_: ForcedImportFailure) { }
            check(NoteStore.list(fatalContext).isEmpty() && Recordings.dir(fatalContext).listFiles().orEmpty().isEmpty())
            results += "PASS: fatal failure before any note rolls back only session-owned audio"

            val linkContext = isolatedContext("link-failure")
            val recordingDirectory = Recordings.dir(linkContext)
            var linkStages = 0
            try {
                val failedLinks = runBlocking { NoteFlexcilImport.restore(linkContext, archive, archive.name, null,
                    File(linkContext.cacheDir, "work").apply { mkdirs() }) { stage ->
                    if (stage.startsWith("노트 복원 중") && ++linkStages == 2) {
                        check(recordingDirectory.setWritable(false, false) && !recordingDirectory.canWrite())
                    }
                } }
                check(failedLinks.notes.size == 2 && !failedLinks.details.isNullOrBlank())
                val retained = Recordings.list(linkContext).single()
                check(retained.meta.noteIds.isEmpty()) { "Strict link write failure was reported as successful metadata" }
                check(sha256(retained.audio) == sourceHash)
                check(failedLinks.notes.all { meta ->
                    val note = checkNotNull(NoteStore.load(linkContext, meta.id))
                    NoteStore.loadInk(linkContext, note.id, note.pages.single().uid).strokes.single().rec == retained.id
                })
                results += "PASS: strict link-metadata failure warns without false links and preserves audio needed by restored ink"
            } finally { recordingDirectory.setWritable(true, true) }

            realFixture?.let { path ->
                verifyRealBackup(isolatedContext("real-backup"), File(path), results)
            }
        } catch (e: Throwable) { failure = e }
        finally {
            activity?.let { runCatching { runOnMainSync { it.finish() } } }
            val owned = Recordings.list(targetContext).filter { it.audio.name !in priorAudio }
            owned.forEach { runCatching { Recordings.delete(targetContext, it) } }
            runBlocking {
                NoteStore.list(targetContext).filter { it.id !in priorNotes }.forEach { runCatching { NoteStore.delete(targetContext, it.id) } }
                importedFolder?.let { runCatching { NoteFolders.remove(targetContext, it) } }
                for ((context, prefs) in isolated) {
                    NoteStore.list(context).forEach { runCatching { NoteStore.delete(context, it.id) } }
                    prefs.forEach { targetContext.deleteSharedPreferences(it) }
                }
                NoteStore.writeMutex.lock()
                try { root.deleteRecursively() } finally { NoteStore.writeMutex.unlock() }
            }
            Recorder.openTranscript.value = originalTranscript
            repo.update { original }
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", (results + listOfNotNull(failure?.stackTraceToString())).joinToString("\n") + "\n")
        })
    }

    private class ForcedImportFailure : Error("Synthetic fatal import failure")

    /** Opt-in external fixture: only aggregate results leave the isolated application storage. */
    private fun verifyRealBackup(context: Context, fixture: File, results: MutableList<String>) {
        var checkpoint = "fixture access"
        try {
            check(fixture.isFile) { "External backup fixture is unavailable" }
            val beforeLength = fixture.length()
            val beforeModified = fixture.lastModified()
            checkpoint = "intake"
            val imported = runBlocking { NoteImport.import(context, Uri.fromFile(fixture)) }
            check(imported.isSuccess) { "Original backup intake failed" }
            val notes = NoteStore.list(context).map { checkNotNull(NoteStore.load(context, it.id)) }
            val noteIds = notes.map { it.id }.toSet()
            val recordings = Recordings.list(context)
            checkpoint = "notes=${notes.size}, recordings=${recordings.size}"
            // The external fixture has 47 audio members; one lacks its MP4 moov and is independently unreadable.
            check(notes.size == 45 && recordings.size == 46) { "Original backup note or valid recording aggregate differs" }
            val summary = File(context.cacheDir, "notes-share").listFiles().orEmpty().firstNotNullOfOrNull { report ->
                report.useLines { lines -> lines.lastOrNull { it.startsWith("summary\t") } }
            }
            check(summary?.contains("audioFailed=1") == true) { "Malformed original audio was not reported" }
            var pages = 0
            var strokes = 0
            var timed = 0
            val pdfs = linkedSetOf<File>()
            for (note in notes) {
                check(File(context.filesDir, "notes/${note.id}/original.flx").isFile) { "An original document was not retained" }
                pages += note.pages.size
                for (page in note.pages) {
                    if (page.isPdf) pdfs += NoteStore.sourceFile(context, note.id, page)
                    val ink = NoteStore.loadInk(context, note.id, page.uid).strokes
                    strokes += ink.size
                    for (stroke in ink) if (stroke.rec != null) {
                        val recording = checkNotNull(recordings.firstOrNull { it.id == stroke.rec }) { "Restored ink has a dangling recording link" }
                        check(stroke.recMs in 0L..recording.meta.durationMs)
                        timed++
                    }
                }
            }
            checkpoint = "pages=$pages, strokes=$strokes, timed=$timed"
            check(pages == 1626 && strokes == 6867 && timed == 6807) { "Original backup page, ink, or timing aggregate differs" }
            checkpoint = "links=${recordings.sumOf { it.meta.noteIds.size }}, linkedNotes=${recordings.flatMap { it.meta.noteIds }.toSet().size}"
            check(recordings.sumOf { it.meta.noteIds.size } == 51)
            check(recordings.flatMap { it.meta.noteIds }.toSet().size == 32)
            check(recordings.all { it.meta.noteIds.all(noteIds::contains) && it.sizeBytes > 0 })
            // One original attachment is unused by current pages, but its stored PDF still must be retained and valid.
            for (note in notes) pdfs += NoteStore.dir(context, note.id).listFiles {
                file -> file.isFile && file.extension.equals("pdf", ignoreCase = true)
            }.orEmpty()
            for (pdf in pdfs) {
                checkpoint = "PDF validation (${pdfs.size} sources)"
                PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use {
                    check(it.pageCount > 0)
                    it.openPage(0).use { first -> check(first.width > 0 && first.height > 0) }
                }
            }
            check(pdfs.size == 47) { "Original PDF source aggregate differs" }
            checkpoint = "source/cache preservation"
            check(File(context.cacheDir, "note_import").walkTopDown().none { it.isFile && it.name == "input" }) { "Seekable original backup was copied into cache" }
            check(fixture.length() == beforeLength && fixture.lastModified() == beforeModified) { "Original external backup was modified" }
            results += "PASS: external original backup restored 45 notes / 1626 pages / 47 PDFs / 6867 strokes / 6807 timed strokes / 46 valid recordings / 51 links across 32 notes; one original malformed MP4 warned, originals retained, all PDFs valid, fixture untouched"
        } catch (e: Throwable) {
            // Import exceptions and diagnostics can contain private archive names; do not attach their messages or cause.
            throw AssertionError("External original backup verification failed at $checkpoint (${e.javaClass.simpleName})")
        }
    }

    private fun scratchContext(root: File, prefix: String, prefs: MutableSet<String>): Context = object : ContextWrapper(targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getExternalFilesDir(type: String?): File = File(root, "external/${type ?: "root"}").apply { mkdirs() }
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            val key = prefix + name
            prefs += key
            return targetContext.getSharedPreferences(key, mode)
        }
    }

    private fun createPdf(file: File) {
        val pdf = PdfDocument()
        try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(100, 120, 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawText("synthetic PDF", 5f, 20f, Paint())
            pdf.finishPage(page)
            file.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
    }

    private fun createSilence(file: File) {
        val rate = 44100
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var muxed = false
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2048)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start(); started = true
            var frames = 0
            var inputDone = false
            var outputDone = false
            var track = -1
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (!outputDone) {
                check(SystemClock.uptimeMillis() < deadline) { "AAC silence encoder timed out" }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(index)).apply { clear() }
                        val count = minOf(1024, rate * 3 - frames)
                        repeat(count * 2) { buffer.put(0.toByte()) }
                        codec.queueInputBuffer(index, 0, count * 2, frames * 1_000_000L / rate,
                            if (count == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        frames += count
                        if (count == 0) inputDone = true
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(!muxed)
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); muxed = true
                } else if (index >= 0) {
                    val buffer = checkNotNull(codec.getOutputBuffer(index))
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        check(muxed)
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
        } finally {
            if (started) runCatching { codec.stop() }
            codec.release()
            if (muxed) runCatching { muxer.stop() }
            muxer.release()
        }
        check(file.length() > 100)
    }

    private fun verifyPlayback(audio: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(audio.absolutePath)
            check(extractor.trackCount == 1)
            check(extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC)
        } finally { extractor.release() }
        val player = MediaPlayer()
        try {
            player.setDataSource(audio.absolutePath); player.setVolume(0f, 0f); player.prepare()
            check(player.duration in 2800..3500)
            val sought = CountDownLatch(1)
            player.setOnSeekCompleteListener { sought.countDown() }
            player.seekTo(1020L, MediaPlayer.SEEK_CLOSEST)
            check(sought.await(5, TimeUnit.SECONDS)) { "MediaPlayer seek did not complete" }
            check(abs(player.currentPosition - 1020) <= 150)
            player.start(); SystemClock.sleep(120)
            check(player.isPlaying)
        } finally { player.release() }
    }

    private fun backup(file: File, pdf: ByteArray, audio: ByteArray, a: String, b: String, title: String, invalidRefs: Boolean = false) {
        val targetA = if (invalidRefs) unknownDoc else docA
        val targetB = if (invalidRefs) unknownDoc else docB
        val info = """{"key":"$recordingKey","name":"$title","start":$epochSeconds,"duration":3.0,"startdoc":"$targetA","includeDocuments":[{"dockey":"$targetA","pages":["$pageA"]},{"dockey":"$targetB","pages":["$pageB"]}]}"""
        val sync = """{"items":[{"addtime":${epochSeconds + 1.020},"dockey":"$targetA","pagekey":"$pageA","obj":"$strokeA"},{"addtime":${epochSeconds + 2.100},"dockey":"$targetB","pagekey":"$pageB","obj":"$strokeB"}]}"""
        val fab = zip(mapOf("$recordingKey.flxa" to audio, "$recordingKey.rinfo" to info.toByteArray(), "$recordingKey.rsync" to sync.toByteArray()))
        file.writeBytes(zip(linkedMapOf(
            "flexcilbackup/Documents/doc-a.flx" to document(docA, pageA, strokeA, a, pdf),
            "flexcilbackup/Documents/doc-b.flx" to document(docB, pageB, strokeB, b, pdf),
            "flexcilbackup/Recordings/$recordingKey.fab" to fab,
        )))
    }

    private fun document(doc: String, page: String, stroke: String, title: String, pdf: ByteArray): ByteArray {
        val points = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(2); putFloat(0f); putFloat(0f); putFloat(0.003f)
            putFloat(0.3f); putFloat(0.3f); putFloat(0.003f)
        }.array()
        val drawings = """[{"type":1,"mode":5,"figure":0,"key":"$stroke","points":"${Base64.getEncoder().encodeToString(points)}","start":{"x":0.1,"y":0.1},"strokeColor":4278190080,"scale":{"x":1,"y":1},"rotate":0}]"""
        val pdfKey = "00000000-0000-4000-8000-000000000008"
        return zip(mapOf(
            "info" to """{"key":"$doc","name":"$title","attachments":[]}""".toByteArray(),
            "pages.index" to """[{"key":"$page","frame":{"width":100,"height":120},"rotate":0,"attachmentPage":{"file":"$pdfKey","index":0}}]""".toByteArray(),
            "attachment/PDF/$pdfKey" to pdf,
            "objects/$page.drawings" to drawings.toByteArray(),
        ))
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) {
            val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) { result += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::walk) }
        uiAutomation.rootInActiveWindow?.let(::walk)
        return result
    }
    private fun clickable(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable) current = current.parent
        return current?.isEnabled == true && current.isClickable
    }
    private fun clickText(text: String) = click(checkNotNull(nodes().firstOrNull { it.text?.toString() == text }))
    private fun clickDescription(text: String) = click(checkNotNull(nodes().firstOrNull { it.contentDescription?.toString() == text }))
    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable) current = current.parent
        check(current?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; SystemClock.sleep(80) }
        error("Timed out waiting for $label")
    }
}
