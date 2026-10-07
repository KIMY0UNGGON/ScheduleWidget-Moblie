package com.schedulewidget.mobile.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.schedulewidget.mobile.data.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * YouTube sign-in the way the PC app signs in: the browser opens Google's page, and the answer comes back to a tiny
 * server on 127.0.0.1 (OAuth "installed app" loopback flow with PKCE), using the PC app's own OAuth client.
 *
 * Why not Play services: they always add the scopes already granted to this app (drive.file for 캐릭터 동기화), and
 * Google refuses YouTube together with Drive in one request ("scopes that cannot be requested together"). Here the
 * request carries only youtube.readonly (include_granted_scopes=false), so it goes through.
 */
object YouTubeLogin {
    private const val SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
    private const val API = "https://www.googleapis.com/youtube/v3"
    private const val PREFS = "youtube_login"
    private const val MAX_CALLBACK_LINE = 8192
    private const val CALLBACK_READ_TIMEOUT_MS = 5000
    // Same encrypted client file and key as the PC app (ScheduleWidget/google_client.bin, GoogleCalendarService.DecryptClient).
    private const val CLIENT_KEY_SEED = "ScheduleWidget|google-client|v1|7f3c9a2e5d8b41f6"

    data class RemotePlaylist(val id: String, val title: String, val count: Int)

    private data class Client(val id: String, val secret: String)

    /** Non-2xx answer from Google ([code] = HTTP status); the message is already user-facing Korean. */
    private class HttpError(val code: Int, message: String) : Exception(message)

    private val tokenLock = Any()

