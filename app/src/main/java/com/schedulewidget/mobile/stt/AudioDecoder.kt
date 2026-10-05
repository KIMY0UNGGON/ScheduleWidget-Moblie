package com.schedulewidget.mobile.stt

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The recording could not be decoded; the message is shown to the user. */
internal class AudioDecodeException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Decodes a recording to 16 kHz mono float PCM in chunks, so a 90-minute lecture never sits in memory at once.
 * WAV is parsed directly; everything else (m4a/AAC from MediaRecorder, mp3, ogg...) goes through MediaCodec.
 */
internal object AudioDecoder {
    const val SAMPLE_RATE = 16_000

    /** Streams the audio to [sink]; [onProgress] gets 0..1 of the input consumed. */
    suspend fun decode(file: File, sink: (FloatArray) -> Unit, onProgress: (Float) -> Unit) {
        if (!file.isFile || file.length() == 0L) throw AudioDecodeException("녹음 파일을 찾을 수 없어요")
        val isWav = file.inputStream().use { s ->
            val head = ByteArray(12)
            s.read(head) == 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WAVE"
        }
        if (isWav) decodeWav(file, sink, onProgress) else decodeWithCodec(file, sink, onProgress)
    }

    // ---- MediaCodec path ----

    /**
     * MediaCodec in asynchronous mode: the codec hands us free input / filled output buffers through callbacks
     * on a private thread, so there is no dequeue polling. Each codec call is a binder round trip to the media
     * process; polling roughly doubled them and made decoding slower than recognition on the emulator.
     * Resampling and the sink run on that callback thread, one buffer at a time.
     */
    private suspend fun decodeWithCodec(file: File, sink: (FloatArray) -> Unit, onProgress: (Float) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val thread = HandlerThread("stt-decode").apply { start() }
        try {
            try {
                extractor.setDataSource(file.path)
            } catch (e: Exception) {
                throw AudioDecodeException("녹음 파일을 열지 못했어요", e)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioDecodeException("녹음 파일에 소리 트랙이 없어요")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            extractor.selectTrack(track)

            val done = CompletableDeferred<Unit>()
            val callback = CodecCallback(extractor, durationUs, sink, onProgress, done)
            codec = try {
                MediaCodec.createDecoderByType(mime).apply {
                    setCallback(callback, Handler(thread.looper))
                    configure(format, null, null, 0)
                    start()
                }
            } catch (e: Exception) {
                throw AudioDecodeException("이 녹음 형식($mime)은 읽을 수 없어요", e)
            }
            // Wait for end of stream; some decoders never flag EOS, so also stop after 5 s without output
            // once all input is in.
            while (!done.isCompleted) {
                withTimeoutOrNull(1_000) { done.await() }
                if (!done.isCompleted && System.nanoTime() - callback.lastActivity > 5_000_000_000L) {
                    if (callback.inputDone) break
                    throw AudioDecodeException("녹음 파일을 읽다가 멈췄어요")
                }
            }
            if (done.isCompleted) done.await() // rethrows a failure from the callbacks
            onProgress(1f)
        } catch (e: AudioDecodeException) {
            throw e
        } catch (e: MediaCodec.CodecException) {
            throw AudioDecodeException("녹음 파일을 읽다가 오류가 났어요", e)
        } catch (e: IllegalStateException) {
            throw AudioDecodeException("녹음 파일을 읽다가 오류가 났어요", e)
        } catch (e: IOException) {
            throw AudioDecodeException("녹음 파일을 읽다가 오류가 났어요", e)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            // No callback may still be touching the extractor or the sink once we return.
            thread.quitSafely()
            thread.join(2_000)
            extractor.release()
        }
    }

    /** Feeds the codec from [extractor] and turns its output into 16 kHz mono for [sink]; runs on the codec thread. */
    private class CodecCallback(
        private val extractor: MediaExtractor,
        private val durationUs: Long,
        private val sink: (FloatArray) -> Unit,
        private val onProgress: (Float) -> Unit,
        private val done: CompletableDeferred<Unit>,
    ) : MediaCodec.Callback() {
        @Volatile var inputDone = false
        @Volatile var lastActivity = System.nanoTime()
        private var outFormat: MediaFormat? = null
        private var resampler: StreamingResampler? = null
        private var resamplerRate = 0
        private var reported = -1

        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            if (inputDone || done.isCompleted) return
            lastActivity = System.nanoTime()
            try {
                val n = extractor.readSampleData(c.getInputBuffer(index)!!, 0)
                if (n < 0) {
                    c.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    c.queueInputBuffer(index, 0, n, extractor.sampleTime, 0)
                    extractor.advance()
                }
            } catch (e: Exception) {
                done.completeExceptionally(e)
            }
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (done.isCompleted) return
            lastActivity = System.nanoTime()
            try {
                if (info.size > 0) {
                    val fmt = outFormat ?: c.outputFormat.also { outFormat = it }
                    val buffer = c.getOutputBuffer(index)!!
                    buffer.position(info.offset).limit(info.offset + info.size)
                    val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    val encoding = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    val mono = toMono(buffer.order(ByteOrder.nativeOrder()), channels, encoding)
                    val current = resampler
                    val r = if (current != null && rate == resamplerRate) current else {
                        current?.let { emit(it.flush(), sink) }
                        StreamingResampler(rate, SAMPLE_RATE).also { resampler = it; resamplerRate = rate }
                    }
                    emit(r.process(mono), sink)
                    if (durationUs > 0) {
                        // Publishing progress per AAC frame (23 ms) was measurable; report per 0.2%.
                        val step = (info.presentationTimeUs * 500 / durationUs).toInt()
                        if (step != reported) { reported = step; onProgress((step / 500f).coerceIn(0f, 1f)) }
                    }
                }
                c.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    resampler?.let { emit(it.flush(), sink) }
                    done.complete(Unit)
                }
            } catch (e: Exception) {
                done.completeExceptionally(e)
            }
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) { outFormat = format }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) { done.completeExceptionally(e) }
    }

    private fun emit(samples: FloatArray, sink: (FloatArray) -> Unit) { if (samples.isNotEmpty()) sink(samples) }

    /** Interleaved PCM (16-bit or float) to mono floats by averaging the channels. */
    private fun toMono(buffer: ByteBuffer, channels: Int, encoding: Int): FloatArray = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> {
            val fb = buffer.asFloatBuffer()
            val frames = fb.remaining() / channels
            FloatArray(frames) { i -> var s = 0f; for (c in 0 until channels) s += fb.get(i * channels + c); s / channels }
        }
        else -> {
            val sb = buffer.asShortBuffer()
            val frames = sb.remaining() / channels
            FloatArray(frames) { i -> var s = 0f; for (c in 0 until channels) s += sb.get(i * channels + c); s / (32768f * channels) }
        }
    }

    // ---- WAV path ----

    private suspend fun decodeWav(file: File, sink: (FloatArray) -> Unit, onProgress: (Float) -> Unit) {
        BufferedInputStream(file.inputStream(), 1 shl 16).use { input ->
            val header = WavReader.readHeader(input) ?: throw AudioDecodeException("WAV 파일 형식을 읽을 수 없어요")
            val resampler = StreamingResampler(header.sampleRate, SAMPLE_RATE)
            val frameBytes = header.channels * header.bytesPerSample
            val buf = ByteArray(frameBytes * 8192)
            var remaining = if (header.dataBytes > 0) header.dataBytes else Long.MAX_VALUE
            val totalBytes = if (header.dataBytes > 0) header.dataBytes else file.length()
            var read = 0L
            while (remaining > 0) {
                currentCoroutineContext().ensureActive()
                val want = minOf(buf.size.toLong(), remaining).toInt() / frameBytes * frameBytes
                if (want == 0) break
                val n = readFully(input, buf, want)
                if (n <= 0) break
                val usable = n / frameBytes * frameBytes
                emit(resampler.process(WavReader.toMono(buf, usable, header)), sink)
                remaining -= n
                read += n
                onProgress((read.toFloat() / totalBytes).coerceIn(0f, 1f))
                if (n < want) break
            }
            emit(resampler.flush(), sink)
            onProgress(1f)
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Int {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) break
            off += n
        }
        return off
    }
}

