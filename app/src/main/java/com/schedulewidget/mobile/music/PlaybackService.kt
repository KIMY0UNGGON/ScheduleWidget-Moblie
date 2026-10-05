package com.schedulewidget.mobile.music

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.schedulewidget.mobile.MainActivity
import com.schedulewidget.mobile.ui.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Plays local playlist files (content:// URIs) for [WidgetPlayer]. The session holds one item at a time; the
 * playlist logic stays in WidgetPlayer, so next/previous from the notification or lock screen are forwarded there.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Volume boost above 100 % on the player's audio session (see [VolumeBoost]); null at 100 % or when idle. */
    private var boost: BoostEffect? = null
    private var boostPercent = VolumeBoost.MIN
    private var playerActive = false

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        // A fixed session id generated up front, so the boost effect can be attached before the first track.
        val sessionId = runCatching { getSystemService(AudioManager::class.java)?.generateAudioSessionId() }.getOrNull()
        if (sessionId != null && sessionId > 0) exo.setAudioSessionId(sessionId)
        exo.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                boost?.release()
                boost = null
                updateBoost(audioSessionId)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // The effect only lives while something is loaded; stop / end releases it.
                playerActive = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY
                updateBoost(exo.audioSessionId)
            }
        })
        scope.launch {
            VolumeBoost.inAppFlow(this@PlaybackService).collect {
                boostPercent = it
                updateBoost(exo.audioSessionId)
            }
        }
        val player = PlaylistForwardingPlayer(exo)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ROUTE, Route.Playlists.name)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
    }

    private fun updateBoost(sessionId: Int) {
        if (!playerActive || boostPercent <= VolumeBoost.MIN || sessionId <= 0) {
            boost?.release()
            boost = null
            return
        }
        val fx = boost?.takeIf { it.sessionId == sessionId }
            ?: BoostEffect(this, sessionId).also { boost?.release(); boost = it }
        fx.apply(boostPercent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        boost?.release()
        boost = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    @OptIn(UnstableApi::class)
    private inner class PlaylistForwardingPlayer(player: Player) : ForwardingPlayer(player) {
        private val extra = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            ).build()

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(extra).build()

        override fun isCommandAvailable(command: Int): Boolean = extra.contains(command) || super.isCommandAvailable(command)

        override fun seekToNext() = WidgetPlayer.next(this@PlaybackService)
        override fun seekToNextMediaItem() = WidgetPlayer.next(this@PlaybackService)
        override fun seekToPrevious() = WidgetPlayer.previous(this@PlaybackService)
        override fun seekToPreviousMediaItem() = WidgetPlayer.previous(this@PlaybackService)
    }
}
