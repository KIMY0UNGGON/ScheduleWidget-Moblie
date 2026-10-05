package com.schedulewidget.mobile.stt

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.schedulewidget.mobile.data.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/** A failure whose message is shown to the user as-is. */
internal class TranscribeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Backs [Transcriber]. Pipeline for one recording (one at a time, others wait in line):
 *  1. decode + resample to 16 kHz mono, spooled to a 16-bit temp file in cacheDir; level-normalised;
 *     Silero VAD then reads the spool to find speech;
 *  2. each speech span (padded, merged, cut at quiet points to fit the model) goes through the OfflineRecognizer;
 *  3. optionally pyannote + embedding diarization in ~5-minute windows, speakers matched across windows
 *     by clustering per-window speaker embeddings; every segment takes the speaker it overlaps most.
 * Peak Java heap is about one diarization window (5 min x 16 kHz x 4 B = 19 MB) instead of the whole recording.
 */
internal object TranscribeEngine {
    private const val TAG = "SttTranscribe"
    private const val SR = AudioDecoder.SAMPLE_RATE

    val states = MutableStateFlow<Map<String, TranscribeState>>(emptyMap())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, Job>()
    // Models take hundreds of MB of native memory; never run two recordings at once.
    private val runLock = Mutex()

    fun anyActive(): Boolean = jobs.values.any { it.isActive }