    private fun client(context: Context): Client {
        val data = context.assets.open("google_client.bin").use { it.readBytes() }
        val key = MessageDigest.getInstance("SHA-256").digest(CLIENT_KEY_SEED.toByteArray())
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(data.copyOfRange(0, 16)))
        val json = JSONObject(String(cipher.doFinal(data, 16, data.size - 16)))
        val node = json.optJSONObject("installed") ?: json.optJSONObject("web") ?: json
        return Client(node.getString("client_id"), node.optString("client_secret"))
    }

    fun signedIn(context: Context) = prefs(context).getString("refresh", null) != null
    fun account(context: Context): String? = prefs(context).getString("account", null)
    fun signOut(context: Context) { prefs(context).edit().clear().apply() }
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Opens the browser for Google sign-in and waits (up to 5 minutes) for the answer on the loopback port. */
    suspend fun signIn(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val c = client(app)
        val verifier = randomUrlSafe(48)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val state = randomUrlSafe(16)
        ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).use { server ->
            val redirect = "http://127.0.0.1:${server.localPort}/"
            val url = "https://accounts.google.com/o/oauth2/v2/auth?response_type=code&access_type=offline&prompt=consent%20select_account" +
                "&include_granted_scopes=false&client_id=" + enc(c.id) + "&redirect_uri=" + enc(redirect) +
                "&scope=" + enc("$SCOPE email") + "&code_challenge=$challenge&code_challenge_method=S256&state=$state"
            withContext(Dispatchers.Main) {
                app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val code = waitForCode(server, state)
            val form = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(redirect) +
                "&client_id=" + enc(c.id) + "&client_secret=" + enc(c.secret) + "&code_verifier=" + enc(verifier)
            val token = post("https://oauth2.googleapis.com/token", form)
            val refresh = token.optString("refresh_token").ifBlank { error("구글이 로그인 정보를 주지 않았습니다. 다시 시도해 주세요.") }
            val access = token.getString("access_token")
            val email = runCatching { get(access, "https://www.googleapis.com/oauth2/v3/userinfo").optString("email") }.getOrNull()
            prefs(app).edit()
                .putString("refresh", refresh).putString("access", access)
                .putLong("expires", System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000 - 60_000)
                .putString("account", email).apply()
        }
    }

    /** Waits for "GET /?state=..&code=.. HTTP/1.1" from the browser and answers with a short page. */
    private suspend fun waitForCode(server: ServerSocket, state: String): String {
        // Short accept timeouts so a closed dialog (cancelled coroutine) frees the port and IO thread right away;
        // a blocking accept() ignores coroutine cancellation and withTimeout.
        server.soTimeout = 1000
        val deadline = System.currentTimeMillis() + 5 * 60_000L
        while (true) {
            kotlin.coroutines.coroutineContext.ensureActive()
            if (System.currentTimeMillis() > deadline) error("구글 로그인이 5분 안에 끝나지 않았습니다.")
            val socket = try { server.accept() } catch (e: SocketTimeoutException) { continue }
            socket.use { s ->
                val line = runCatching { readBoundedRequestLine(s) }.getOrNull().orEmpty()
                val target = line.split(' ').getOrNull(1).orEmpty()
                // Malformed %-escapes (stray requests) must not abort the sign-in.
                fun dec(v: String) = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
                val query = target.substringAfter('?', "").split('&').filter { '=' in it }
                    .associate { dec(it.substringBefore('=')) to dec(it.substringAfter('=')) }
                val ours = query["state"] == state
                val message = when {
                    !ours -> ""
                    query["code"] != null -> "YouTube 연결이 끝났습니다. 이 창을 닫고 일정 위젯으로 돌아가세요."
                    else -> "YouTube 로그인이 취소되었습니다."
                }
                val body = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>" +
                    "<body style='font-family:sans-serif;padding:32px'><h2>$message</h2></body>"
                val bytes = body.toByteArray()
                runCatching {
                    s.getOutputStream().apply {
                        write(("HTTP/1.1 ${if (ours) "200 OK" else "404 Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        write(bytes)
                        flush()
                    }
                }
                if (ours) return query["code"] ?: error(query["error"]?.let { "YouTube 로그인 실패: $it" } ?: "YouTube 로그인이 취소되었습니다.")
            }
        }
    }

    internal fun readBoundedRequestLine(
        socket: Socket,
        maxBytes: Int = MAX_CALLBACK_LINE,
        timeoutMs: Int = CALLBACK_READ_TIMEOUT_MS,
    ): String? {
        require(maxBytes > 0 && timeoutMs > 0)
        val input = BufferedInputStream(socket.getInputStream())
        val out = ByteArrayOutputStream()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return null
            socket.soTimeout = ((remaining + 999_999L) / 1_000_000L).coerceAtLeast(1).toInt()
            val byte = try { input.read() } catch (_: SocketTimeoutException) { return null }
            if (byte < 0) return null
            if (byte == '\n'.code) return out.toString(Charsets.UTF_8.name()).removeSuffix("\r")
            if (out.size() == maxBytes) return null
            out.write(byte)
        }
    }

    /** A valid access token (refreshed when expired). Throws with a Korean message when sign-in is needed again. */
    private fun accessToken(context: Context): String = synchronized(tokenLock) { accessTokenLocked(context) }

    private fun accessTokenLocked(context: Context): String {
        val p = prefs(context)
        p.getString("access", null)?.takeIf { p.getLong("expires", 0) > System.currentTimeMillis() }?.let { return it }
        val refresh = p.getString("refresh", null) ?: error("YouTube 로그인이 필요합니다.")
        val c = client(context)
        val token = runCatching {
            post("https://oauth2.googleapis.com/token",
                "grant_type=refresh_token&refresh_token=" + enc(refresh) + "&client_id=" + enc(c.id) + "&client_secret=" + enc(c.secret))
        }.getOrElse { e ->
            // Only a rejected refresh token means signed out; a network hiccup must not erase the login.
            if (e is HttpError && e.code in 400..401) { signOut(context); error("YouTube 로그인이 만료되었습니다. 다시 로그인해 주세요.") }
            if (e is IOException) error("YouTube에 연결하지 못했습니다. 인터넷 연결을 확인해 주세요.")
            throw e
        }
        val access = token.getString("access_token")
        p.edit().putString("access", access).putLong("expires", System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000 - 60_000).apply()
        return access
    }

    /** API GET with a valid token; a 401 (token revoked before expiry) drops the cached access token and retries once. */
    private fun api(context: Context, url: String): JSONObject = try {
        get(accessToken(context), url)
    } catch (e: HttpError) {
        if (e.code != 401) throw e
        synchronized(tokenLock) { prefs(context).edit().remove("access").remove("expires").commit() }
        get(accessToken(context), url)
    }

    /** "좋아요 표시한 동영상" first, then the account's playlists (YouTube and YouTube Music). */
    suspend fun playlists(context: Context): List<RemotePlaylist> = withContext(Dispatchers.IO) {
        val out = mutableListOf<RemotePlaylist>()
        runCatching {
            api(context, "$API/channels?part=contentDetails&mine=true").optJSONArray("items")?.optJSONObject(0)
                ?.optJSONObject("contentDetails")?.optJSONObject("relatedPlaylists")?.optString("likes")?.takeIf { it.isNotBlank() }
                ?.let { out += RemotePlaylist(it, "좋아요 표시한 동영상", -1) }
        }
        var page: String? = null
        do {
            ensureActive()
            val json = api(context, "$API/playlists?part=snippet,contentDetails&mine=true&maxResults=50" + (page?.let { "&pageToken=" + enc(it) } ?: ""))
            val items = json.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) {
                val p = items!!.getJSONObject(i)
                out += RemotePlaylist(p.getString("id"), p.optJSONObject("snippet")?.optString("title").orEmpty().ifBlank { "재생목록" },
                    p.optJSONObject("contentDetails")?.optInt("itemCount", -1) ?: -1)
            }
            page = json.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        out
    }

    suspend fun tracks(context: Context, playlistId: String): List<MusicTrack> = withContext(Dispatchers.IO) {
        val out = mutableListOf<MusicTrack>()
        var page: String? = null
        do {
            ensureActive()
            val json = api(context,"$API/playlistItems?part=snippet&maxResults=50&playlistId=" + enc(playlistId) + (page?.let { "&pageToken=" + enc(it) } ?: ""))
            val items = json.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) {
                val s = items!!.getJSONObject(i).optJSONObject("snippet") ?: continue
                val video = s.optJSONObject("resourceId")?.optString("videoId").orEmpty()
                val title = s.optString("title")
                if (video.isBlank() || title == "Private video" || title == "Deleted video") continue
                val artist = s.optString("videoOwnerChannelTitle").removeSuffix(" - Topic")
                out += MusicTrack(listOf(title, artist).filter { it.isNotBlank() }.joinToString(" - "), "https://www.youtube.com/watch?v=$video")
            }
            page = json.optString("nextPageToken").ifBlank { null }
        } while (page != null && out.size < 5000)
        out
    }

    // ---- HTTP ----

    private fun get(token: String, url: String): JSONObject {
        val conn = open(url)
        conn.setRequestProperty("Authorization", "Bearer $token")
        return read(conn)
    }

    // Timeouts must be set before getOutputStream() connects (POST), not only before reading.
    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 20000
    }

    private fun post(url: String, form: String): JSONObject {
        val conn = open(url)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(form.toByteArray()) }
        return read(conn)
    }

    private fun read(conn: HttpURLConnection): JSONObject {
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val reason = runCatching {
                    val e = JSONObject(body)
                    e.optJSONObject("error")?.optString("message") ?: e.optString("error_description").ifBlank { e.optString("error") }
                }.getOrNull()
                throw HttpError(code,
                    when (code) {
                        401 -> "YouTube 로그인이 만료되었습니다. 다시 로그인해 주세요."
                        403 -> "YouTube API를 사용할 수 없습니다: ${reason ?: "권한 없음"} (Cloud 프로젝트에서 YouTube Data API v3 사용 설정 필요)"
                        else -> "YouTube 오류 $code${reason?.let { ": $it" } ?: ""}"
                    }
                )
            }
            return JSONObject(body)
        } finally { conn.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun randomUrlSafe(bytes: Int): String =
        Base64.encodeToString(ByteArray(bytes).also { SecureRandom().nextBytes(it) }, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
