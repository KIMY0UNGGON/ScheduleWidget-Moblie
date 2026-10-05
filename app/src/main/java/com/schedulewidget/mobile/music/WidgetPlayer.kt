package com.schedulewidget.mobile.music

import com.schedulewidget.mobile.data.Spotify
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.schedulewidget.mobile.apps.MusicHub
import com.schedulewidget.mobile.data.AppData
import com.schedulewidget.mobile.data.MusicPlaylist
import com.schedulewidget.mobile.data.MusicSettings
import com.schedulewidget.mobile.data.MusicTrack
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.data.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume


/**
 * CONTRACT (owner: music-playback agent). In-app playlist player for AppData.music (files via Media3
 * PlaybackService, YouTube via the WebView in YouTubeHost). Keep these signatures.
 */
object WidgetPlayer {
    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state

    private val _current = MutableStateFlow<PlayingRef?>(null)
    /** Track currently loaded (playing or paused); null when stopped. */
    val current: StateFlow<PlayingRef?> = _current

    private val _status = MutableStateFlow("재생할 곡을 선택하세요.")
    /** Human-readable status line (errors, skips, end of playlist). */
    val status: StateFlow<String> = _status

    private val _blocked = MutableStateFlow<MusicTrack?>(null)
    /** Last YouTube track that refused embedded playback; the UI may offer to open it in the YouTube app. */
    val blocked: StateFlow<MusicTrack?> = _blocked

    /** External = a YouTube track handed to the official YouTube Music / YouTube app (user's Premium applies). */
    private enum class Kind { None, Local, YouTube, External }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Context

    private val _route = MutableStateFlow(AudioRoute.InApp)
    /** Where the widget playlist's sound comes from now; only [AudioRoute.InApp] can be boosted (VolumeBoost). */
    val route: StateFlow<AudioRoute> = _route

    private var kind = Kind.None
        set(v) {
            field = v
            _route.value = when (v) {
                Kind.YouTube -> AudioRoute.YouTubeWeb
                Kind.External -> AudioRoute.OtherApp
                Kind.None, Kind.Local -> AudioRoute.InApp
            }
        }
    private var request = 0
    private var failures = 0
    private var liveTitle: String? = null

    private var controller: MediaController? = null
    private var connecting: Job? = null
    private var ticker: Job? = null
    private var externalPkg: String? = null
    private var externalWatch: Job? = null

    /** The music app a widget song is currently handed to (it is ours to follow), or null. */
    val followingApp: String? get() = if (kind == Kind.External) externalPkg else null

    /** How long a relaunched external song may keep the title the app was already showing before we accept it. */
    private const val EXTERNAL_GRACE_MS = 8000L

    private var shuffleOrder = listOf<Int>()
    private var shuffleKey = ""
    private var shuffleIndex = -1

    // ---- public API ------------------------------------------------------------------------------------------

    fun playPause(context: Context) = run(context) {
        when (kind) {
            Kind.Local -> controller?.let { if (it.isPlaying) it.pause() else it.play() }
            Kind.External -> externalPkg?.let { pkg ->
                val t = MusicHub.transport(pkg) ?: return@let
                if (_state.value.isPlaying) t.pause() else t.play()
            }
            Kind.YouTube -> {
                val playing = _state.value.isPlaying
                YouTubeEngine.command(app, if (playing) "pause" else "resume")
                publish(isPlaying = !playing)
            }
            Kind.None -> {
                val pl = settings().selectedPlaylist ?: return@run
                if (pl.tracks.isEmpty()) return@run
                val ref = _current.value
                val start = if (settings().shuffle) shuffleStart(pl) else ref?.takeIf { it.playlistId == pl.id }?.index?.coerceIn(0, pl.tracks.lastIndex) ?: 0
                failures = 0
                playTrack(pl, start)
            }
        }
    }

    fun pause(context: Context) = run(context) {
        when (kind) {
            Kind.Local -> controller?.pause()
            Kind.YouTube -> { YouTubeEngine.command(app, "pause"); publish(isPlaying = false) }
            Kind.External -> externalPkg?.let { MusicHub.transport(it)?.pause() }
            Kind.None -> Unit
        }
    }

    fun next(context: Context) = run(context) { advance(1, automatic = false) }