    fun start(context: Context, recordingId: String, audio: File, options: Transcriber.Options) {
        val app = context.applicationContext
        if (jobs[recordingId]?.isActive == true) return
        set(recordingId, TranscribeState.Running(0f, "대기 중"))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext()[Job]
            val publish = { s: TranscribeState -> if (jobs[recordingId] === self) set(recordingId, s) }
            runLock.withLock {
                try {
                    run(app, recordingId, audio, options) { fraction, stage -> publish(TranscribeState.Running(fraction.coerceIn(0f, 1f), stage)) }
                    publish(TranscribeState.Done)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TranscribeException) {
                    Log.w(TAG, "transcribe $recordingId failed: ${e.message}", e)
                    publish(TranscribeState.Failed(e.message ?: "받아쓰기에 실패했어요"))
                } catch (e: AudioDecodeException) {
                    Log.w(TAG, "decode $recordingId failed", e)
                    publish(TranscribeState.Failed(e.message ?: "녹음 파일을 읽지 못했어요"))
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "transcribe $recordingId: out of memory", e)
                    publish(TranscribeState.Failed("메모리가 부족해요. 다른 앱을 닫거나 더 작은 모델로 다시 시도해 주세요"))
                } catch (e: Throwable) {
                    Log.e(TAG, "transcribe $recordingId failed", e)
                    publish(TranscribeState.Failed("받아쓰기에 실패했어요: ${e.message ?: e.javaClass.simpleName}"))
                }
            }
        }
        job.invokeOnCompletion { jobs.remove(recordingId, job) }
        jobs[recordingId] = job
        job.start()
        TranscribeService.start(app)
    }

    fun cancel(recordingId: String) {
        jobs.remove(recordingId)?.cancel()
        states.update { it - recordingId }
    }

    fun cancelAll() { jobs.keys.toList().forEach(::cancel) }

    private fun set(id: String, s: TranscribeState) = states.update { it + (id to s) }

    // ---- pipeline ----

    private fun threads() = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** Longest span fed to the recognizer: Whisper sees at most 30 s, Qwen3-ASR has a 512-token KV budget. */
    private fun maxSeconds(model: SttModel) = when (model) {
        SttModel.QWEN3_ASR -> 20.0
        SttModel.WHISPER_TURBO, SttModel.WHISPER_SMALL -> 28.0
        SttModel.SENSE_VOICE -> 25.0
    }

    private suspend fun run(
        app: Context, id: String, audio: File, options: Transcriber.Options,
        progress: (Float, String) -> Unit,
    ) {
        if (!audio.isFile) throw TranscribeException("녹음 파일을 찾을 수 없어요")
        if (!ModelStore.isReady(app, options.model)) throw TranscribeException("모델을 먼저 받아 주세요")
        if (options.diarize && !ModelStore.isDiarizationReady(app)) throw TranscribeException("화자 분리 모델을 먼저 받아 주세요")

        val wake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScheduleWidget:stt-transcribe")
        wake.setReferenceCounted(false)
        wake.acquire(8 * 60 * 60 * 1000L)
        val work = SttPaths.work(app).apply { mkdirs() }
        val pcm = File(work, "${id.hashCode().toUInt()}-${System.nanoTime()}.pcm")
        try {
            val (decodeEnd, recognizeEnd) = if (options.diarize) 0.15f to 0.75f else 0.2f to 1f
            val t0 = System.nanoTime()

            // 1. decode to a 16 kHz spool, measure the level, find speech
            progress(0f, "음성 구간 찾는 중")
            val (total, gain) = decode(audio, pcm) { progress(it * decodeEnd * 0.7f, "음성 구간 찾는 중") }
            if (total < SR / 2) throw TranscribeException("녹음이 너무 짧아요")
            val tDecoded = System.nanoTime()
            val audioData = Pcm(pcm, gain)
            val speech = detectSpeech(app, audioData, total) { progress(decodeEnd * (0.7f + 0.3f * it), "음성 구간 찾는 중") }
            val spans = SegmentPlanner.plan(
                speech, total,
                maxLen = (maxSeconds(options.model) * SR).toLong(),
                mergeGap = (0.6 * SR).toLong(), pad = (0.25 * SR).toLong(), minLen = (0.3 * SR).toLong(),
                quietest = audioData::quietest,
            )
            val t1 = System.nanoTime()

            // 2. recognition
            val texts = recognize(app, options.model, audioData, spans) { done, n, fraction ->
                progress(decodeEnd + (recognizeEnd - decodeEnd) * fraction, "받아쓰는 중 ($done/$n)")
            }
            // The user's correction dictionary (Settings) fixes recurring misrecognitions in new transcripts.
            val corrections = runCatching { Repository.get(app).data.value.stt.corrections }.getOrDefault(emptyList())
            var segments = spans.indices.mapNotNull { i ->
                val text = SttCorrections.apply(texts[i], corrections).trim()
                if (text.isEmpty()) null else TranscriptSegment(spans[i].start * 1000 / SR, spans[i].end * 1000 / SR, null, text)
            }
            val t2 = System.nanoTime()

            // 3. speakers
            if (options.diarize && segments.isNotEmpty()) {
                progress(recognizeEnd, "화자 나누는 중")
                val turns = diarize(app, audioData, total, speech, options.speakers) {
                    progress(recognizeEnd + (1f - recognizeEnd) * it, "화자 나누는 중")
                }
                val assigned = Speakers.renumber(Speakers.assign(segments.map { it.startMs / 1000.0 to it.endMs / 1000.0 }, turns))
                segments = segments.mapIndexed { i, s -> s.copy(speaker = assigned[i] ?: 0) }
            }

            currentCoroutineContext().ensureActive()
            TranscriptStore.save(app, Transcript(id, options.model.id, System.currentTimeMillis(), segments))
            val sec = { a: Long, b: Long -> "%.1f".format((b - a) / 1e9) }
            Log.i(
                TAG, "$id: ${options.model.id}, audio ${total / SR}s, gain ${"%.2f".format(gain)}, ${spans.size} spans; " +
                    "decode ${sec(t0, tDecoded)}s, vad ${sec(tDecoded, t1)}s, asr ${sec(t1, t2)}s, speakers ${sec(t2, System.nanoTime())}s",
            )
        } finally {
            pcm.delete()
            if (wake.isHeld) wake.release()
        }
    }

    /**
     * Decodes [audio] into [pcm] (16-bit LE, 16 kHz mono) and returns (total samples, gain). The gain brings the
     * 99.99th-percentile level to 0.5: Silero misses pauses in quiet recordings (one 140 s "segment" on the PC
     * benchmark, SenseVoice error rate 7.9% -> 19.6%), so speech detection and recognition see normalised audio.
     */
    private suspend fun decode(audio: File, pcm: File, onProgress: (Float) -> Unit): Pair<Long, Float> {
        val histogram = LongArray(32769)
        var total = 0L
        BufferedOutputStream(FileOutputStream(pcm), 1 shl 16).use { out ->
            val bytes = ByteBuffer.allocate(8192 * 2).order(ByteOrder.LITTLE_ENDIAN)
            AudioDecoder.decode(audio, sink = { chunk ->
                // Spooled as 16-bit: half the disk of floats, plenty of precision for speech models.
                var i = 0
                while (i < chunk.size) {
                    bytes.clear()
                    val n = minOf(8192, chunk.size - i)
                    for (k in 0 until n) {
                        val v = (chunk[i + k] * 32767f).coerceIn(-32768f, 32767f).toInt()
                        histogram[if (v < 0) -v else v]++
                        bytes.putShort(v.toShort())
                    }
                    out.write(bytes.array(), 0, n * 2)
                    i += n
                }
                total += chunk.size
            }, onProgress = onProgress)
        }
        var above = 0L
        var level = 32768
        val limit = total / 10_000 // 99.99th percentile
        while (level > 0 && above + histogram[level] <= limit) { above += histogram[level]; level-- }
        // Never boost silence/noise into something huge, never attenuate much.
        val gain = if (level == 0) 1f else (0.5f * 32768f / level).coerceIn(0.5f, 30f)
        return total to gain
    }

    /** Silero VAD over the spooled audio; returns raw speech spans in samples. */
    private suspend fun detectSpeech(app: Context, audio: Pcm, total: Long, onProgress: (Float) -> Unit): List<Span> {
        val vadModel = File(ModelCatalog.vad.dir(app), "silero_vad.onnx").path
        val window = 512
        val vad = createNative("음성 구간 모델") {
            Vad(
                null,
                VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadModel, threshold = 0.5f, minSilenceDuration = 0.5f, minSpeechDuration = 0.25f,
                        windowSize = window, maxSpeechDuration = 15f,
                    ),
                    sampleRate = SR, numThreads = 1,
                ),
            )
        }
        val speech = ArrayList<Span>()
        fun drain() {
            while (!vad.empty()) {
                val seg = vad.front()
                speech += Span(seg.start.toLong(), seg.start.toLong() + seg.samples.size)
                vad.pop()
            }
        }
        try {
            val block = window * 64L
            var pos = 0L
            RandomAccessFile(audio.file, "r").use { raf ->
                while (pos < total) {
                    currentCoroutineContext().ensureActive()
                    val samples = audio.read(raf, Span(pos, minOf(total, pos + block)))
                    var j = 0
                    // Silero wants exactly windowSize samples per call (the last one may be short).
                    while (j < samples.size) {
                        val end = minOf(samples.size, j + window)
                        vad.acceptWaveform(samples.copyOfRange(j, end))
                        j = end
                    }
                    drain()
                    pos += samples.size
                    onProgress(pos.toFloat() / total)
                }
            }
            vad.flush()
            drain()
        } finally {
            vad.release()
        }
        return speech
    }

    /** The spooled 16 kHz recording with its normalising [gain]. */
    private class Pcm(val file: File, val gain: Float) {
        fun read(raf: RandomAccessFile, span: Span): FloatArray {
            val n = span.length.toInt()
            val bytes = ByteArray(n * 2)
            raf.seek(span.start * 2)
            raf.readFully(bytes)
            val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val scale = gain / 32768f
            return FloatArray(n) { (sb.get(it) * scale).coerceIn(-1f, 1f) }
        }

        /** Centre of the quietest 30 ms frame in [from, to) (where long speech is best cut). */
        fun quietest(from: Long, to: Long): Long {
            val frame = SR * 3 / 100
            if (to - from < frame * 2) return (from + to) / 2
            val samples = RandomAccessFile(file, "r").use { read(it, Span(from, to)) }
            var best = (from + to) / 2
            var bestEnergy = Double.MAX_VALUE
            var i = 0
            while (i + frame <= samples.size) {
                var e = 0.0
                for (k in i until i + frame) e += samples[k] * samples[k]
                if (e < bestEnergy) { bestEnergy = e; best = from + i + frame / 2 }
                i += frame / 2
            }
            return best
        }
    }

    private suspend fun recognize(
        app: Context, model: SttModel, audio: Pcm, spans: List<Span>,
        onProgress: (done: Int, total: Int, fraction: Float) -> Unit,
    ): List<String> {
        if (spans.isEmpty()) return emptyList()
        val whisper = model == SttModel.WHISPER_TURBO || model == SttModel.WHISPER_SMALL
        val recognizer = createNative("음성 인식 모델") { OfflineRecognizer(null, recognizerConfig(app, model)) }
        val totalSamples = spans.sumOf { it.length }.coerceAtLeast(1)
        var doneSamples = 0L
        val texts = ArrayList<String>(spans.size)
        try {
            RandomAccessFile(audio.file, "r").use { raf ->
                onProgress(0, spans.size, 0f)
                for ((i, span) in spans.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val samples = audio.read(raf, span)
                    val stream = recognizer.createStream()
                    try {
                        // Qwen3-ASR has no language setting in its config; the prompt takes it per stream.
                        if (model == SttModel.QWEN3_ASR) stream.setOption("language", "Korean")
                        stream.acceptWaveform(samples, SR)
                        recognizer.decode(stream)
                        val raw = recognizer.getResult(stream).text
                        val text = clean(if (whisper) WhisperByteTokens.decodeText(raw) else raw)
                        // SenseVoice spaces Korean morpheme by morpheme; fixed before the correction dictionary runs
                        // so its entries match normally spaced text.
                        texts += if (model == SttModel.SENSE_VOICE) KoreanSpacing.fix(text) else text
                    } finally {
                        stream.release()
                    }
                    doneSamples += span.length
                    onProgress(i + 1, spans.size, doneSamples.toFloat() / totalSamples)
                }
            }
        } finally {
            recognizer.release()
        }
        return texts
    }

    /** Strips recognizer artefacts: SenseVoice-style <|tags|>, repeated whitespace. */
    private fun clean(text: String): String = text.replace(Regex("<\\|[^|>]*\\|>"), "").replace(Regex("\\s+"), " ").trim()

    private fun recognizerConfig(app: Context, model: SttModel): OfflineRecognizerConfig {
        val dir = ModelCatalog.of(model).dir(app)
        val path = dir.path
        val threads = threads()
        // See WhisperByteTokens: Whisper reads a re-encoded tokens file so Hangul byte pieces survive.
        fun whisperTokens(name: String) = try {
            WhisperByteTokens.fixedTokens(File(dir, name)).path
        } catch (e: java.io.IOException) {
            throw TranscribeException("모델 파일을 준비하지 못했어요. 모델을 지우고 다시 받아 주세요", e)
        }
        val modelConfig = when (model) {
            SttModel.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(model = "$path/model.int8.onnx", language = "ko", useInverseTextNormalization = true),
                tokens = "$path/tokens.txt", numThreads = threads,
            )
            SttModel.WHISPER_TURBO -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "$path/turbo-encoder.int8.onnx", decoder = "$path/turbo-decoder.int8.onnx", language = "ko", task = "transcribe",
                ),
                tokens = whisperTokens("turbo-tokens.txt"), numThreads = threads, modelType = "whisper",
            )
            SttModel.WHISPER_SMALL -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "$path/small-encoder.int8.onnx", decoder = "$path/small-decoder.int8.onnx", language = "ko", task = "transcribe",
                ),
                tokens = whisperTokens("small-tokens.txt"), numThreads = threads, modelType = "whisper",
            )
            SttModel.QWEN3_ASR -> OfflineModelConfig(
                qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = "$path/conv_frontend.onnx", encoder = "$path/encoder.int8.onnx", decoder = "$path/decoder.int8.onnx",
                    tokenizer = "$path/tokenizer", maxNewTokens = 256,
                ),
                tokens = "", numThreads = threads,
            )
        }
        return OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = SR, featureDim = 80), modelConfig = modelConfig)
    }

    /** sherpa-onnx constructors throw IllegalArgumentException when native init fails (bad/missing model file). */
    private inline fun <T> createNative(what: String, block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw TranscribeException("$what 을(를) 불러오지 못했어요. 모델을 지우고 다시 받아 주세요", e)
    } catch (e: UnsatisfiedLinkError) {
        throw TranscribeException("이 기기에서는 음성 인식 엔진을 실행할 수 없어요", e)
    }

    // ---- diarization ----

    // Diarization works on windows of this length; on the emulator one 10-minute window took the process
    // from 240 MB to 450 MB RSS (sherpa holds per-chunk segmentation/embedding work for the whole window).
    private const val WINDOW_SECONDS = 5 * 60
    // FastClustering distance threshold (cosine distance); the PC benchmark found 0.7 best for CAM++.
    private const val CLUSTER_THRESHOLD = 0.7f
    // Clusters holding less speech than this are folded into their nearest neighbour.
    private const val MIN_SPEAKER_SHARE = 0.05
    private const val MAX_EMBED_SECONDS = 60

    /**
     * pyannote segmentation + CAM++ clustering over ~5-minute windows (bounded memory). Every (window, local
     * speaker) gets a voice print; prints are clustered across windows with the same threshold, then tiny
     * clusters and (when the user gave a count) surplus clusters are merged into their nearest neighbour.
     * The user's count is never forced on the clusterer itself: it splits one voice in two rather than merging.
     */
    private suspend fun diarize(
        app: Context, audio: Pcm, total: Long, speech: List<Span>, speakers: Int, onProgress: (Float) -> Unit,
    ): List<SpeakerTurn> {
        val dir = ModelCatalog.diarization.dir(app).path
        val embeddingModel = "$dir/${ModelCatalog.EMBEDDING_FILE}"
        val windows = SegmentPlanner.windows(speech, total, target = WINDOW_SECONDS.toLong() * SR, slack = 30L * SR)
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = "$dir/model.onnx"), numThreads = threads(),
            ),
            embedding = SpeakerEmbeddingExtractorConfig(model = embeddingModel, numThreads = threads()),
            clustering = FastClusteringConfig(numClusters = -1, threshold = CLUSTER_THRESHOLD),
            minDurationOn = 0.3f, minDurationOff = 0.5f,
        )
        val sd = createNative("화자 분리 모델") { OfflineSpeakerDiarization(null, config) }
        val extractor = createNative("화자 분리 모델") {
            SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(model = embeddingModel, numThreads = threads()))
        }
        try {
            data class Local(val window: Int, val speaker: Int, val embedding: FloatArray, val seconds: Double)
            val windowTurns = ArrayList<List<SpeakerTurn>>()
            val locals = ArrayList<Local>()
            RandomAccessFile(audio.file, "r").use { raf ->
                for ((w, span) in windows.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val samples = audio.read(raf, span)
                    val offset = span.start.toDouble() / SR
                    val callback = DiarizationCallback { done, all ->
                        onProgress((w + done.toFloat() / all.coerceAtLeast(1)) / windows.size * 0.9f)
                    }
                    @Suppress("UNCHECKED_CAST")
                    val result = sd.processWithCallback(samples, callback as (Int, Int, Long) -> Int)
                    windowTurns += result.map { SpeakerTurn(it.start + offset, it.end + offset, it.speaker) }
                    for ((speaker, own) in result.groupBy { it.speaker }) {
                        val emb = embed(extractor, samples, own.map { it.start.toDouble() to it.end.toDouble() }) ?: continue
                        locals += Local(w, speaker, emb, own.sumOf { (it.end - it.start).toDouble() })
                    }
                }
            }
            if (locals.isEmpty()) return windowTurns.flatten()

            val embeddings = locals.map { it.embedding }
            val seconds = locals.map { it.seconds }
            // One window: keep sherpa's clusters. Several: join the per-window speakers by voice print.
            val joined = if (windows.size == 1) IntArray(locals.size) { locals[it].speaker }
            else Speakers.cluster(embeddings, 0, 1.0 - CLUSTER_THRESHOLD, seconds)
            val labels = Speakers.consolidate(joined, embeddings, seconds, MIN_SPEAKER_SHARE, speakers)
            Log.d(TAG, "diarization: ${windows.size} windows, ${locals.size} local speakers -> ${joined.distinct().size} joined -> ${labels.distinct().size}")
            val global = HashMap<Pair<Int, Int>, Int>()
            locals.forEachIndexed { i, l -> global[l.window to l.speaker] = labels[i] }
            onProgress(1f)
            // Turns of speakers too short to embed are dropped; their segments fall back to the nearest turn.
            return windowTurns.flatMapIndexed { w, turns -> turns.mapNotNull { t -> global[w to t.speaker]?.let { t.copy(speaker = it) } } }
        } finally {
            sd.release()
            extractor.release()
        }
    }

    /**
     * Progress callback for OfflineSpeakerDiarization.processWithCallback. Its JNI side looks up exactly
     * `invoke(IIJ)Ljava/lang/Integer;`, which a Kotlin lambda no longer has (Kotlin 2 compiles lambdas via
     * invokedynamic, D8 turns them into synthetic classes with only the erased invoke) and the native code aborts.
     * A nullable Int return type makes the compiler emit that exact signature (verified with javap).
     */
    private class DiarizationCallback(private val onChunk: (Int, Int) -> Unit) : Function3<Int, Int, Long, Int?> {
        override fun invoke(processed: Int, total: Int, arg: Long): Int? {
            onChunk(processed, total)
            return 0
        }
    }

    /** Speaker embedding over (at most [MAX_EMBED_SECONDS] of) the given ranges of [samples]; null if too little audio. */
    private fun embed(extractor: SpeakerEmbeddingExtractor, samples: FloatArray, ranges: List<Pair<Double, Double>>): FloatArray? {
        val limit = MAX_EMBED_SECONDS * SR
        val buf = FloatArray(minOf(limit, samples.size))
        var len = 0
        // Longest turns first: they give the cleanest voice print.
        for ((s, e) in ranges.sortedByDescending { it.second - it.first }) {
            if (len >= buf.size) break
            val a = (s * SR).toInt().coerceIn(0, samples.size)
            val b = (e * SR).toInt().coerceIn(a, samples.size)
            val n = minOf(b - a, buf.size - len)
            System.arraycopy(samples, a, buf, len, n)
            len += n
        }
        if (len < SR) return null
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(buf.copyOf(len), SR)
            stream.inputFinished()
            if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally {
            stream.release()
        }
    }
}
