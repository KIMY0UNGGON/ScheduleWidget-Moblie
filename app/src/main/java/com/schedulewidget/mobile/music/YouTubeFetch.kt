package com.schedulewidget.mobile.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Port of desktop Services/YouTubePlaylistService.cs (public oEmbed + playlist page, no API key needed). */
object YouTubeFetch {
    data class Video(val id: String, val title: String)

    class FetchException(message: String) : Exception(message)

    private const val MAX_PAGES = 60
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val idRegex = Regex("^[A-Za-z0-9_-]{11}$")

    /** Title of a public video or playlist link via oEmbed; null when it can't be read. */
    suspend fun title(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = http("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8"))
            JSONObject(body).optString("title").trim().ifBlank { null }
        }.getOrNull()
    }

    /** Videos of a public playlist in order. Throws [FetchException] with a Korean reason. */
    suspend fun playlist(listId: String): List<Video> = withContext(Dispatchers.IO) {
        if (!Regex("^[A-Za-z0-9_-]{10,100}$").matches(listId)) throw FetchException("재생목록 ID가 올바르지 않습니다.")
        val html = try {
            http("https://www.youtube.com/playlist?list=$listId&hl=ko")
        } catch (e: IOException) {
            throw FetchException("YouTube에 연결하지 못했습니다. 인터넷 연결을 확인해 주세요.")
        }
        val initial = extractJson(html, "ytInitialData") ?: throw FetchException("재생목록 정보를 읽을 수 없습니다.")
        val videos = mutableListOf<Video>()
        val seen = HashSet<String>()
        var continuation = collect(initial, videos, seen)

        val apiKey = Regex("\"INNERTUBE_API_KEY\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        val clientVersion = Regex("\"INNERTUBE_CLIENT_VERSION\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: "2.20240101.00.00"
        var page = 0
        while (continuation != null && apiKey != null && page++ < MAX_PAGES) {
            ensureActive() // runCatching below would swallow cancellation; stop paging when the caller is gone
            val body = JSONObject()
                .put("context", JSONObject().put("client", JSONObject().put("clientName", "WEB").put("clientVersion", clientVersion).put("hl", "ko")))
                .put("continuation", continuation)
            val next = runCatching { JSONObject(http("https://www.youtube.com/youtubei/v1/browse?key=$apiKey", body.toString())) }
                .getOrNull() ?: break
            continuation = collect(next, videos, seen)
        }
        if (videos.isEmpty()) throw FetchException("재생목록이 비어 있거나 비공개입니다. 자동 생성 믹스는 곡별로 가져올 수 없습니다.")
        videos
    }

    /**
     * First video in YouTube's public search results for [query] (e.g. "title artist"), or null. Used when another app's
     * play queue only exposes titles (YouTube Music does not publish video ids in its queue).
     */
    suspend fun searchVideoId(query: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val html = http("https://www.youtube.com/results?hl=ko&search_query=" + URLEncoder.encode(query, "UTF-8"))
            Regex("\"videoRenderer\":\\{\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(html)?.groupValues?.get(1)
                ?: Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(html)?.groupValues?.get(1)
        }.getOrNull()
    }

    private fun http(url: String, postJson: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
            if (postJson != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(postJson.toByteArray()) }
            }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun extractJson(html: String, name: String): JSONObject? {
        var start = html.indexOf(name)
        if (start < 0) return null
        start = html.indexOf('{', start)
        if (start < 0) return null
        return runCatching { JSONTokener(html.substring(start)).nextValue() as? JSONObject }.getOrNull()
    }

    /** Collects entries only from the playlist's own item arrays; returns the next continuation token. */
    private fun collect(root: Any, videos: MutableList<Video>, seen: MutableSet<String>): String? {
        var continuation: String? = null
        fun visitArray(owner: String?, items: JSONArray) {
            if (owner != "contents" && owner != "continuationItems") return
            val objects = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            if (objects.none { it.has("lockupViewModel") || it.has("playlistVideoRenderer") }) return
            for (entry in objects) {
                var id: String? = null
                var title: String? = null
                val video = entry.optJSONObject("playlistVideoRenderer")
                val lockup = entry.optJSONObject("lockupViewModel")
                when {
                    video != null -> {
                        if (video.has("isPlayable") && !video.optBoolean("isPlayable", true)) continue
                        id = video.optString("videoId")
                        val t = video.optJSONObject("title")
                        title = t?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")?.ifBlank { null }
                            ?: t?.optString("simpleText")
                    }
                    lockup != null -> {
                        if (lockup.optString("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") continue
                        id = lockup.optString("contentId")
                        title = lockup.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                            ?.optJSONObject("title")?.optString("content")
                    }
                    entry.has("continuationItemViewModel") || entry.has("continuationItemRenderer") -> {
                        continuation = findToken(entry) ?: continuation
                        continue
                    }
                }
                if (id == null || !idRegex.matches(id) || !seen.add(id)) continue
                videos += Video(id, title?.trim()?.ifBlank { null } ?: "YouTube · $id")
            }
        }
        fun walk(node: Any?, owner: String?) {
            when (node) {
                is JSONObject -> node.keys().forEach { k -> walk(node.opt(k), k) }
                is JSONArray -> {
                    visitArray(owner, node)
                    for (i in 0 until node.length()) walk(node.opt(i), null)
                }
            }
        }
        walk(root, null)
        return continuation
    }

    private fun findToken(node: Any?): String? = when (node) {
        is JSONObject -> {
            val direct = node.opt("token") as? String
            direct ?: node.keys().asSequence().firstNotNullOfOrNull { findToken(node.opt(it)) }
        }
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { findToken(node.opt(it)) }
        else -> null
    }
}
