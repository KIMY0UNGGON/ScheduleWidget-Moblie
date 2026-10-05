package com.schedulewidget.mobile.notes

import android.content.Context
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import com.schedulewidget.mobile.notes.importer.FileKind
import com.schedulewidget.mobile.notes.importer.FlexcilArchive
import com.schedulewidget.mobile.notes.importer.ZipPartSource
import com.schedulewidget.mobile.pet.DrivePets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

/** Import of files into notebooks (implemented with the library). */
object NoteImport {
    enum class Convert {
        AUTO, DRIVE, OFFLINE;

        companion object {
            /** From [com.schedulewidget.mobile.data.NotesSettings.convert]. */
            fun of(setting: String): Convert = when (setting) { "drive" -> DRIVE; "offline" -> OFFLINE; else -> AUTO }
        }
    }

    sealed interface State {
        data object Idle : State
        /** [stage] = what is happening; in a batch "가져오는 중 (n/m) · 파일명", with that file's own step in [detail]. */
        data class Running(val stage: String, val detail: String? = null) : State
        /** [diagnostic] = a text report (archive entry names and sizes, no content) the user can share with us. */
        data class Failed(val message: String, val diagnostic: File? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /**
     * A finished import: the (first) notebook, how it was converted, and whether the editor should open right away.
     * [count] notebooks were made (a Flexcil backup or a batch makes several). [details] = things the user should
     * read (ink not imported, files that failed); [diagnostic] as in [State.Failed].
     */
    data class Done(
        val note: NoteMeta,
        val message: String,
        val open: Boolean,
        val count: Int = 1,
        val details: String? = null,
        val diagnostic: File? = null,
    )

    private val _done = MutableStateFlow<Done?>(null)
    val done: StateFlow<Done?> = _done
    fun consumeDone() { _done.value = null }
    fun dismissFailure() { if (_state.value is State.Failed) _state.value = State.Idle }

    /** Google's consent screen the library must show (an import that needs Drive is waiting for it). */
    private val _consent = MutableStateFlow<IntentSenderRequest?>(null)
    val consent: StateFlow<IntentSenderRequest?> = _consent
    @Volatile private var consentWaiter: CompletableDeferred<String?>? = null
    fun consentLaunched() { _consent.value = null }
    /** The consent screen's outcome: a Drive token, or null when declined / failed. */
    fun consentResult(token: String?) { consentWaiter?.complete(token) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var job: Job? = null

    internal class ImportError(message: String, val diagnostic: File? = null) : Exception(message)
    private class Outcome(
        val notes: List<NoteMeta>, val message: String, val details: String? = null,
        val diagnostic: File? = null, val cancelled: Boolean = false,
    ) {
        val note: NoteMeta get() = notes.first()
    }

    /** Label of the file being imported in a batch ("가져오는 중 (n/m) · 파일명"); null for a single file. */
    @Volatile private var batchLabel: String? = null

    /** Runs [import] in the background (survives leaving the screen); the result arrives in [done] / [state]. */
    fun start(context: Context, uri: Uri, convert: Convert, folder: String?, open: Boolean): Boolean =
        begin(context, convert, folder, open) { listOf(uri) }

    /** Imports several files one after another ("공유" of many files, multi-select in the picker). */
    fun startBatch(context: Context, uris: List<Uri>, convert: Convert, folder: String?, open: Boolean): Boolean =
        if (uris.isEmpty()) false else begin(context, convert, folder, open) { uris }

    /** Imports every PDF / PPTX / DOCX / Flexcil file under the folder [tree] (ACTION_OPEN_DOCUMENT_TREE), recursively. */
    fun startTree(context: Context, tree: Uri, convert: Convert, folder: String?): Boolean =
        begin(context, convert, folder, open = false) { app -> NoteImportTree.list(app, tree, ::stage) }

    private fun begin(context: Context, convert: Convert, folder: String?, open: Boolean, list: (Context) -> List<Uri>): Boolean {
        if (job?.isActive == true) return false
        val app = context.applicationContext
        _state.value = State.Running("파일 읽는 중")
        job = scope.launch { runBatch(app, list, convert, folder, open) }
        return true
    }

    fun cancel() {
        job?.cancel()
        consentWaiter?.complete(null)
    }

    /** Imports a PDF, PPTX, DOCX (or image) from [uri]; PPT/Word are converted to PDF pages per [convert]. */
    suspend fun import(context: Context, uri: Uri, convert: Convert = Convert.AUTO, folder: String? = null): Result<NoteMeta> {
        val app = context.applicationContext
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    Result.success(importOnce(app, uri, convert, folder).note)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Result.failure(e)
                } finally {
                    _state.value = State.Idle
                }
            }
        }
    }

