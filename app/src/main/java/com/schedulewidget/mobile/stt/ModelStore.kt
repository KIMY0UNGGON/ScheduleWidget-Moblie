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
        if (!hasVerifiedPackage(dir, pkg)) return false
        val filesOk = pkg.files.all { f -> File(dir, f.name).let { it.isFile && it.length() == f.bytes } }
        val archiveOk = pkg.archive?.let { a ->
            a.required.all { (path, min) -> File(dir, path).let { it.isFile && it.length() >= min } }
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
                val downloaded = File(dir, f.name)
                val present = if (downloaded.isFile) downloaded.length() else File(dir, f.name + ".part").length()
                needed += (f.bytes - present).coerceAtLeast(0)
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
        File(dir, COMPLETE_MARKER).delete()
        var before = 0L
        for (f in pkg.files) {
            val base = before
            Downloader.fetch(f.url, File(dir, f.name), f.bytes, f.sha256) { onProgress(base + it) }
            before += f.bytes
        }
        pkg.archive?.let { a ->
            val archive = File(dir, archiveName(a))
            val base = before
            Downloader.fetch(a.url, archive, a.archiveBytes, a.sha256) { onProgress(base + it) }
            // The catalog's extracted size is an estimate; allow headroom for the kept files.
            Downloader.extractTarBz2(archive, dir, a.keep, a.extractedBytes * 2 + 16L * 1024 * 1024) {}
            archive.delete()
            val missing = a.required.filter { (path, min) -> File(dir, path).let { !it.isFile || it.length() < min } }
            if (missing.isNotEmpty()) throw DownloadException("압축 파일에 필요한 파일이 없어요: ${missing.keys.joinToString()}")
        }
        File(dir, COMPLETE_MARKER).writeText(packageVerification(pkg))
    }
}

internal fun packageVerification(pkg: ModelPackage): String =
    (pkg.files.map { "${it.name}:${it.bytes}:${it.sha256}" } + listOfNotNull(pkg.archive?.sha256)).joinToString("\n")

/** Legacy size-only downloads must pass a hash check once, before this private marker is written. */
internal fun hasVerifiedPackage(dir: File, pkg: ModelPackage): Boolean {
    val expected = packageVerification(pkg)
    val marker = File(dir, ".complete")
    return marker.isFile && marker.length() == expected.toByteArray(Charsets.UTF_8).size.toLong() &&
        runCatching { marker.readText() == expected }.getOrDefault(false)
}

internal fun archiveSpaceNeeded(dir: File, name: String, archive: RemoteArchive): Long {
    val downloaded = File(dir, name)
    val present = if (downloaded.isFile) downloaded.length() else File(dir, "$name.part").length()
    val downloadBytes = (archive.archiveBytes - present).coerceAtLeast(0)
    return downloadBytes + archive.extractedBytes
}
