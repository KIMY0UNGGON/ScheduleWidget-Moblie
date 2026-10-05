package com.schedulewidget.mobile.stt

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Backs [SttModelManager]: per-package download jobs, readiness checks and the published states. */
internal object ModelStore {
    private const val TAG = "SttModels"
    private const val COMPLETE_MARKER = ".complete"

    val modelStates = MutableStateFlow<Map<SttModel, ModelState>>(emptyMap())
    val diarizationState = MutableStateFlow<ModelState>(ModelState.Missing)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    // Two models downloading at once both want the shared VAD file.
    private val packageLocks = ConcurrentHashMap<String, Mutex>()

    // ---- readiness ----

    fun isReady(context: Context, pkg: ModelPackage): Boolean {
        val dir = pkg.dir(context)
        val filesOk = pkg.files.all { f -> File(dir, f.name).let { it.isFile && it.length() >= f.bytes / 2 } }
        val archiveOk = pkg.archive?.let { a ->
            File(dir, COMPLETE_MARKER).isFile && a.required.all { (path, min) -> File(dir, path).let { it.isFile && it.length() >= min } }
        } ?: true
        return filesOk && archiveOk
    }

    fun isReady(context: Context, model: SttModel): Boolean =
        isReady(context, ModelCatalog.of(model)) && isReady(context, ModelCatalog.vad)

    fun isDiarizationReady(context: Context): Boolean = isReady(context, ModelCatalog.diarization)

