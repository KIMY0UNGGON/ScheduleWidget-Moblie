package com.schedulewidget.mobile.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateRelease(val version: String, val assetId: Long, val assetName: String, val size: Long, val sha256: String, val releaseUrl: String)

/** Update failure whose message is safe to show as-is: never contains tokens, URLs or raw server responses. */
class UpdateException(message: String) : IOException(message)

/**
 * Manual update check against the latest published stable GitHub release, plus a verified APK download.
 * The optional read-only token lives only in the caller's memory and is sent to api.github.com only.
 * Installing is left to the Android system installer (user consent); nothing here touches app data.
 */
object AppUpdates {
    const val REPOSITORY = "KIMY0UNGGON/ScheduleWidget-Moblie"
    const val RELEASES_URL = "https://github.com/KIMY0UNGGON/ScheduleWidget-Moblie/releases"
    internal const val APK_NAME = "ScheduleWidget-mobile-notes.apk"
    internal const val MAX_APK_BYTES = 512L * 1024 * 1024
    private const val API = "https://api.github.com/repos/$REPOSITORY"
    private const val API_HOST = "api.github.com"
    private const val MAX_JSON_BYTES = 1 shl 20
    private const val MAX_REDIRECTS = 5
    private val DOWNLOAD_HOSTS = setOf(
        API_HOST, "github.com", "objects.githubusercontent.com",
        "release-assets.githubusercontent.com", "github-releases.githubusercontent.com",
    )
    private val VERSION = Regex("""(?:v(?:er)?\.?\s*)?(\d{1,9}(?:\.\d{1,9}){0,5})""", RegexOption.IGNORE_CASE)
    private val TOKEN = Regex("[A-Za-z0-9_]{1,255}")
    private val DIGEST = Regex("sha256:[0-9a-fA-F]{64}")
    private val HEX64 = Regex("[0-9a-f]{64}")
    private val downloadMutex = Mutex()