/** Minimal RIFF/WAVE parser: PCM 8/16/24/32-bit, IEEE float 32-bit, WAVE_FORMAT_EXTENSIBLE. */
internal object WavReader {
    data class Header(val channels: Int, val sampleRate: Int, val bytesPerSample: Int, val isFloat: Boolean, val dataBytes: Long)

    /** Reads up to the start of the "data" chunk; null if the format is unsupported. */
    fun readHeader(input: InputStream): Header? {
        val riff = ByteArray(12)
        if (readExactly(input, riff) < 12) return null
        var channels = 0; var rate = 0; var bits = 0; var format = 0
        val chunkHeader = ByteArray(8)
        while (true) {
            if (readExactly(input, chunkHeader) < 8) return null
            val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            when (id) {
                "fmt " -> {
                    val body = ByteArray(size.toInt())
                    if (readExactly(input, body) < body.size) return null
                    if (size % 2 == 1L) input.read()
                    val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                    format = bb.getShort(0).toInt() and 0xFFFF
                    channels = bb.getShort(2).toInt()
                    rate = bb.getInt(4)
                    bits = bb.getShort(14).toInt()
                    if (format == 0xFFFE && size >= 26) format = bb.getShort(24).toInt() and 0xFFFF
                }
                "data" -> {
                    if (channels <= 0 || rate <= 0) return null
                    val isFloat = format == 3
                    if (!(format == 1 && bits in listOf(8, 16, 24, 32)) && !(isFloat && bits == 32)) return null
                    // 0 or 0xFFFFFFFF: size unknown (streamed recorder) -> read to the end of the file.
                    val dataBytes = if (size == 0L || size == 0xFFFFFFFFL) 0L else size
                    return Header(channels, rate, bits / 8, isFloat, dataBytes)
                }
                else -> skip(input, size + (size and 1))
            }
        }
    }

    fun toMono(buf: ByteArray, len: Int, h: Header): FloatArray {
        val bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        val frames = len / (h.channels * h.bytesPerSample)
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until h.channels) {
                val p = (i * h.channels + c) * h.bytesPerSample
                acc += when {
                    h.isFloat -> bb.getFloat(p)
                    h.bytesPerSample == 1 -> ((buf[p].toInt() and 0xFF) - 128) / 128f
                    h.bytesPerSample == 2 -> bb.getShort(p) / 32768f
                    h.bytesPerSample == 3 -> ((buf[p].toInt() and 0xFF) or ((buf[p + 1].toInt() and 0xFF) shl 8) or (buf[p + 2].toInt() shl 16)) / 8388608f
                    else -> bb.getInt(p) / 2147483648f
                }
            }
            out[i] = acc / h.channels
        }
        return out
    }

    private fun readExactly(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun skip(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) { if (input.read() < 0) throw EOFException(); left-- } else left -= s
        }
    }
}
