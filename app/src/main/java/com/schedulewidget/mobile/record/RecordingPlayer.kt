package com.schedulewidget.mobile.record

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.music.BoostEffect
import com.schedulewidget.mobile.music.VolumeBoost
import kotlinx.coroutines.delay

/** One MediaPlayer for the recordings screen: plays one recording at a time; state is Compose-observable. */
@Stable
class RecordingPlayer {
    var currentId by mutableStateOf<String?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    private var player: MediaPlayer? = null
    private var boostEffect: BoostEffect? = null

    /** Set by [rememberRecordingPlayer]; needed to create the boost effect (and its "unsupported" toast). */
    internal var appContext: Context? = null

    /** Volume boost 100–300 % (AppData.volumeBoost, shared with the music player); 100 attaches no effect. */
    var boost: Int = VolumeBoost.MIN
        set(v) {
            field = VolumeBoost.clamp(v)
            applyBoost()
        }

    private fun applyBoost() {
        val p = player
        val ctx = appContext
        if (p == null || ctx == null || boost <= VolumeBoost.MIN) {
            boostEffect?.release()
            boostEffect = null
            return
        }
        val session = runCatching { p.audioSessionId }.getOrDefault(0)
        val fx = boostEffect?.takeIf { it.sessionId == session }
            ?: BoostEffect(ctx, session).also { boostEffect?.release(); boostEffect = it }
        fx.apply(boost)
    }

    /** In-app playback volume 0..1 (AppData.stt.playbackVolume), independent of the system volume and the mic gain. */
    var volume: Float = 1f
        set(v) {
            field = v.coerceIn(0f, 1f)
            player?.let { p -> runCatching { p.setVolume(field, field) } }
        }

    fun isCurrent(item: RecordingItem) = currentId == item.id && player != null

    fun toggle(item: RecordingItem) {
        val p = player
        if (p != null && currentId == item.id) {
            if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true }
        } else load(item, startAt = 0L)
    }

    /** Jumps to [ms] in [item] and plays (loading it first when another recording was playing). */
    fun seek(item: RecordingItem, ms: Long, play: Boolean = true) {
        val p = player
        if (p == null || currentId != item.id) return load(item, startAt = ms, play = play)
        p.seekTo(ms.coerceAtLeast(0L), MediaPlayer.SEEK_CLOSEST)
        positionMs = ms
        if (play && !p.isPlaying) { p.start(); playing = true }
    }

    private fun load(item: RecordingItem, startAt: Long, play: Boolean = true) {
        release()
        val p = MediaPlayer()
        val ok = runCatching {
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            p.setDataSource(item.audio.absolutePath)
            p.prepare()
        }.isSuccess
        if (!ok) { runCatching { p.release() }; return }
        p.setVolume(volume, volume)
        p.setOnCompletionListener { playing = false; positionMs = 0L; runCatching { it.seekTo(0) } }
        player = p
        applyBoost()
        currentId = item.id
        durationMs = p.duration.toLong().coerceAtLeast(0L)
        if (startAt > 0) p.seekTo(startAt, MediaPlayer.SEEK_CLOSEST)
        positionMs = startAt
        if (play) { p.start(); playing = true }
    }

    /** Called often while playing to move the seek bar. */
    fun tick() {
        val p = player ?: return
        runCatching { positionMs = p.currentPosition.toLong() }
    }

    fun stopIf(item: RecordingItem) { if (currentId == item.id) release() }

    fun release() {
        boostEffect?.release()
        boostEffect = null
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        currentId = null
        playing = false
        positionMs = 0L
        durationMs = 0L
    }
}

@Composable
fun rememberRecordingPlayer(): RecordingPlayer {
    val context = LocalContext.current
    val player = remember { RecordingPlayer().also { it.appContext = context.applicationContext } }
    DisposableEffect(player) { onDispose { player.release() } }
    val saved by remember { Repository.get(context) }.data.collectAsStateWithLifecycle()
    val volume = PlaybackVolume.draft ?: saved.stt.playbackVolume
    LaunchedEffect(volume) { player.volume = volume.coerceIn(0, 100) / 100f }
    val boost by remember { VolumeBoost.inAppFlow(context) }.collectAsStateWithLifecycle(saved.volumeBoost)
    LaunchedEffect(boost) { player.boost = boost }
    LaunchedEffect(player.playing) {
        while (player.playing) {
            player.tick()
            delay(100) // ~10 updates a second for the seek bar and subtitles; nothing runs while paused
        }
    }
    return player
}

/** Play/pause and seek controls for one recording. */
@Composable
fun PlayerControls(item: RecordingItem, player: RecordingPlayer, modifier: Modifier = Modifier) =
    RecordingPlayerControls(item, player, modifier)

/** Shared volume-boost control for recording playback. */
@Composable
fun PlaybackBoostSetting(modifier: Modifier = Modifier, compact: Boolean = false) =
    RecordingPlaybackBoostSetting(modifier, compact)