    /** Strict numeric stable versions ("0.1", "v0.1", "Ver 0.1"); missing trailing parts count as 0. Null when malformed or prerelease. */
    fun compareVersions(left: String, right: String): Int? {
        val a = parseVersion(left) ?: return null
        val b = parseVersion(right) ?: return null
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    internal fun parseVersion(raw: String): List<Int>? =
        VERSION.matchEntire(raw.trim())?.groupValues?.get(1)?.split('.')?.map { it.toInt() }

    /** The newer stable release, or null when [currentVersion] is already the latest. Every failure throws [UpdateException]. */
    suspend fun check(currentVersion: String, token: String = ""): UpdateRelease? = io("GitHub에 연결하지 못했어요. 네트워크를 확인하고 다시 시도해 주세요") {
        val auth = checkToken(token)
        if (parseVersion(currentVersion) == null) throw UpdateException("현재 앱 버전을 확인할 수 없어요")
        val latest = getJson("$API/releases/latest", auth)
        if (latest != null) return@io parseLatestRelease(latest, currentVersion)
        // 404 means either "no published release" or "repository not visible"; never report that as up to date.
        if (getJson(API, auth) != null) throw UpdateException("아직 게시된 정식 릴리즈가 없어요 (초안·사전 릴리즈는 제외)")
        throw UpdateException(
            if (auth.isEmpty()) "저장소가 비공개이거나 찾을 수 없어요. 읽기 전용 GitHub 토큰이 필요할 수 있어요"
            else "이 토큰으로 저장소에 접근할 수 없어요. 저장소 읽기 권한을 확인해 주세요",
        )
    }

    /** Pure parser for the /releases/latest body; null when not newer than [currentVersion]. */
    internal fun parseLatestRelease(body: String, currentVersion: String): UpdateRelease? {
        val release = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: throw bad()
        if (release.bool("draft") != false || release.bool("prerelease") != false) throw UpdateException("정식 릴리즈가 아니어서 업데이트로 쓰지 않아요")
        val tag = release.text("tag_name") ?: throw bad()
        val version = parseVersion(tag)?.joinToString(".") ?: throw UpdateException("릴리즈 버전 형식이 올바르지 않아요")
        val newer = compareVersions(version, currentVersion) ?: throw UpdateException("현재 앱 버전을 확인할 수 없어요")
        if (newer <= 0) return null
        val releaseUrl = "$RELEASES_URL/tag/$tag"
        if (!release.text("html_url").equals(releaseUrl, ignoreCase = true)) throw bad()

        val apks = (release["assets"] as? JsonArray ?: throw bad()).mapNotNull { it as? JsonObject }
            .filter { it.text("name")?.endsWith(".apk", ignoreCase = true) == true }
        val asset = apks.singleOrNull { it.text("name") == APK_NAME }
            ?: apks.filterNot { it.text("name")!!.contains("test", ignoreCase = true) }.singleOrNull()
            ?: throw UpdateException("릴리즈에서 설치할 APK를 하나로 정할 수 없어요")
        val id = asset.long("id")?.takeIf { it > 0 } ?: throw bad()
        val size = asset.long("size")?.takeIf { it in 1..MAX_APK_BYTES } ?: throw UpdateException("APK 크기가 올바르지 않아요")
        val sha = asset.text("digest")?.takeIf { DIGEST.matches(it) }?.substringAfter(':')?.lowercase()
            ?: throw UpdateException("릴리즈에 SHA-256 정보가 없어 받을 수 없어요")
        if (asset.text("state") != "uploaded") throw UpdateException("APK 업로드가 아직 끝나지 않았어요")
        if (!asset.text("url").equals("$API/releases/assets/$id", ignoreCase = true)) throw bad()
        return UpdateRelease(version, id, asset.text("name")!!, size, sha, releaseUrl)
    }

    /**
     * Downloads [release] into cache/app-updates, verifying size and SHA-256 and then [validateApk].
     * Returns only a fully verified file; partial or rejected files are deleted. [onProgress] runs on an IO thread.
     */
    suspend fun download(context: Context, release: UpdateRelease, token: String = "", onProgress: (Long) -> Unit = {}): File =
        io("APK를 받는 중 연결이 끊겼어요. 네트워크를 확인하고 다시 시도해 주세요") {
          downloadMutex.withLock {
            currentCoroutineContext().ensureActive()
            val auth = checkToken(token)
            checkRelease(release)
            val dir = File(context.cacheDir, "app-updates")
            dir.listFiles()?.forEach { it.delete() }
            if (!dir.isDirectory && !dir.mkdirs()) throw UpdateException("업데이트 파일을 저장할 폴더를 만들지 못했어요")
            if (dir.usableSpace < release.size + 32L * 1024 * 1024) throw UpdateException("저장 공간이 부족해요")
            val apk = File(dir, "ScheduleWidget-mobile-notes-${release.version}.apk")
            val part = File(dir, apk.name + ".part")
            var done = false
            try {
                val conn = openAsset(release.assetId, auth)
                try {
                    val declared = conn.contentLengthLong
                    if (declared >= 0 && declared != release.size) throw UpdateException("받을 파일 크기가 릴리즈 정보와 달라요")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    var lastReport = 0L
                    conn.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                // ponytail: a stalled socket read is bounded by readTimeout, not interrupted on cancel.
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > release.size) throw UpdateException("받은 파일이 릴리즈 정보보다 커요")
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                val now = System.nanoTime()
                                if (now - lastReport > 200_000_000L) { lastReport = now; onProgress(total) }
                            }
                        }
                    }
                    onProgress(total)
                    if (total != release.size) throw UpdateException("APK를 끝까지 받지 못했어요. 다시 시도해 주세요")
                    if (hex(digest.digest()) != release.sha256) throw UpdateException("APK 해시가 릴리즈 정보와 달라 받은 파일을 지웠어요")
                } finally {
                    conn.disconnect()
                }
                if (!part.renameTo(apk)) throw UpdateException("업데이트 파일을 저장하지 못했어요")
                validateApk(context, release, apk)
                done = true
                apk
            } finally {
                if (!done) { part.delete(); apk.delete() }
            }
          }
        }

    /**
     * Blocking (hashes the file): call off the main thread. Throws [UpdateException] unless [apk] matches the
     * release size/SHA-256, is this package, has a strictly higher versionCode than the installed app,
     * a versionName equal to [UpdateRelease.version], and the same non-empty signing certificate set.
     */
    fun validateApk(context: Context, release: UpdateRelease, apk: File) {
        try {
            checkRelease(release)
            if (!apk.isFile || apk.length() != release.size) throw UpdateException("APK 크기가 릴리즈 정보와 달라요")
            if (sha256(apk) != release.sha256) throw UpdateException("APK 해시가 릴리즈 정보와 달라요")
            val pm = context.packageManager
            val incoming = archiveInfo(pm, apk) ?: throw UpdateException("APK 파일을 읽을 수 없어요")
            val installed = installedInfo(pm, context.packageName)
            if (incoming.packageName != context.packageName) throw UpdateException("이 앱의 APK가 아니라서 설치할 수 없어요")
            if (PackageInfoCompat.getLongVersionCode(incoming) <= PackageInfoCompat.getLongVersionCode(installed)) {
                throw UpdateException("APK 버전 코드가 설치된 앱보다 높지 않아요")
            }
            if (compareVersions(incoming.versionName.orEmpty(), release.version) != 0) throw UpdateException("APK 버전 이름이 릴리즈 버전과 달라요")
            val signers = signers(incoming)
            if (signers.isEmpty() || signers != signers(installed)) throw UpdateException("APK 서명이 설치된 앱과 달라 설치할 수 없어요")
        } catch (e: UpdateException) {
            throw e
        } catch (e: Exception) {
            throw UpdateException("APK를 확인하지 못했어요")
        }
    }

    /** Only exact official GitHub download hosts over default-port HTTPS without credentials. */
    internal fun isAllowedDownloadUrl(url: URL): Boolean =
        url.protocol == "https" && url.userInfo == null && url.port == -1 && url.host.lowercase() in DOWNLOAD_HOSTS

    private suspend fun <T> io(networkMessage: String, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: UpdateException) {
            throw e
        } catch (e: IOException) {
            throw UpdateException(networkMessage)
        } catch (e: Exception) {
            throw UpdateException("업데이트 정보를 처리하지 못했어요")
        }
    }

    private fun checkToken(token: String): String {
        val t = token.trim()
        if (t.isNotEmpty() && !TOKEN.matches(t)) throw UpdateException("GitHub 토큰 형식이 올바르지 않아요")
        return t
    }

    private fun checkRelease(r: UpdateRelease) {
        val ok = r.assetId > 0 && r.size in 1..MAX_APK_BYTES && HEX64.matches(r.sha256) &&
            parseVersion(r.version)?.joinToString(".") == r.version && r.releaseUrl.startsWith("$RELEASES_URL/tag/")
        if (!ok) throw UpdateException("업데이트 정보가 올바르지 않아요")
    }

    private fun open(url: URL, token: String, accept: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "ScheduleWidget-mobile-notes")
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Encoding", "identity")
            if (url.protocol == "https" && url.host == API_HOST) {
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
            }
        }

    /** Body on 200, null on 404, [UpdateException] otherwise. */
    private fun getJson(url: String, token: String): String? {
        val conn = open(URL(url), token, "application/vnd.github+json")
        try {
            return when (conn.responseCode) {
                200 -> readLimited(conn.inputStream)
                404 -> null
                else -> throw failure(conn, token.isNotEmpty())
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Asset bytes from the numeric asset API, following only allow-listed HTTPS redirects by hand. */
    private fun openAsset(assetId: Long, token: String): HttpURLConnection {
        var url = URL("$API/releases/assets/$assetId")
        repeat(MAX_REDIRECTS + 1) {
            val conn = open(url, token, "application/octet-stream")
            val code = try { conn.responseCode } catch (e: Exception) { conn.disconnect(); throw e }
            if (code == 200) return conn
            if (code in setOf(301, 302, 303, 307, 308)) {
                val next = conn.getHeaderField("Location")?.let { runCatching { URL(url, it) }.getOrNull() }
                conn.disconnect()
                if (next == null || !isAllowedDownloadUrl(next)) throw UpdateException("허용되지 않은 다운로드 주소로 이동하려 해서 중단했어요")
                url = next
            } else {
                val error = if (url.host == API_HOST) failure(conn, token.isNotEmpty()) else UpdateException("다운로드 서버가 파일을 주지 않았어요 (코드 $code)")
                conn.disconnect()
                throw error
            }
        }
        throw UpdateException("다운로드 주소가 너무 여러 번 바뀌었어요")
    }

    private fun failure(conn: HttpURLConnection, hasToken: Boolean): UpdateException {
        val code = conn.responseCode
        val limited = code == 429 || (code == 403 &&
            (conn.getHeaderField("X-RateLimit-Remaining") == "0" || conn.getHeaderField("Retry-After") != null))
        return UpdateException(
            when {
                limited -> "GitHub 요청 한도를 넘었어요. 잠시 후 다시 시도해 주세요"
                code == 401 -> "GitHub 토큰이 올바르지 않거나 만료됐어요"
                code == 403 -> if (hasToken) "토큰에 이 저장소를 읽을 권한이 없어요" else "GitHub가 접근을 거부했어요. 잠시 후 다시 시도해 주세요"
                code == 404 -> "GitHub에서 릴리즈 파일을 찾을 수 없어요"
                code >= 500 -> "GitHub 서버에 문제가 있어요. 잠시 후 다시 시도해 주세요"
                else -> "GitHub 응답을 처리하지 못했어요 (코드 $code)"
            },
        )
    }

    private fun readLimited(input: InputStream): String = input.use {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = it.read(buf)
            if (n < 0) break
            if (out.size() + n > MAX_JSON_BYTES) throw UpdateException("GitHub 응답이 너무 커요")
            out.write(buf, 0, n)
        }
        out.toString("UTF-8")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    private fun signerFlag() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(signerFlag().toLong()))
        else pm.getPackageArchiveInfo(apk.path, signerFlag())

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, name: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(name, PackageManager.PackageInfoFlags.of(signerFlag().toLong()))
        else pm.getPackageInfo(name, signerFlag())

    /** Current APK signers only (not rotation history), compared as a set. */
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val list = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return list.orEmpty().map { it.toCharsString() }.toSet()
    }

    private fun bad() = UpdateException("GitHub 릴리즈 정보가 올바르지 않아요")
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    private fun JsonObject.bool(key: String) = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
}
