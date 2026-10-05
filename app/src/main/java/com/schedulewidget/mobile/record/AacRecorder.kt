package com.schedulewidget.mobile.record

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Process
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.tanh

/**
 * AudioRecord (16-bit PCM) → software gain with a soft limiter → MediaCodec AAC-LC → MediaMuxer (.m4a), on its own
 * thread. MediaRecorder has no input gain, hence this. [gain] is read for every buffer, so changes apply live.
 *
 * The MP4 index is written when [stop] finalizes the muxer; a process killed mid-recording leaves an unreadable file,
 * so the service always stops through [stop] (on 정지, low storage, errors and service destruction).
 */
class AacRecorder(
    private val file: File,
    private val audioSource: Int,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitRate: Int,
    /** Linear gain (1.0 = as recorded). */
    private val gain: () -> Float,
    /** Stop once the file reaches this many bytes (storage nearly full / MP4 4 GB limit). */
    private val maxBytes: Long,
    /** Peak level after gain (dBFS, -90..0) and whether that buffer had to be limited; about 10 times a second. */
    private val onLevel: (peakDb: Float, clipped: Boolean) -> Unit,
    /** The recorder stopped on its own: [full] = size limit, otherwise an error. Called on the recording thread. */
    private val onStoppedItself: (full: Boolean) -> Unit,
) {
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var track = -1
    private var muxerStarted = false
    private var writtenBytes = 0L
    private var samplesWritten = 0
    @Volatile private var framesRecorded = 0L

    /** Recorded length so far. */
    val durationMs: Long get() = framesRecorded * 1000 / sampleRate

    /** Sets everything up and starts the thread; false (with everything released) when the device refuses. */
    @SuppressLint("MissingPermission") // checked by the service before starting
    fun start(): Boolean {
        val channelMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val min = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val ok = runCatching {
            // Half a second of buffer: the encoder or a slow disk can stall briefly without losing audio.
            val r = AudioRecord(audioSource, sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 4, sampleRate * channels))
            record = r
            check(r.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME * channels * 2 * 4)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = c
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            c.start()
            r.startRecording()
            check(r.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "microphone busy" }
        }
        if (ok.isFailure) {
            Log.w(TAG, "start failed (source $audioSource)", ok.exceptionOrNull())
            releaseAll()
            file.delete()
            return false
        }
        running = true
        thread = Thread({ loop() }, "lecture-recorder").apply { start() }
        return true
    }

    /** Stops, drains the encoder and finalizes the file. True when the file holds audio and is playable. */
    fun stop(): Boolean {
        running = false
        val t = thread
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(5_000) }
        thread = null
        val ok = finish()
        return ok
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val r = record ?: return
        val buf = ShortArray(FRAME * channels)
        var levelPeak = 0f
        var levelClipped = false
        var levelSamples = 0
        var stoppedItself: Boolean? = null
        try {
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n < 0) { Log.w(TAG, "read error $n"); stoppedItself = false; break }
                if (n == 0) continue
                // Gain + soft knee above -1 dBFS: loud peaks are rounded off instead of clipping harshly.
                val g = gain()
                for (i in 0 until n) {
                    var s = buf[i] / 32768f * g
                    val a = abs(s)
                    if (a > KNEE) {
                        if (a >= 1f) levelClipped = true
                        val limited = KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE))
                        s = if (s < 0) -limited else limited
                    }
                    val m = abs(s)
                    if (m > levelPeak) levelPeak = m
                    buf[i] = (s * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }
                levelSamples += n / channels
                if (levelSamples >= sampleRate / 10) {
                    onLevel(if (levelPeak <= 0.00003f) -90f else (20 * log10(levelPeak)).coerceAtLeast(-90f), levelClipped)
                    levelPeak = 0f; levelClipped = false; levelSamples = 0
                }
                feed(buf, n, endOfStream = false)
                if (writtenBytes >= maxBytes) { stoppedItself = true; break }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "recording failed", e)
            stoppedItself = false
        }
        // Drain here, on this thread, so stop() only has to finalize.
        runCatching { record?.stop() }
        runCatching { feed(ShortArray(0), 0, endOfStream = true) }
        if (stoppedItself != null && running) {
            running = false
            onStoppedItself(stoppedItself)
        }
    }

    /** Queues [n] samples (or end of stream) into the encoder, draining output whenever it has no free input. */
    private fun feed(buf: ShortArray, n: Int, endOfStream: Boolean) {
        val c = codec ?: return
        var offset = 0
        var guard = 0
        while (offset < n || endOfStream) {
            val index = c.dequeueInputBuffer(10_000)
            if (index < 0) {
                drain(false)
                if (++guard > 500) return // encoder stuck; give up on this buffer rather than hang
                continue
            }
            val input = c.getInputBuffer(index) ?: return
            input.clear()
            val count = minOf(n - offset, input.remaining() / 2 / channels * channels)
            input.order(ByteOrder.nativeOrder()).asShortBuffer().put(buf, offset, count)
            input.position(count * 2)
            val pts = framesRecorded * 1_000_000 / sampleRate
            framesRecorded += count / channels
            offset += count
            val eos = endOfStream && offset >= n
            c.queueInputBuffer(index, 0, count * 2, pts, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
            if (eos) { drain(true); return }
        }
        drain(false)
    }

    private val info = MediaCodec.BufferInfo()
    private var lastPts = -1L

    private fun drain(endOfStream: Boolean) {
        val c = codec ?: return
        val m = muxer ?: return
        var idle = 0
        while (true) {
            val index = c.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream || ++idle > 200) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        track = m.addTrack(c.outputFormat)
                        m.start()
                        muxerStarted = true
                    }
                }
                index >= 0 -> {
                    val out: ByteBuffer? = c.getOutputBuffer(index)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (out != null && !config && info.size > 0 && muxerStarted) {
                        // The muxer needs strictly increasing timestamps.
                        if (info.presentationTimeUs <= lastPts) info.presentationTimeUs = lastPts + 1
                        lastPts = info.presentationTimeUs
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        m.writeSampleData(track, out, info)
                        writtenBytes += info.size
                        samplesWritten++
                    }
                    c.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    @Synchronized
    private fun finish(): Boolean {
        val hadAudio = muxerStarted && samplesWritten > 0
        var finalized = false
        muxer?.let { m ->
            if (muxerStarted) finalized = runCatching { m.stop() }.isSuccess
            runCatching { m.release() }
        }
        muxer = null
        muxerStarted = false
        releaseAll()
        return hadAudio && finalized
    }

    private fun releaseAll() {
        record?.let { runCatching { it.stop() }; runCatching { it.release() } }
        record = null
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        muxer?.let { runCatching { it.release() } }
        muxer = null
    }

    companion object {
        private const val TAG = "AacRecorder"
        /** Frames per read: ~21 ms at 48 kHz, one AAC frame. */
        private const val FRAME = 1024
        private const val KNEE = 0.89f // -1 dBFS
    }
}
