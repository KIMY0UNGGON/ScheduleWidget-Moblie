package com.schedulewidget.mobile.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import com.schedulewidget.mobile.data.AppData
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.music.NowPlaying
import com.schedulewidget.mobile.music.WidgetPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Unified control over the current playback source:
 * AppData.musicSource == "widget" -> delegate to music.WidgetPlayer; otherwise a package name -> that app's
 * MediaController (via MediaListenerService / MediaSessionManager).
 * Used by the in-app MusicBar and by the home-screen music widget.
 */
object MusicHub {
    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state

    /** NowPlaying of every app that currently has an active media session, keyed by package. */
    private val _sessions = MutableStateFlow<Map<String, NowPlaying>>(emptyMap())
    val sessions: StateFlow<Map<String, NowPlaying>> = _sessions

    private val _systemVolume = MutableStateFlow(0.5f)

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var appContext: Context
    private var sessionManager: MediaSessionManager? = null
    private var listenerRegistered = false
    // Mutated only on the main thread, but read from widget receivers / coroutine threads (transport(), queue(), label()).
    private val controllers = java.util.concurrent.ConcurrentHashMap<String, MediaController>()
    private val callbacks = java.util.concurrent.ConcurrentHashMap<String, MediaController.Callback>()
    private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> onSessionsChanged(list.orEmpty()) }

    fun init(context: Context) {
        if (::appContext.isInitialized) return refresh(context)
        appContext = context.applicationContext
        sessionManager = appContext.getSystemService(MediaSessionManager::class.java)
        readSystemVolume()
        appContext.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = readSystemVolume()
        })
        val repo = Repository.get(appContext)
        val source = repo.data.map { it.musicSource to it.musicApps }.distinctUntilChanged()
        scope.launch {
            combine(source, WidgetPlayer.state, _sessions, _systemVolume) { (src, apps), widget, sessions, vol ->
                val id = if (src == AppData.SOURCE_WIDGET || src !in apps) AppData.SOURCE_WIDGET else src
                if (id == AppData.SOURCE_WIDGET) widget.copy(sourceId = id, sourceLabel = WIDGET_LABEL)
                else (sessions[id] ?: NowPlaying(sourceId = id)).copy(sourceLabel = label(id), volume = vol)
            }.collect { _state.value = it }
        }
        refresh(appContext)
    }

    /** Re-tries session tracking (e.g. after the user grants notification access). */
    fun refresh(context: Context) {
        if (!::appContext.isInitialized) return init(context)
        main.post {
            val sm = sessionManager ?: return@post
            if (!hasNotificationAccess(appContext)) {
                // Access revoked: drop the listener and every controller so stale sessions don't linger.
                if (listenerRegistered) runCatching { sm.removeOnActiveSessionsChangedListener(sessionsListener) }
                listenerRegistered = false
                onSessionsChanged(emptyList())
                return@post
            }
            val component = ComponentName(appContext, MediaListenerService::class.java)
            runCatching {
                if (!listenerRegistered) {
                    sm.addOnActiveSessionsChangedListener(sessionsListener, component, main)
                    listenerRegistered = true
                }
                onSessionsChanged(sm.getActiveSessions(component))
            }
        }
    }

    fun hasNotificationAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun notificationAccessIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun onSessionsChanged(list: List<MediaController>) {
        val byPkg = list.groupBy { it.packageName }.mapValues { it.value.first() }
        (controllers.keys - byPkg.keys).forEach { pkg ->
            callbacks.remove(pkg)?.let { cb -> controllers[pkg]?.unregisterCallback(cb) }
            controllers.remove(pkg)
        }
        byPkg.forEach { (pkg, c) ->
            val old = controllers[pkg]
            if (old != null && old.sessionToken == c.sessionToken) return@forEach
            callbacks.remove(pkg)?.let { old?.unregisterCallback(it) }
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                override fun onSessionDestroyed() {
                    c.unregisterCallback(this)
                    // A newer session of the same app may already have replaced this one.
                    if (callbacks.remove(pkg, this)) controllers.remove(pkg, c)
                    publish()
                }
            }
            c.registerCallback(cb, main)
            controllers[pkg] = c
            callbacks[pkg] = cb
        }
        publish()
    }

    private val wasPlaying = HashSet<String>()

    private fun publish() {
        val now = controllers.mapValues { (pkg, c) -> snapshot(pkg, c) }
        _sessions.value = now
        // A music app that starts playing on its own (the user opened it and pressed play) becomes the MP3 tool's
        // source, so its own playlist is what the tool shows and controls — not the widget playlist.
        val started = now.filter { (pkg, np) -> np.isPlaying && pkg !in wasPlaying }.keys
        wasPlaying.retainAll(now.filterValues { it.isPlaying }.keys)
        wasPlaying.addAll(started)
        if (!::appContext.isInitialized) return
        for (pkg in started) {
            if (pkg == appContext.packageName || pkg == WidgetPlayer.followingApp) continue
            val d = Repository.get(appContext).data.value
            if (pkg == d.musicSource || (pkg !in d.musicApps && pkg !in KNOWN_MUSIC_APPS)) continue
            if (d.musicSource == AppData.SOURCE_WIDGET && WidgetPlayer.state.value.isPlaying) WidgetPlayer.pause(appContext)
            followApp(appContext, pkg)
            break
        }
    }

    /** Makes [pkg] the current source without pausing anything (it is the one playing now). */
    fun followApp(context: Context, pkg: String) {
        Repository.get(context).update { d ->
            if (d.musicSource == pkg) d
            else d.copy(musicSource = pkg, musicApps = if (pkg in d.musicApps) d.musicApps else d.musicApps + pkg)
        }
    }

    private fun snapshot(pkg: String, c: MediaController): NowPlaying {
        val md = c.metadata
        val ps = c.playbackState
        fun text(vararg keys: String) = keys.firstNotNullOfOrNull { k -> md?.getString(k)?.takeIf { it.isNotBlank() } }.orEmpty()
        val playing = ps?.state == PlaybackState.STATE_PLAYING || ps?.state == PlaybackState.STATE_BUFFERING
        return NowPlaying(
            sourceId = pkg,
            sourceLabel = label(pkg),
            title = text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            isPlaying = playing,
            positionMs = ps?.position?.coerceAtLeast(0) ?: 0,
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0,
            updatedAt = ps?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            canSeek = ps != null && ps.actions and PlaybackState.ACTION_SEEK_TO != 0L,
            volume = _systemVolume.value,
        )
    }

    private fun currentSource(context: Context): String {
        val d = Repository.get(context).data.value
        return if (d.musicSource != AppData.SOURCE_WIDGET && d.musicSource in d.musicApps) d.musicSource else AppData.SOURCE_WIDGET
    }

    private fun controller(pkg: String): MediaController? = controllers[pkg]

    /** Transport controls of [pkg]'s active media session (null when it has none or access is not granted). */
    fun transport(pkg: String): MediaController.TransportControls? = controllers[pkg]?.transportControls

    /**
     * One entry of another app's play queue (e.g. YouTube Music's "다음 트랙"). [videoId] when it is a YouTube video,
     * [spotifyUri] (spotify:track:...) when it is a Spotify song.
     */
    data class QueueTrack(val title: String, val artist: String, val videoId: String?, val spotifyUri: String? = null)

    private val videoIdRegex = Regex("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{11}(?![A-Za-z0-9_-])")

    /**
     * The play queue [pkg] publishes through its media session, and the index of the song playing now (-1 if unknown).
     * Null when the app has no session / queue or notification access is missing.
     */
    fun queue(pkg: String): Pair<List<QueueTrack>, Int>? {
        val c = controllers[pkg] ?: return null
        val items = c.queue?.takeIf { it.isNotEmpty() } ?: return null
        val active = c.playbackState?.activeQueueItemId ?: -1L
        val tracks = items.map { q ->
            val d = q.description
            val raw = listOfNotNull(d.mediaUri?.toString(), d.mediaId).joinToString(" ")
            val spotify = if (pkg == AppData.SPOTIFY_APP) com.schedulewidget.mobile.data.Spotify.parse(raw)?.uri else null
            // YouTube Music media ids carry the 11-character video id (sometimes with a prefix/suffix).
            val id = if (pkg == AppData.SPOTIFY_APP) null
            else com.schedulewidget.mobile.data.YouTube.videoId(raw) ?: videoIdRegex.findAll(raw).map { it.value }.lastOrNull()
            QueueTrack(d.title?.toString().orEmpty(), d.subtitle?.toString().orEmpty(), id, spotify)
        }
        return tracks to items.indexOfFirst { it.queueId == active }
    }

    fun playPause(context: Context) {
        val src = currentSource(context)
        if (src == AppData.SOURCE_WIDGET) return WidgetPlayer.playPause(context)
        val c = controller(src)
        if (c == null) {
            startApp(context, src)
            return
        }
        // Same notion of "playing" as snapshot(): the UI shows a pause button while buffering too.
        val st = c.playbackState?.state
        if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next(context: Context) {
        val src = currentSource(context)
        if (src == AppData.SOURCE_WIDGET) return WidgetPlayer.next(context)
        controller(src)?.transportControls?.skipToNext() ?: sendMediaButton(context, src, KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun previous(context: Context) {
        val src = currentSource(context)
        if (src == AppData.SOURCE_WIDGET) return WidgetPlayer.previous(context)
        controller(src)?.transportControls?.skipToPrevious() ?: sendMediaButton(context, src, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    fun seekTo(context: Context, positionMs: Long) {
        val src = currentSource(context)
        if (src == AppData.SOURCE_WIDGET) return WidgetPlayer.seekTo(context, positionMs)
        controller(src)?.transportControls?.seekTo(positionMs.coerceAtLeast(0))
    }

    /** 0..1. Widget source: player volume. App source: system media (STREAM_MUSIC) volume. */
    fun setVolume(context: Context, volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        if (currentSource(context) == AppData.SOURCE_WIDGET) return WidgetPlayer.setVolume(context, v)
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v * max), 0) }
        _systemVolume.value = v
    }

    /** "widget" or a package name. Switching pauses the previous source, like the desktop app. */
    fun setSource(context: Context, sourceId: String) {
        val prev = currentSource(context)
        if (prev == sourceId) return
        if (prev == AppData.SOURCE_WIDGET) WidgetPlayer.pause(context) else controller(prev)?.transportControls?.pause()
        Repository.get(context).update { d ->
            val apps = if (sourceId == AppData.SOURCE_WIDGET || sourceId in d.musicApps) d.musicApps else d.musicApps + sourceId
            d.copy(musicSource = sourceId, musicApps = apps)
        }
    }

    /** Sources available right now: "widget" first, then AppData.musicApps (installed ones). */
    fun sources(context: Context): List<MusicSource> {
        val d = Repository.get(context).data.value
        val pm = context.packageManager
        val apps = d.musicApps.filter { runCatching { pm.getApplicationInfo(it, 0) }.isSuccess }
        val live = _sessions.value
        return listOf(MusicSource(AppData.SOURCE_WIDGET, WIDGET_LABEL, WidgetPlayer.state.value.isPlaying)) +
            apps.map { MusicSource(it, label(it, context), live[it]?.isPlaying == true) }
    }

    fun label(pkg: String, context: Context? = null): String {
        if (pkg == AppData.SOURCE_WIDGET) return WIDGET_LABEL
        labels[pkg]?.let { return it }
        val ctx = context ?: if (::appContext.isInitialized) appContext else return pkg
        val l = runCatching { ctx.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrNull()
        return (l ?: pkg).also { if (l != null) labels[pkg] = it }
    }

    fun launchApp(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    /** App has no live session: try a media-button PLAY directly to it, otherwise open the app. */
    private fun startApp(context: Context, pkg: String) {
        // Without notification access we can't see whether it is playing, so toggle instead of forcing PLAY.
        val key = if (hasNotificationAccess(context)) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        if (!sendMediaButton(context, pkg, key)) launchApp(context, pkg)
        main.postDelayed({ refresh(appContext) }, 1500)
    }

    private fun sendMediaButton(context: Context, pkg: String, keyCode: Int): Boolean {
        val probe = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
        val receivers = context.packageManager.queryBroadcastReceivers(probe, 0)
        if (receivers.isEmpty()) return false
        val info = receivers.first().activityInfo
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(info.packageName, info.name))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(now, now, action, keyCode, 0))
            runCatching { context.sendBroadcast(intent) }
        }
        return true
    }

    private fun readSystemVolume() {
        if (!::appContext.isInitialized) return
        val am = appContext.getSystemService(AudioManager::class.java) ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        _systemVolume.value = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    const val WIDGET_LABEL = "위젯 플레이리스트"
}

data class MusicSource(val id: String, val label: String, val active: Boolean = false)
