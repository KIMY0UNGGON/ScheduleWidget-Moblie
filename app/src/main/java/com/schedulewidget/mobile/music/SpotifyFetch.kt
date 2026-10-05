package com.schedulewidget.mobile.music

import com.schedulewidget.mobile.data.Spotify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Public Spotify data without an API key: the embed page (open.spotify.com/embed/...) carries the title, artist and,
 * for albums / playlists, the track list as JSON. Playback itself always happens in the Spotify app.
 */
object SpotifyFetch {
    data class Item(val title: String, val artist: String, val uri: String) {
        /** "Title - Artist", the same form as imported YouTube songs. */
        val label: String get() = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" - ")
    }

    data class Entity(val name: String, val artist: String, val tracks: List<Item>)

    class FetchException(message: String) : Exception(message)

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val nextData = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    /** Name, artist and (for albums / playlists) the songs of [ref]. Throws [FetchException] with a Korean reason. */
    suspend fun entity(ref: Spotify.Ref): Entity = withContext(Dispatchers.IO) {
        val html = try {
            http("https://open.spotify.com/embed/${ref.type}/${ref.id}")
        } catch (e: IOException) {
            throw FetchException("Spotify에 연결하지 못했습니다. 인터넷 연결을 확인해 주세요.")
        }
        val json = nextData.find(html)?.groupValues?.get(1) ?: throw FetchException("Spotify 정보를 읽을 수 없습니다.")
        val e = runCatching {
            JSONObject(json).getJSONObject("props").getJSONObject("pageProps").getJSONObject("state")
                .getJSONObject("data").getJSONObject("entity")
        }.getOrNull() ?: throw FetchException("Spotify 정보를 읽을 수 없습니다. (비공개이거나 없는 항목)")
        val artists = e.optJSONArray("artists")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
        }.orEmpty()
        val tracks = e.optJSONArray("trackList")?.let { list ->
            (0 until list.length()).mapNotNull { i ->
                val t = list.optJSONObject(i) ?: return@mapNotNull null
                val uri = t.optString("uri").takeIf { it.startsWith("spotify:track:") } ?: return@mapNotNull null
                // Artist lists are joined with non-breaking spaces ("A, B").
                Item(t.optString("title").trim(), t.optString("subtitle").replace(' ', ' ').trim(), uri)
            }
        }.orEmpty()
        Entity(
            name = e.optString("name").ifBlank { e.optString("title") }.trim(),
            artist = artists.joinToString(", ").ifBlank { e.optString("subtitle").trim() },
            tracks = tracks,
        )
    }

    /** "Title - Artist" of a song (or the name of an album / playlist), or null. */
    suspend fun title(ref: Spotify.Ref): String? = runCatching {
        val e = entity(ref)
        if (ref.isList) e.name.ifBlank { null } else Item(e.name, e.artist, ref.uri).label.ifBlank { null }
    }.getOrNull()

    /**
     * The open.spotify.com link behind a spotify.link / spotify.app.link short link (what the Spotify app often shares),
     * or null. Follows the redirects by hand so an intent:// hop does not end the lookup.
     */
    suspend fun expandShortLink(url: String): String? = withContext(Dispatchers.IO) {
        var current = url
        repeat(5) {
            Spotify.normalize(current)?.let { return@withContext it }
            val c = runCatching { URL(current).openConnection() as HttpURLConnection }.getOrNull() ?: return@withContext null
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 10000
                c.readTimeout = 15000
                c.setRequestProperty("User-Agent", UA)
                val code = runCatching { c.responseCode }.getOrNull() ?: return@withContext null
                val location = c.getHeaderField("Location")
                if (code in 300..399 && location != null) {
                    current = URL(URL(current), location).toString()
                } else {
                    // Some short links answer with a page that names the target instead of redirecting.
                    val body = runCatching { c.inputStream.bufferedReader().use { it.readText() } }.getOrNull().orEmpty()
                    return@withContext Spotify.normalize(body)
                }
            } finally {
                c.disconnect()
            }
        }
        Spotify.normalize(current)
    }

    private fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
