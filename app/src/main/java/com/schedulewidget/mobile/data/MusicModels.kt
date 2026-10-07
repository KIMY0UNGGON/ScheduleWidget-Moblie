package com.schedulewidget.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI
import java.util.UUID

@Serializable
data class MusicSettings(
    @SerialName("Playlists") val playlists: List<MusicPlaylist> = emptyList(),
    @SerialName("SelectedPlaylistId") val selectedPlaylistId: String = "00000000-0000-0000-0000-000000000000",
    @SerialName("Volume") val volume: Double = 0.5,
    @SerialName("Repeat") val repeat: Boolean = false,
    @SerialName("Shuffle") val shuffle: Boolean = false,
    @SerialName("RepeatOne") val repeatOne: Boolean = false,
) {
    val selectedPlaylist: MusicPlaylist? get() = playlists.firstOrNull { it.id == selectedPlaylistId } ?: playlists.firstOrNull()
}

@Serializable
data class MusicPlaylist(
    @SerialName("Id") val id: String = UUID.randomUUID().toString(),
    @SerialName("Name") val name: String = "내 플레이리스트",
    @SerialName("Tracks") val tracks: List<MusicTrack> = emptyList(),
)

@Serializable
data class MusicTrack(
    @SerialName("Title") val title: String = "",
    // Android: content:// URI (persisted SAF grant), a YouTube URL or an open.spotify.com URL.
    // Desktop imports may carry Windows paths (C:\...) which cannot play until relinked.
    @SerialName("Source") val source: String = "",
) {
    val isYouTube: Boolean get() = YouTube.videoId(source) != null || YouTube.playlistId(source) != null
    val isSpotify: Boolean get() = Spotify.parse(source) != null
    val isDesktopPath: Boolean get() = Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(source) || source.startsWith("\\\\")
}

/**
 * Spotify songs, albums and playlists. They always play in the Spotify app with the user's own account (Spotify
 * does not allow full playback elsewhere); tracks are stored as https://open.spotify.com/{type}/{id}.
 */
object Spotify {
    data class Ref(val type: String, val id: String) {
        val uri: String get() = "spotify:$type:$id"
        val url: String get() = "https://open.spotify.com/$type/$id"
        val isList: Boolean get() = type == "album" || type == "playlist"
    }

    private const val TYPES = "track|album|playlist|episode"
    private val webPattern =
        Regex("""open\.spotify\.com/(?:intl-[A-Za-z-]+/)?(?:embed/)?($TYPES)/([A-Za-z0-9]{22})""", RegexOption.IGNORE_CASE)
    private val uriPattern = Regex("""spotify:($TYPES):([A-Za-z0-9]{22})""")
    private val shortPattern = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
    private val shortHosts = setOf("spotify.link", "spotify.app.link")
    private val redirectHosts = shortHosts + "open.spotify.com"

    /** The first Spotify link / URI inside [text] (share texts may contain more words), or null. */
    fun parse(text: String): Ref? =
        (webPattern.find(text) ?: uriPattern.find(text))?.let { Ref(it.groupValues[1].lowercase(), it.groupValues[2]) }

    /** Canonical open.spotify.com link (tracking params such as si= dropped), or null when [text] has none. */
    fun normalize(text: String): String? = parse(text)?.url

    /** A spotify.link short link in [text] (the Spotify app shares these); needs SpotifyFetch.expandShortLink. */
    fun shortLink(text: String): String? = shortPattern.findAll(text).firstNotNullOfOrNull { match ->
        val candidate = match.value.trimEnd('.', ',', ')', '"', '\'')
        runCatching { URI(candidate) }.getOrNull()?.takeIf(::isShortLinkUri)?.toString()
    }

    internal fun isShortLinkUri(uri: URI): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", true) && uri.userInfo == null && uri.port == -1 && host in shortHosts
    }

    internal fun isTrustedShortLinkRedirect(uri: URI): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", true) && uri.userInfo == null && uri.port == -1 && host in redirectHosts
    }
}

/** Links a playlist can hold besides files: YouTube / YouTube Music and Spotify. */
object MusicLinks {
    fun normalize(text: String): String? = YouTube.normalize(text) ?: Spotify.normalize(text)

    /** True for links that stand for several songs (YouTube playlist, Spotify album / playlist). */
    fun isList(link: String): Boolean = YouTube.playlistId(link) != null || Spotify.parse(link)?.isList == true
}

object YouTube {
    private val idPatterns = listOf(
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
        Regex("""/shorts/([A-Za-z0-9_-]{11})"""),
        Regex("""/embed/([A-Za-z0-9_-]{11})"""),
        Regex("""/live/([A-Za-z0-9_-]{11})"""),
    )

    fun videoId(url: String): String? {
        if (!url.contains("youtu")) return null
        return idPatterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }
    }

    fun playlistId(url: String): String? {
        if (!url.contains("youtu")) return null
        Regex("""[?&]list=([A-Za-z0-9_-]+)""").find(url)?.let { return it.groupValues[1] }
        // YouTube Music album/playlist pages: music.youtube.com/browse/VLPL... -> PL...
        return Regex("""/browse/VL([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
    }

    /** First YouTube / YouTube Music link inside [text] (share texts look like "Title\nhttps://..."), or null. */
    fun extractUrl(text: String): String? =
        Regex("""https?://[^\s]*youtu[^\s]*""", RegexOption.IGNORE_CASE).find(text)?.value?.trimEnd('.', ',', ')', '"', '\'')

    /**
     * Canonical www.youtube.com form of a YouTube, youtu.be or YouTube Music link (tracking params such as si= dropped),
     * so titles, playlist expansion and the embedded player all work the same. null when it isn't a YouTube link.
     */
    fun normalize(text: String): String? {
        val url = extractUrl(text) ?: text.trim().takeIf { it.contains("youtu") } ?: return null
        val video = videoId(url)
        val list = playlistId(url)
        return when {
            video != null && list != null -> "https://www.youtube.com/watch?v=$video&list=$list"
            video != null -> "https://www.youtube.com/watch?v=$video"
            list != null -> "https://www.youtube.com/playlist?list=$list"
            else -> null
        }
    }
}
