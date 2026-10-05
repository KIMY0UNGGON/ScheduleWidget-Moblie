package com.schedulewidget.mobile.music

import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.schedulewidget.mobile.data.Repository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.log10
import kotlin.math.roundToInt

private const val TAG = "VolBoost"

/**
 * "증폭": loudness above 100 %. One setting, AppData.volumeBoost (100–300 %), applied live.
 *
 * Only audio this app renders itself is boosted: per-session effects on our own players (widget-playlist files in
 * [PlaybackService], recordings in RecordingPlayer). An output-mix (session 0) effect for other apps was tried and
 * dropped: streaming music is already mastered near full scale, so the extra gain only made the limiter squash it
 * (quieter, crackling on the S23 Ultra).
 */
object VolumeBoost {
    const val MIN = 100
    const val MAX = 300
    const val STEP = 10
    /** Above this the UI warns about speakers and ears. */
    const val WARN_ABOVE = 200

    /** The dragged, not yet saved boost (percent); effects follow it so the change is heard right away. */
    val draft = MutableStateFlow<Int?>(null)

    fun clamp(percent: Int): Int = percent.coerceIn(MIN, MAX)

    /** Amplitude gain in dB: 200 % = +6.0 dB, 300 % = +9.5 dB. */
    fun gainDb(percent: Int): Float = (20 * log10(clamp(percent) / 100.0)).toFloat()

    /** "+6.0 dB" */
    fun label(percent: Int): String = "+%.1f dB".format(gainDb(percent))

    /** Effective boost: the draft while a slider is dragged, otherwise the saved value. */
    fun flow(context: Context): Flow<Int> =
        combine(Repository.get(context).data.map { it.volumeBoost }, draft) { saved, d -> clamp(d ?: saved) }
            .distinctUntilChanged()

    /** Boost for our own players' sessions. */
    fun inAppFlow(context: Context): Flow<Int> = flow(context)

    fun save(context: Context, percent: Int) {
        val v = clamp((percent.toFloat() / STEP).roundToInt() * STEP)
        Repository.get(context).update { if (it.volumeBoost == v) it else it.copy(volumeBoost = v) }
        draft.value = null
    }

    @Volatile private var warned = false

    /** Shown at most once per process when no boost effect can be created on this device. */
    internal fun warnUnsupported(context: Context) {
        if (warned) return
        warned = true
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(app, "이 기기에서는 음량 증폭을 지원하지 않아요", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * The boost effect for one audio session. Nothing is attached at 100 %, so normal volume behaviour is untouched.
 *
 * Preferred: DynamicsProcessing (API 28+) with only the input-gain stage and a stereo-linked limiter (no EQ, no
 * multiband compressor). The gain is applied cleanly and the limiter only acts on peaks that would exceed
 * -1 dBFS, with a fast attack and a short release, so quiet and mid passages keep their dynamics and loud peaks
 * are rounded off instead of clipping. LoudnessEnhancer is the fallback: it also limits, but its compressor curve
 * is fixed by the device and tends to pump more on dense music.
 *
 * [global] = session 0 (output mix): failures are reported through [active] instead of a toast.
 */
class BoostEffect(context: Context, val sessionId: Int, private val global: Boolean = false) {
    private val app = context.applicationContext
    private var dynamics: Any? = null // DynamicsProcessing; typed Any so this class loads on API 26-27
    private var enhancer: LoudnessEnhancer? = null
    private var unsupported = false

    /** True while an effect is attached and enabled. */
    val active: Boolean get() = !unsupported && (dynamics != null || enhancer != null)

    /** Applies [percent] (100–300); 100 releases the effect. Safe to call repeatedly. */
    fun apply(percent: Int) {
        val p = VolumeBoost.clamp(percent)
        if (p <= VolumeBoost.MIN || (sessionId <= 0 && !global)) return release()
        if (unsupported) return
        val db = VolumeBoost.gainDb(p)
        if (dynamics == null && enhancer == null) create(db)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dynamics != null) {
            val r = runCatching { (dynamics as DynamicsProcessing).setInputGainAllChannelsTo(db) }
            Log.d(TAG, "session=$sessionId DynamicsProcessing gain=${"%.1f".format(db)}dB ok=${r.isSuccess}")
            if (r.isFailure) fail(r.exceptionOrNull())
            return
        }
        enhancer?.let { e ->
            val r = runCatching { e.setTargetGain((db * 100).roundToInt()) }
            Log.d(TAG, "session=$sessionId LoudnessEnhancer gain=${"%.1f".format(db)}dB ok=${r.isSuccess}")
            if (r.isFailure) fail(r.exceptionOrNull())
        }
    }

    fun release() {
        if (dynamics != null || enhancer != null) Log.d(TAG, "session=$sessionId released")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) (dynamics as? DynamicsProcessing)?.let { runCatching { it.release() } }
        enhancer?.let { runCatching { it.release() } }
        dynamics = null
        enhancer = null
    }

    private fun create(db: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val r = runCatching { createDynamics(db) }
            Log.d(TAG, "session=$sessionId create DynamicsProcessing ok=${r.isSuccess} ${r.exceptionOrNull() ?: ""}")
            dynamics = r.getOrNull()
            if (dynamics != null) return
        }
        val r = runCatching {
            val e = LoudnessEnhancer(sessionId)
            try {
                e.setTargetGain((db * 100).roundToInt()) // millibels
                e.enabled = true
                check(e.hasControl() && e.enabled) { "no control" }
            } catch (t: Throwable) {
                runCatching { e.release() }
                throw t
            }
            e
        }
        Log.d(TAG, "session=$sessionId create LoudnessEnhancer ok=${r.isSuccess} ${r.exceptionOrNull() ?: ""}")
        enhancer = r.getOrNull()
        if (enhancer == null) fail(r.exceptionOrNull())
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun createDynamics(db: Float): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION, CHANNELS,
            false, 0, // pre-EQ
            false, 0, // multiband compressor
            false, 0, // post-EQ
            true, // limiter
        ).build()
        val fx = DynamicsProcessing(0, sessionId, config)
        try {
            // linkGroup 0 on every channel: both channels are limited together, keeping the stereo image.
            fx.setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(true, true, 0, ATTACK_MS, RELEASE_MS, RATIO, THRESHOLD_DB, 0f)
            )
            fx.setInputGainAllChannelsTo(db)
            fx.enabled = true
            check(fx.hasControl() && fx.enabled) { "no control" }
        } catch (t: Throwable) {
            runCatching { fx.release() }
            throw t
        }
        return fx
    }

    private fun fail(t: Throwable?) {
        Log.w(TAG, "session=$sessionId boost unsupported", t)
        release()
        unsupported = true
        if (!global) VolumeBoost.warnUnsupported(app)
    }

    private companion object {
        const val CHANNELS = 2
        const val ATTACK_MS = 1f
        const val RELEASE_MS = 60f
        // Ratio 20: even a full-scale peak at +9.5 dB lands at about -0.5 dBFS, below hard clipping.
        const val RATIO = 20f
        const val THRESHOLD_DB = -1f
    }
}