    private fun messageOf(e: Throwable): String = when (e) {
        is ImportError -> e.message.orEmpty()
        is OutOfMemoryError -> "파일이 너무 커서 가져오지 못했어요"
        is SecurityException -> "파일을 읽을 권한이 없어요. 다시 선택해 주세요"
        else -> "가져오지 못했어요: ${e.message ?: e.javaClass.simpleName}"
    }

    /** One file in its own work directory (deleted afterwards). */
    private suspend fun importOnce(app: Context, uri: Uri, convert: Convert, folder: String?): Outcome {
        val work = File(app.cacheDir, "note_import/" + System.nanoTime()).apply { mkdirs() }
        try {
            return importFile(app, uri, convert, folder, work)
        } finally {
            work.deleteRecursively()
        }
    }

    private suspend fun runBatch(app: Context, list: (Context) -> List<Uri>, convert: Convert, folder: String?, open: Boolean): Unit =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val uris = try {
                        list(app)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _state.value = State.Failed("폴더를 읽지 못했어요: ${e.message ?: e.javaClass.simpleName}")
                        return@withContext
                    }
                    when (uris.size) {
                        0 -> { _state.value = State.Failed("가져올 수 있는 파일이 없어요 (PDF, PPT, Word, .flex/.flx)") }
                        1 -> try {
                            val o = importOnce(app, uris[0], convert, folder)
                            _state.value = State.Idle
                            _done.value = Done(o.note, o.message, open && o.notes.size == 1, o.notes.size, o.details, o.diagnostic)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            _state.value = State.Failed(messageOf(e), (e as? ImportError)?.diagnostic)
                        }
                        else -> importMany(app, uris, convert, folder)
                    }
                    Unit
                } catch (e: CancellationException) {
                    _state.value = State.Idle
                    throw e
                } finally {
                    batchLabel = null
                }
            }
        }

    private suspend fun importMany(app: Context, uris: List<Uri>, convert: Convert, folder: String?) {
        val notes = ArrayList<NoteMeta>()
        val failed = ArrayList<String>()
        val notices = ArrayList<String>()
        var diagnostic: File? = null
        var ok = 0
        var attempted = 0
        var cancelled = false
        for ((i, uri) in uris.withIndex()) {
            try {
                currentCoroutineContext().ensureActive()
            } catch (_: CancellationException) {
                cancelled = true
                break
            }
            val name = NoteImportFiles.displayName(app, uri) ?: "파일 ${i + 1}"
            attempted++
            batchLabel = "가져오는 중 (${i + 1}/${uris.size}) · $name"
            stage("파일 읽는 중")
            try {
                val o = importOnce(app, uri, convert, folder)
                notes += o.notes
                ok++
                o.details?.let { notices += "$name: $it" }
                o.diagnostic?.let { diagnostic = it }
                if (o.cancelled) {
                    cancelled = true
                    break
                }
            } catch (e: CancellationException) {
                cancelled = true
                notices += "$name: 취소되어 이 파일은 완료하지 못했어요."
                break
            } catch (e: Throwable) {
                failed += "$name — ${messageOf(e)}"
                (e as? ImportError)?.diagnostic?.let { diagnostic = it }
            }
        }
        batchLabel = null
        if (notes.isEmpty()) {
            _state.value = if (cancelled && failed.isEmpty()) State.Idle else State.Failed(
                (if (cancelled) "가져오기를 취소했어요. 저장된 노트가 없어요." else "파일 ${uris.size}개를 하나도 가져오지 못했어요") +
                    if (failed.isEmpty()) "" else "\n\n" + failed.joinToString("\n"), diagnostic,
            )
            return
        }
        _state.value = State.Idle
        val message = if (cancelled) "가져오기를 취소했어요 · 노트 ${notes.size}개 저장" else
            "파일 ${uris.size}개 중 ${ok}개를 가져왔어요" + if (notes.size != ok) " (노트 ${notes.size}개)" else ""
        val details = buildList {
            addAll(notices)
            if (failed.isNotEmpty()) add("가져오지 못한 파일 ${failed.size}개:\n" + failed.joinToString("\n"))
            val remaining = uris.size - attempted
            if (cancelled && remaining > 0) add("남은 파일 ${remaining}개는 처리하지 않았어요.")
        }.joinToString("\n\n").ifEmpty { null }
        _done.value = Done(notes.first(), message, open = false, count = notes.size,
            details = if (cancelled && details == null) "가져오기를 취소했어요." else details, diagnostic = diagnostic)
    }

    private fun stage(text: String) {
        val label = batchLabel
        _state.value = if (label != null) State.Running(label, text) else State.Running(text)
    }

    private suspend fun importFile(app: Context, uri: Uri, convert: Convert, folder: String?, work: File): Outcome {
        stage("파일 읽는 중")
        val name = NoteImportFiles.displayName(app, uri)
        // Seekable SAF files avoid a second multi-GB cache copy of backup recordings we don't import.
        if (FlexcilArchive.isFlexcilName(name) || name?.endsWith(".zip", true) == true) {
            val descriptor = runCatching { app.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
            descriptor?.use { fd ->
                val direct = File("/proc/self/fd/${fd.fd}")
                if (fd.statSize > 0 && FlexcilArchive.looksLikeFlexcil(direct))
                    return flexcil(app, direct, name, folder, work)
            }
        }
        val mime = runCatching { app.contentResolver.getType(uri) }.getOrNull()
        val input = File(work, "input")
        val importJob = currentCoroutineContext().job
        (app.contentResolver.openInputStream(uri) ?: throw ImportError("파일을 열 수 없어요")).use { src ->
            input.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    importJob.ensureActive()
                    val count = src.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
        if (input.length() == 0L) throw ImportError("빈 파일이에요")
        val head = input.inputStream().use { s -> ByteArray(1024).let { b -> val n = s.read(b); if (n <= 0) ByteArray(0) else b.copyOf(n) } }
        val detected = FileKind.detect(name, mime, head) { entry ->
            runCatching { ZipPartSource(input).use { it.has(entry) } }.getOrDefault(false)
        }
        // A zip with another name ("backup.zip", a renamed export) may still be a Flexcil export.
        val isZip = head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
        val kind = if (detected == FileKind.UNKNOWN && isZip && FlexcilArchive.looksLikeFlexcil(input)) FileKind.FLEXCIL else detected
        val title = name?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() } ?: "가져온 노트"
        currentCoroutineContext().ensureActive()
        return when (kind) {
            FileKind.PDF -> {
                NoteImportFiles.checkPdf(input)
                stage("노트 만드는 중")
                Outcome(listOf(create(app, input, title, "pdf", folder)), "PDF를 가져왔어요")
            }
            FileKind.IMAGE -> {
                stage("페이지 만드는 중")
                val pdf = File(work, "image.pdf")
                NoteImportFiles.imageToPdf(input, pdf)
                stage("노트 만드는 중")
                Outcome(listOf(create(app, pdf, title, "image", folder)), "사진을 가져왔어요")
            }
            FileKind.FLEXCIL -> flexcil(app, input, name, folder, work)
            FileKind.UNKNOWN -> throw ImportError("지원하지 않는 파일이에요. PDF, PPT, Word, 사진, .flex/.flx 파일을 가져올 수 있어요")
            else -> office(app, input, kind, title, convert, folder, work)
        }
    }

    // ---- Flexcil (.flx document, .flex backup) ----

    /** Restores native notebooks, preserving page order, blank pages, original sources and editable ink. */
    private suspend fun flexcil(app: Context, input: File, name: String?, folder: String?, work: File): Outcome {
        val restored = NoteFlexcilImport.restore(app, input, name, folder, work, ::stage)
        return Outcome(restored.notes, restored.message, restored.details, restored.diagnostic, restored.cancelled)
    }

    private fun create(app: Context, pdf: File, title: String, source: String, folder: String?): NoteMeta = try {
        NoteStore.createFromPdf(app, pdf, title, source, folder)
    } catch (e: IOException) {
        throw ImportError("노트를 만들지 못했어요: ${e.message ?: "PDF 오류"}")
    }

    // ---- PPT / Word ----

    private suspend fun office(app: Context, input: File, kind: FileKind, title: String, convert: Convert, folder: String?, work: File): Outcome {
        val converted = NoteOfficeImport.convert(app, input, kind, title, convert, work, ::stage, ::driveToken)
        stage("노트 만드는 중")
        val note = create(app, converted.pdf, title, kind.source, folder)
        return Outcome(listOf(note), converted.message)
    }

    /** A Drive token: silently when access was granted before; with [interactive], via the library's consent screen. */
    private suspend fun driveToken(app: Context, interactive: Boolean): String? {
        stage("구글 계정 확인 중")
        return when (val a = runCatching { DrivePets.authorize(app) }.getOrNull()) {
            is DrivePets.Auth.Token -> a.value
            is DrivePets.Auth.NeedsConsent -> if (interactive) askConsent(a.request) else null
            else -> null
        }
    }

    private suspend fun askConsent(request: IntentSenderRequest): String? {
        val waiter = CompletableDeferred<String?>()
        consentWaiter = waiter
        _consent.value = request
        stage("구글 드라이브 권한을 기다리는 중")
        return try {
            withTimeoutOrNull(3 * 60_000L) { waiter.await() }
        } finally {
            _consent.value = null
            consentWaiter = null
        }
    }

    // ---- PDF / image helpers ----


}