    fun previous(context: Context) = run(context) {
        // Like most players: restart the current song when it has been playing for a while.
        val s = _state.value
        if (kind != Kind.None && s.canSeek && extrapolated(s) > 3000) seekInternal(0) else advance(-1, automatic = false)
    }

    fun seekTo(context: Context, positionMs: Long) = run(context) { seekInternal(positionMs) }

    fun setVolume(context: Context, volume: Float) = run(context) {
        applyVolume(volume)
        Repository.get(app).update { it.copy(music = it.music.copy(volume = volume.toDouble().coerceIn(0.0, 1.0))) }
    }

    /** Live volume change without persisting (e.g. while dragging a slider). */
    fun previewVolume(context: Context, volume: Float) = run(context) { applyVolume(volume) }

    /** Plays track [index] of playlist [playlistId] (also selects that playlist). */
    fun play(context: Context, playlistId: String, index: Int) = run(context) {
        val repo = Repository.get(app)
        val pl = repo.data.value.music.playlists.firstOrNull { it.id == playlistId } ?: return@run
        if (index !in pl.tracks.indices) return@run
        repo.update {
            it.copy(music = it.music.copy(selectedPlaylistId = playlistId), musicSource = AppData.SOURCE_WIDGET)
        }
        failures = 0
        if (settings().shuffle) resetShuffle(pl, first = index)
        playTrack(pl, index)
    }

    /** Cycles 순차 -> 랜덤 -> 1곡 (Shuffle/RepeatOne in MusicSettings). */
    fun cycleMode(context: Context) = run(context) {
        val m = settings()
        val next = when {
            m.repeatOne -> m.copy(shuffle = false, repeatOne = false)
            m.shuffle -> m.copy(shuffle = false, repeatOne = true)
            else -> m.copy(shuffle = true, repeatOne = false)
        }
        Repository.get(app).update { it.copy(music = next) }
        val pl = currentPlaylist()
        if (next.shuffle && pl != null) resetShuffle(pl, first = resolveIndex(pl)) else shuffleOrder = emptyList()
    }

    fun setRepeat(context: Context, repeat: Boolean) = run(context) {
        Repository.get(app).update { it.copy(music = it.music.copy(repeat = repeat)) }
    }

    fun stop(context: Context) = run(context) { stopPlayback("재생할 곡을 선택하세요.") }

