package com.schedulewidget.mobile.music

/** Shared now-playing snapshot for any source (widget playlist or an external app). */
data class NowPlaying(
    val sourceId: String = "widget",
    val sourceLabel: String = "위젯 플레이리스트",
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    /** 0 = unknown */
    val durationMs: Long = 0,
    /** System clock (SystemClock.elapsedRealtime) when positionMs was sampled; extrapolate while playing. */
    val updatedAt: Long = 0,
    val canSeek: Boolean = false,
    val volume: Float = 0.5f,
)

/** Who renders the audio: this app (files, boostable), the YouTube WebView, or another music app. */
enum class AudioRoute { InApp, YouTubeWeb, OtherApp }

/** Which track the in-app player is on (index may drift after edits; [source] is used to re-find it). */
data class PlayingRef(val playlistId: String, val index: Int, val source: String)