    fun usedBytes(context: Context): Long =
        SttPaths.root(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun refresh(context: Context) {
        val app = context.applicationContext
        modelStates.update { old ->
            SttModel.entries.mapNotNull { m ->
                val state = when {
                    jobs[m.id]?.isActive == true -> old[m] ?: ModelState.Downloading(0, ModelCatalog.of(m).downloadBytes())
                    isReady(app, m) -> ModelState.Ready
                    old[m] is ModelState.Failed -> old[m]
                    else -> null
                }
                state?.let { m to it }
            }.toMap()
        }
        if (jobs[ModelCatalog.diarization.key]?.isActive != true) {
            diarizationState.value = when {
                isDiarizationReady(app) -> ModelState.Ready
                diarizationState.value is ModelState.Failed -> diarizationState.value
                else -> ModelState.Missing
            }
        }
    }

    // ---- downloads ----

    fun download(context: Context, model: SttModel) {
        val app = context.applicationContext
        val packages = listOf(ModelCatalog.of(model), ModelCatalog.vad)
        start(app, model.id, packages) { modelStates.update { m -> m + (model to it) } }
    }

    fun downloadDiarization(context: Context) {
        val app = context.applicationContext
        start(app, ModelCatalog.diarization.key, listOf(ModelCatalog.diarization)) { diarizationState.value = it }
    }

    fun cancel(model: SttModel) {
        jobs.remove(model.id)?.cancel()
        modelStates.update { it - model }
    }

    fun cancelDiarization() {
        jobs.remove(ModelCatalog.diarization.key)?.cancel()
        diarizationState.value = ModelState.Missing
    }

    fun cancelAll() {
        SttModel.entries.forEach { if (jobs[it.id] != null) cancel(it) }
        if (jobs[ModelCatalog.diarization.key] != null) cancelDiarization()
    }

    fun delete(context: Context, model: SttModel) {
        jobs.remove(model.id)?.cancel()
        ModelCatalog.of(model).dir(context.applicationContext).deleteRecursively()
        modelStates.update { it - model }
    }

    fun anyDownloading(): Boolean = jobs.values.any { it.isActive }

    private fun start(app: Context, key: String, packages: List<ModelPackage>, publishAny: (ModelState) -> Unit) {
        if (jobs[key]?.isActive == true) return
        if (packages.all { isReady(app, it) }) { publishAny(ModelState.Ready); return }
        val total = packages.sumOf { it.downloadBytes() }
        publishAny(ModelState.Downloading(bytesOnDisk(app, packages).coerceAtMost(total), total))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            // After cancel()/delete() the entry is gone; late progress callbacks must not resurrect the state.
            val publish = { s: ModelState -> if (jobs[key] === self) publishAny(s) }
            val wake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScheduleWidget:stt-download")
            wake.setReferenceCounted(false)
            wake.acquire(3 * 60 * 60 * 1000L)
            try {
                checkSpace(app, packages)
                var before = 0L
                for (pkg in packages) {
                    val base = before
                    packageLocks.getOrPut(pkg.key) { Mutex() }.withLock {
                        fetchPackage(app, pkg) { done -> publish(ModelState.Downloading((base + done).coerceAtMost(total), total)) }
                    }
                    before += pkg.downloadBytes()
                }
                publish(if (packages.all { isReady(app, it) }) ModelState.Ready else ModelState.Failed("받은 파일이 올바르지 않아요. 다시 받아 주세요"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadException) {
                Log.w(TAG, "download $key failed", e)
                publish(ModelState.Failed(e.message ?: "다운로드 실패"))
            } catch (e: Exception) {
                Log.w(TAG, "download $key failed", e)
                publish(ModelState.Failed("다운로드 실패: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                if (wake.isHeld) wake.release()
            }
        }
        job.invokeOnCompletion { jobs.remove(key, job) }
        jobs[key] = job
        job.start()
        SttDownloadService.start(app)
    }

    private fun checkSpace(app: Context, packages: List<ModelPackage>) {
        var needed = 0L
        for (pkg in packages) {
            if (isReady(app, pkg)) continue
            val dir = pkg.dir(app)
            for (f in pkg.files) {
                if (!File(dir, f.name).isFile) needed += f.bytes - File(dir, f.name + ".part").length()
            }
            pkg.archive?.let { a -> needed += archiveSpaceNeeded(dir, archiveName(a), a) }
        }
        val free = app.filesDir.usableSpace
        // Keep some headroom so the phone itself does not run out of space.
        if (needed + 200L * 1024 * 1024 > free) {
            val mb = { b: Long -> (b / (1024 * 1024)).coerceAtLeast(1) }
            throw DownloadException("저장 공간이 부족해요 (${mb(needed)}MB 필요, ${mb(free)}MB 남음)")
        }
    }

    private fun archiveName(a: RemoteArchive) = a.url.substringAfterLast('/')

    /** Download bytes already present (finished files and partial ".part" files), so a resume starts its bar there. */
    private fun bytesOnDisk(app: Context, packages: List<ModelPackage>): Long = packages.sumOf { pkg ->
        if (isReady(app, pkg)) return@sumOf pkg.downloadBytes()
        val dir = pkg.dir(app)
        val names = pkg.files.map { it.name } + listOfNotNull(pkg.archive?.let(::archiveName))
        names.sumOf { n -> File(dir, n).length().takeIf { it > 0 } ?: File(dir, "$n.part").length() }
    }

    /** Fetches every missing piece of [pkg]; [onProgress] gets the package bytes downloaded so far. */
    private suspend fun fetchPackage(app: Context, pkg: ModelPackage, onProgress: (Long) -> Unit) {
        if (isReady(app, pkg)) { onProgress(pkg.downloadBytes()); return }
        val dir = pkg.dir(app).apply { mkdirs() }
        var before = 0L
        for (f in pkg.files) {
            val base = before
            Downloader.fetch(f.url, File(dir, f.name), f.bytes) { onProgress(base + it) }
            before += f.bytes
        }
        val a = pkg.archive ?: return
        if (File(dir, COMPLETE_MARKER).isFile && isReady(app, pkg)) return
        File(dir, COMPLETE_MARKER).delete()
        val archive = File(dir, archiveName(a))
        val base = before
        Downloader.fetch(a.url, archive, a.archiveBytes) { onProgress(base + it) }
        // Unpacking the big Qwen3 archive takes a while; the progress bar sits at 100% meanwhile.
        Downloader.extractTarBz2(archive, dir, a.keep) {}
        archive.delete()
        val missing = a.required.filter { (path, min) -> File(dir, path).let { !it.isFile || it.length() < min } }
        if (missing.isNotEmpty()) throw DownloadException("압축 파일에 필요한 파일이 없어요: ${missing.keys.joinToString()}")
        File(dir, COMPLETE_MARKER).writeText(System.currentTimeMillis().toString())
    }
}

internal fun archiveSpaceNeeded(dir: File, name: String, archive: RemoteArchive): Long {
    val downloaded = File(dir, name)
    val downloadBytes = if (downloaded.isFile && downloaded.length() > 0) 0L
    else (archive.archiveBytes - File(dir, "$name.part").length()).coerceAtLeast(0)
    return downloadBytes + archive.extractedBytes
}