    fun openInYouTube(context: Context, track: MusicTrack) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(track.source)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
        _blocked.value = null
    }

    fun dismissBlocked() { _blocked.value = null }

    fun modeLabel(m: MusicSettings): String = when {
        m.repeatOne -> "1곡"
        m.shuffle -> "랜덤"
        else -> "순차"
    }

    // ---- playlist logic (port of MusicWindow.AdvanceAsync / PlayTrackAsync) ------------------------------------

    private fun run(context: Context, block: () -> Unit) {
        if (!::app.isInitialized) {
            app = context.applicationContext
            YouTubeEngine.listener = ytListener
            _state.value = _state.value.copy(volume = settings().volume.toFloat())
        }
        scope.launch { block() }
    }

    private fun settings() = Repository.get(app).data.value.music

    private fun currentPlaylist(): MusicPlaylist? {
        val id = _current.value?.playlistId
        val music = settings()
        return music.playlists.firstOrNull { it.id == id } ?: music.selectedPlaylist
    }

    /** Current index in [pl], re-found by source when the list was edited; -1 when not in this list. */
    private fun resolveIndex(pl: MusicPlaylist): Int {
        val ref = _current.value ?: return -1
        if (ref.playlistId != pl.id) return -1
        if (pl.tracks.getOrNull(ref.index)?.source == ref.source) return ref.index
        return pl.tracks.indexOfFirst { it.source == ref.source }
    }

    private fun keyOf(pl: MusicPlaylist) = pl.id + "|" + pl.tracks.joinToString("\u0001") { it.source }

    private fun resetShuffle(pl: MusicPlaylist, first: Int = -1) {
        val order = pl.tracks.indices.shuffled().toMutableList()
        shuffleIndex = -1
        if (first in pl.tracks.indices) {
            order.remove(first); order.add(0, first); shuffleIndex = 0
        }
        shuffleOrder = order
        shuffleKey = keyOf(pl)
    }

    private fun shuffleStart(pl: MusicPlaylist): Int {
        resetShuffle(pl)
        shuffleIndex = 0
        return shuffleOrder.first()
    }

    private fun advance(direction: Int, automatic: Boolean) {
        val pl = currentPlaylist() ?: return
        if (pl.tracks.isEmpty()) return
        val m = settings()
        val index = resolveIndex(pl)
        if (automatic && m.repeatOne && index >= 0) {
            playTrack(pl, index)
            return
        }
        val target: Int
        if (m.shuffle) {
            if (shuffleKey != keyOf(pl) || shuffleOrder.size != pl.tracks.size) resetShuffle(pl, first = index)
            if (shuffleIndex < 0) {
                shuffleIndex = shuffleOrder.indexOf(index)
                if (shuffleIndex < 0) shuffleIndex = if (direction < 0) 0 else -1
            }
            var nextPos = shuffleIndex + direction
            if (nextPos !in shuffleOrder.indices) {
                if (automatic && !m.repeat) { finish(); return }
                resetShuffle(pl)
                nextPos = if (direction < 0) shuffleOrder.lastIndex else 0
            }
            shuffleIndex = nextPos
            target = shuffleOrder[nextPos]
        } else {
            var next = index + direction
            if (index < 0) {
                // The playing song was removed from the list: continue from where it was instead of jumping to the top.
                val gone = _current.value?.takeIf { it.playlistId == pl.id }?.index
                next = when {
                    gone != null -> if (direction < 0) gone - 1 else gone
                    direction < 0 -> pl.tracks.lastIndex
                    else -> 0
                }
            }
            if (next !in pl.tracks.indices) {
                if (automatic && !m.repeat) { finish(); return }
                next = if (next < 0) pl.tracks.lastIndex else 0
            }
            target = next
        }
        playTrack(pl, target, direction)
    }

    private fun finish() = stopPlayback("플레이리스트 재생을 마쳤습니다.")

    private fun playTrack(pl: MusicPlaylist, index: Int, direction: Int = 1) {
        val track = pl.tracks.getOrNull(index) ?: return
        // YouTube -> YouTube in the same external app: don't pause it; the async pause could land after the app
        // has already started the new song and leave it paused (the watcher would then wait forever).
        val sameExternalApp = kind == Kind.External && !track.isDesktopPath && externalPackageFor(track) == externalPkg
        stopEngines(pauseExternal = !sameExternalApp)
        request++
        liveTitle = null
        _current.value = PlayingRef(pl.id, index, track.source)
        val volume = settings().volume.toFloat()
        _state.value = NowPlaying(
            title = track.title.ifBlank { "제목 없음" }, artist = pl.name, isPlaying = true,
            updatedAt = SystemClock.elapsedRealtime(), volume = volume,
        )

        if (track.isDesktopPath || track.source.isBlank()) {
            skipUnplayable(pl, "재연결 필요: ${track.title} — 건너뜁니다.", direction)
            return
        }
        if (track.isSpotify) {
            // Spotify only plays full songs in its own app (with the user's account).
            if (!playSpotify(track)) skipUnplayable(pl, "Spotify 앱이 없어 건너뜁니다: ${track.title}", direction)
            return
        }
        if (track.isYouTube && playExternally(track)) return
        if (track.isYouTube) {
            kind = Kind.YouTube
            _status.value = "YouTube 연결 중…"
            val list = YouTube.playlistId(track.source)
            YouTubeEngine.load(app, request, YouTube.videoId(track.source), list, (volume * 100).toInt())
            startTicker()
            return
        }
        kind = Kind.Local
        _status.value = "파일을 여는 중…"
        val item = MediaItem.Builder()
            .setUri(Uri.parse(track.source))
            .setMediaId(track.source)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(pl.name).build())
            .build()
        val req = request
        withController { c ->
            if (req != request) return@withController
            c.volume = volume
            c.setMediaItem(item)
            c.prepare()
            c.play()
            startTicker()
        }
    }

    private fun skipUnplayable(pl: MusicPlaylist, message: String, direction: Int) {
        failures++
        if (failures >= pl.tracks.size) {
            stopPlayback("재생할 수 있는 곡이 없습니다. $message")
            failures = 0
            return
        }
        _status.value = message
        advance(if (direction < 0) -1 else 1, automatic = false)
    }

    private fun stopEngines(pauseExternal: Boolean = true) {
        when (kind) {
            Kind.Local -> controller?.run { stop(); clearMediaItems() }
            Kind.YouTube -> YouTubeEngine.command(app, "stop")
            Kind.External -> if (pauseExternal) externalPkg?.let { MusicHub.transport(it)?.pause() }
            Kind.None -> Unit
        }
        kind = Kind.None
        ticker?.cancel()
        externalWatch?.cancel()
        externalPkg = null
    }

    private fun stopPlayback(message: String) {
        stopEngines()
        request++
        _current.value = null
        _status.value = message
        _state.value = NowPlaying(volume = settings().volume.toFloat(), updatedAt = SystemClock.elapsedRealtime())
    }

    private fun seekInternal(positionMs: Long) {
        when (kind) {
            Kind.Local -> controller?.seekTo(positionMs.coerceAtLeast(0))
            Kind.YouTube -> YouTubeEngine.command(app, "seek", mapOf("seconds" to positionMs / 1000.0))
            Kind.External -> externalPkg?.let { MusicHub.transport(it)?.seekTo(positionMs.coerceAtLeast(0)) }
            Kind.None -> return
        }
        publish(positionMs = positionMs)
    }

    private fun applyVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        controller?.volume = v
        if (kind == Kind.YouTube) YouTubeEngine.command(app, "volume", mapOf("volume" to (v * 100).toInt()))
        _state.value = _state.value.copy(volume = v)
    }

    private fun extrapolated(s: NowPlaying): Long =
        if (s.isPlaying) s.positionMs + (SystemClock.elapsedRealtime() - s.updatedAt) else s.positionMs

    private fun publish(
        isPlaying: Boolean = _state.value.isPlaying,
        positionMs: Long = extrapolated(_state.value),
        durationMs: Long = _state.value.durationMs,
    ) {
        _state.value = _state.value.copy(
            isPlaying = isPlaying, positionMs = positionMs, durationMs = durationMs,
            updatedAt = SystemClock.elapsedRealtime(), canSeek = durationMs > 0,
        )
    }

    // ---- YouTube through the official app (Premium) ------------------------------------------------------------

    /**
     * Opens [track] in the YouTube Music / YouTube app chosen in settings, then follows that app's media session:
     * its title/position are mirrored here, and when the song ends (or the app moves on to its own next song) the
     * app is paused and the widget playlist continues — so phone files and YouTube songs play in one list.
     */
    private fun playExternally(track: MusicTrack): Boolean {
        val pkg = Repository.get(app).data.value.youTubePlayer
        if (pkg == AppData.YOUTUBE_EMBED || app.packageManager.getLaunchIntentForPackage(pkg) == null) return false
        val video = YouTube.videoId(track.source)
        val list = YouTube.playlistId(track.source)
        val host = if (pkg == AppData.YOUTUBE_MUSIC_APP) "https://music.youtube.com" else "https://www.youtube.com"
        val url = when {
            video != null -> "$host/watch?v=$video" + (list?.let { "&list=$it" } ?: "")
            list != null -> "$host/playlist?list=$list"
            else -> return false
        }
        return followExternal(pkg, Uri.parse(url), isList = list != null)
    }

    /** A Spotify song / album / playlist, handed to the Spotify app (the user's account and Premium apply there). */
    private fun playSpotify(track: MusicTrack): Boolean {
        val ref = Spotify.parse(track.source) ?: return false
        if (app.packageManager.getLaunchIntentForPackage(AppData.SPOTIFY_APP) == null) return false
        return followExternal(AppData.SPOTIFY_APP, Uri.parse(ref.uri), isList = ref.isList)
    }

    /** The app a track is handed to (null when it plays inside this app). */
    private fun externalPackageFor(track: MusicTrack): String? = when {
        track.isSpotify -> AppData.SPOTIFY_APP
        track.isYouTube -> Repository.get(app).data.value.youTubePlayer.takeIf { it != AppData.YOUTUBE_EMBED }
        else -> null
    }

    /**
     * Opens [uri] in [pkg] and follows that app's media session. For a single song ([isList] false) the widget
     * playlist moves on when the song ends or the app switches to another song; a list link is left to the app.
     */
    private fun followExternal(pkg: String, uri: Uri, isList: Boolean): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://${app.packageName}"))
        if (runCatching { app.startActivity(intent) }.isFailure) return false

        kind = Kind.External
        externalPkg = pkg
        val label = MusicHub.label(pkg, app)
        _status.value = if (MusicHub.hasNotificationAccess(app)) "$label 에서 재생 중"
        else "$label 에서 재생 중 · 알림 접근을 허용하면 곡이 끝날 때 자동으로 다음 곡으로 넘어갑니다"
        val req = request
        val launchedAt = SystemClock.elapsedRealtime()
        // What the app was on when we handed it our song (its previous song, or one it autoplayed). Seeing that
        // title "playing" must not count as our song starting, or its switch to our song would look like "moved on".
        val staleTitle = MusicHub.sessions.value[pkg]?.title?.takeIf { it.isNotBlank() }
        externalWatch = scope.launch {
            var startedTitle: String? = null
            var last: NowPlaying? = null
            // Re-evaluates once after the grace period even if the session sends no new update (same song relaunched).
            val graceOver = MutableStateFlow(false)
            launch { delay(EXTERNAL_GRACE_MS); graceOver.value = true }
            kotlinx.coroutines.flow.combine(MusicHub.sessions, graceOver) { sessions, _ -> sessions }.collect { sessions ->
                if (req != request || kind != Kind.External) return@collect
                val s = sessions[pkg] ?: return@collect
                if (startedTitle == null) {
                    // Wait until the app actually plays the song we asked for.
                    if (!s.isPlaying || s.title.isBlank()) return@collect
                    val fresh = staleTitle == null || s.title != staleTitle ||
                        SystemClock.elapsedRealtime() - launchedAt >= EXTERNAL_GRACE_MS
                    if (!fresh) return@collect
                    startedTitle = s.title
                    failures = 0
                }
                val prev = last
                last = s
                val movedOn = s.title.isNotBlank() && s.title != startedTitle
                // Playing -> not playing at (or, extrapolated, right before) the end. Position samples are only sent on
                // state changes, so the previous sample must be extrapolated, and the new one (paused at the end) counts.
                val duration = s.durationMs.takeIf { it > 0 } ?: prev?.durationMs ?: 0L
                val prevPos = prev?.let { if (it.isPlaying) it.positionMs + (SystemClock.elapsedRealtime() - it.updatedAt) else it.positionMs } ?: 0L
                val ended = !s.isPlaying && prev?.isPlaying == true && duration > 0 &&
                    (s.positionMs >= duration - 2500 || prevPos >= duration - 2500)
                if (!isList && ended && !movedOn && settings().repeatOne) {
                    // 1곡 반복: restart it in place (re-opening the same link may just focus the app and stay paused).
                    MusicHub.transport(pkg)?.run { seekTo(0); play() }
                    last = null
                    return@collect
                }
                // A different song far from the end of ours: the user picked something else in the app (its own
                // playlist). Hand the app back instead of pushing the next widget song into it.
                val nearEnd = duration <= 0 || prevPos >= duration - 8000 || s.positionMs >= duration - 8000
                if (!isList && movedOn && !nearEnd) {
                    releaseToApp(pkg)
                    return@collect
                }
                if (!isList && (movedOn || ended)) {
                    // stopEngines (via advance) pauses the app when the next song is not played by it.
                    advance(1, automatic = true)
                    return@collect
                }
                _state.value = _state.value.copy(
                    title = if (isList) s.title else _state.value.title,
                    isPlaying = s.isPlaying, positionMs = s.positionMs, durationMs = s.durationMs,
                    updatedAt = s.updatedAt, canSeek = s.canSeek,
                )
            }
        }
        return true
    }

    /** Stops following [pkg] (without pausing it) and makes that app the MP3 tool's source. */
    private fun releaseToApp(pkg: String) {
        val job = externalWatch
        kind = Kind.None
        externalPkg = null
        externalWatch = null
        request++
        _current.value = null
        _status.value = "${MusicHub.label(pkg, app)}에서 다른 곡을 틀어서 위젯 플레이리스트를 멈췄습니다."
        _state.value = NowPlaying(volume = settings().volume.toFloat(), updatedAt = SystemClock.elapsedRealtime())
        MusicHub.followApp(app, pkg)
        job?.cancel()
    }

    // ---- Media3 (local files) -------------------------------------------------------------------------------

    private fun startTicker() {
        ticker?.cancel()
        if (kind != Kind.Local) return
        ticker = scope.launch {
            while (isActive) {
                controller?.let { c ->
                    val d = c.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET && it > 0 } ?: 0L
                    publish(isPlaying = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING),
                        positionMs = c.currentPosition, durationMs = d)
                }
                delay(500)
            }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.takeIf { it.isConnected }?.let { block(it); return }
        // A disconnected controller (service stopped/killed) still holds its binder and listener: release it.
        controller?.let { old -> old.removeListener(controllerListener); runCatching { old.release() } }
        controller = null
        val pendingJob = connecting
        val req = request
        connecting = scope.launch {
            pendingJob?.join()
            val c = controller?.takeIf { it.isConnected } ?: connect()
            if (c == null) {
                // Only for the request that asked; a newer (e.g. YouTube) track must not be stopped by it.
                if (req == request) stopPlayback("재생 서비스에 연결하지 못했습니다.")
                return@launch
            }
            block(c)
        }
    }

    private suspend fun connect(): MediaController? = suspendCancellableCoroutine { cont ->
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull()
            c?.addListener(controllerListener)
            controller = c
            if (cont.isActive) cont.resume(c)
        }, ContextCompat.getMainExecutor(app))
        cont.invokeOnCancellation { future.cancel(true) }
    }

    private val controllerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (kind != Kind.Local) return
            if (isPlaying) { failures = 0; _status.value = "재생 중" }
            publish(isPlaying = isPlaying, positionMs = controller?.currentPosition ?: 0)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (kind == Kind.Local && playbackState == Player.STATE_ENDED) advance(1, automatic = true)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (kind != Kind.Local) return
            val pl = currentPlaylist() ?: return
            val title = _state.value.title
            skipUnplayable(pl, "재생 실패: $title — 파일을 열 수 없습니다. 다시 추가하거나 재연결하세요.", 1)
        }
    }

    // ---- YouTube --------------------------------------------------------------------------------------------

    private val ytListener = object : YouTubeEngine.Listener {
        private fun ours(req: Int) = kind == Kind.YouTube && req == request

        override fun onYtState(request: Int, state: Int) {
            if (!ours(request)) return
            val playing = state == 1
            if (playing) { failures = 0; _status.value = "YouTube 재생 중" }
            // 3 = buffering: keep the "playing" look so the UI does not flicker.
            if (state != 3) publish(isPlaying = playing)
        }

        override fun onYtEnded(request: Int) { if (ours(request)) advance(1, automatic = true) }

        override fun onYtError(request: Int, code: String) {
            if (!ours(request)) return
            val pl = currentPlaylist() ?: return
            val track = resolveIndex(pl).let { pl.tracks.getOrNull(it) }
            if (code in setOf("100", "101", "150", "152", "153")) {
                _blocked.value = track
                skipUnplayable(pl, "‘${track?.title ?: ""}’은(는) 앱 안에서 재생할 수 없어 건너뜁니다. (YouTube 오류 $code)", 1)
                return
            }
            publish(isPlaying = false)
            if (code == "renderer") {
                // The WebView was replaced and has nothing loaded: let the play button reload this song.
                kind = Kind.None
                _status.value = "YouTube 플레이어가 종료되었습니다. 재생 버튼을 눌러 다시 재생하세요."
                return
            }
            _status.value = if (code == "network") "YouTube에 연결하지 못했습니다. 인터넷 연결을 확인하세요."
            else "YouTube 재생 오류 $code. 연결을 확인하거나 YouTube에서 열어 보세요."
        }

        override fun onYtTime(request: Int, currentSec: Double, durationSec: Double) {
            if (!ours(request)) return
            publish(positionMs = (currentSec * 1000).toLong(), durationMs = (durationSec * 1000).toLong())
        }

        override fun onYtTitle(request: Int, title: String) {
            if (!ours(request) || title.isBlank()) return
            liveTitle = title
            val pl = currentPlaylist()
            val track = pl?.let { p -> p.tracks.getOrNull(resolveIndex(p)) }
            // Playlist links play many songs under one entry: show the song actually playing.
            if (track == null || track.title.isBlank() || YouTube.playlistId(track.source) != null) {
                _state.value = _state.value.copy(title = title)
            }
        }

        override fun onYtBlocked(request: Int) {
            if (!ours(request)) return
            publish(isPlaying = false)
            _status.value = "자동 재생이 차단되었습니다. 재생 버튼을 눌러 주세요."
        }
    }
}
