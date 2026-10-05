package com.schedulewidget.mobile.pet

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.schedulewidget.mobile.data.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * 캐릭터 구글 드라이브 보관 — the phone side of the desktop app's "캐릭터도 구글 드라이브에 보관".
 * Imported characters live in the Drive folder "ScheduleWidget 캐릭터" as one .zip each (appProperties petId/petName),
 * exactly like the PC, so characters imported on any PC show up here and the other way round.
 *
 * The app only has `drive.file` access (files this app's Google Cloud project created), so the Android OAuth client must
 * be in the same Cloud project as the desktop app — see README.md.
 */
object DrivePets {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val FILES = "https://www.googleapis.com/drive/v3/files"
    private const val ABOUT = "https://www.googleapis.com/drive/v3/about"
    private const val FOLDER = "ScheduleWidget 캐릭터"
    private const val MAX_ZIP = 30L * 1024 * 1024
    private val mutex = Mutex()

    sealed interface Auth {
        data class Token(val value: String) : Auth
        data class NeedsConsent(val request: IntentSenderRequest) : Auth
        data class Failed(val message: String) : Auth
    }

    data class Outcome(val downloaded: Int, val uploaded: Int, val deleted: Int, val failed: Int)

    /** From an Activity this may ask for consent; from the app context it only succeeds when already granted. */
    suspend fun authorize(context: Context): Auth = suspendCancellableCoroutine { cont ->
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
        Identity.getAuthorizationClient(context).authorize(request)
            .addOnSuccessListener { r ->
                val pending = r.pendingIntent
                cont.resume(
                    when {
                        r.hasResolution() && pending != null -> Auth.NeedsConsent(IntentSenderRequest.Builder(pending.intentSender).build())
                        r.accessToken != null -> Auth.Token(r.accessToken!!)
                        else -> Auth.Failed("구글 드라이브 권한을 받지 못했습니다.")
                    }
                )
            }
            .addOnFailureListener { if (cont.isActive) cont.resume(Auth.Failed(describe(it))) }
            .addOnCanceledListener { if (cont.isActive) cont.resume(Auth.Failed("로그인을 취소했습니다.")) }
    }

    fun tokenFrom(activity: Activity, data: Intent?): Auth = runCatching {
        Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data).accessToken
            ?.let { Auth.Token(it) } ?: Auth.Failed("구글 드라이브 권한을 받지 못했습니다.")
    }.getOrElse { Auth.Failed(describe(it)) }

    private fun describe(e: Throwable): String = when ((e as? ApiException)?.statusCode) {
        CommonStatusCodes.DEVELOPER_ERROR ->
            "이 앱의 구글 로그인이 아직 등록되지 않았습니다. PC 앱과 같은 Google Cloud 프로젝트에 Android OAuth 클라이언트를 추가해 주세요. (README 참고)"
        CommonStatusCodes.CANCELED -> "로그인을 취소했습니다."
        CommonStatusCodes.NETWORK_ERROR -> "네트워크에 연결할 수 없습니다."
        else -> "구글 로그인 실패: ${e.message ?: e.javaClass.simpleName}"
    }

    /** Silent sync (app start / resume): only when switched on and access was granted before. */
    suspend fun syncIfEnabled(context: Context): Outcome? {
        val app = context.applicationContext
        val repo = Repository.get(app)
        if (!repo.data.value.petDrive.enabled) return null
        val token = when (val auth = authorize(app)) {
            is Auth.Token -> auth.value
            is Auth.NeedsConsent -> {
                setLastError(repo, "구글 드라이브 권한이 필요합니다. 캐릭터 설정에서 ‘지금 동기화’를 눌러 권한을 허용해 주세요.")
                return null
            }
            is Auth.Failed -> {
                setLastError(repo, "구글 드라이브 인증에 실패했습니다. 캐릭터 설정에서 다시 연결해 주세요. ${auth.message}")
                return null
            }
        }
        return try {
            sync(app, token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun setLastError(repo: Repository, message: String) {
        if (repo.data.value.petDrive.lastError != message) {
            repo.update { it.copy(petDrive = it.petDrive.copy(lastError = message)) }
        }
    }

    /**
     * Same rules as the desktop PetSyncPlan: characters here go up, characters on Drive come down, one deleted here after
     * it was synced is deleted on Drive; nothing is deleted here automatically; a name already on the other side (built-in
     * names included) is neither uploaded nor downloaded, so the same pet never appears twice.
     */
    suspend fun sync(context: Context, token: String): Outcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val repo = Repository.get(app)
            val coroutineContext = currentCoroutineContext()
            val accountPermissionId = accountPermissionId(token)
            val folder = folderId(token)
            val remote = listRemote(token, folder) // petId -> (fileId, name)
            val local = PetImport.importedNames(app)
            val builtIn = Characters.list(app).filter { !it.imported }.map { it.name }
            val previous = repo.data.value.petDrive
            // A missing id is an older install, so its markers cannot safely prove that a remote deletion belongs to
            // this account. Dropping them may re-import an orphaned character once, but cannot delete another account's.
            val synced = if (previous.accountPermissionId == accountPermissionId) previous.syncedIds.toMutableSet()
                else mutableSetOf()

            fun key(name: String) = name.trim().lowercase()
            val namesHere = (local.values + builtIn).map(::key).toMutableSet()
            val namesThere = remote.values.map { key(it.second) }.toMutableSet()
            val upload = local.keys.sorted().filter { id -> id !in remote && id !in synced && namesThere.add(key(local.getValue(id))) }
            val deleteRemote = mutableListOf<String>()
            val download = mutableListOf<String>()
            for (id in remote.keys.sorted()) {
                if (id in local) continue
                if (id in synced) deleteRemote += id
                else if (isPetId(id) && namesHere.add(key(remote.getValue(id).second))) download += id
            }

            var failed = 0
            var uploaded = 0
            var downloaded = 0
            val deleted = mutableSetOf<String>()
            for (id in deleteRemote) {
                coroutineContext.ensureActive()
                try {
                    request(token, "DELETE", "$FILES/${enc(remote.getValue(id).first)}", allowMissing = true)
                    synced -= id
                    deleted += id
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed++
                }
            }
            for (id in upload) {
                coroutineContext.ensureActive()
                try {
                    upload(token, folder, id, local.getValue(id), PetImport.exportZip(app, id))
                    synced += id
                    uploaded++
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed++
                }
            }
            for (id in download) {
                coroutineContext.ensureActive()
                try {
                    val zip = downloadBytes(token, "$FILES/${enc(remote.getValue(id).first)}?alt=media")
                    val result = PetImport.fromZipBytes(app, zip, forcedId = id)
                    if (result !is PetImport.Result.Imported) error((result as? PetImport.Result.Failed)?.message ?: "가져오기 실패")
                    synced += id
                    downloaded++
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failed++
                }
            }
            coroutineContext.ensureActive()
            local.keys.filter { it in remote }.forEach { synced += it }
            val nowLocal = PetImport.importedNames(app).keys
            // Keep an id while the character is here or still on Drive; forget it once it is gone from both. A delete that
            // failed keeps its id, otherwise the next sync would see an unknown Drive file and download it again.
            val keep = synced.filter { it in nowLocal || (it in remote && it !in deleted) }.sorted()
            repo.update { it.copy(petDrive = it.petDrive.copy(accountPermissionId = accountPermissionId,
                syncedIds = keep, lastSync = System.currentTimeMillis(), lastError = null)) }
            if (downloaded > 0) Characters.invalidate()
            Outcome(downloaded, uploaded, deleted.size, failed)
        }
    }

    private fun isPetId(id: String) = Characters.isPetId(id)

    /** Drive's opaque user permission ID is account-specific and works with the existing drive.file scope. */
    private fun accountPermissionId(token: String): String =
        request(token, "GET", "$ABOUT?fields=" + enc("user(permissionId)")).optJSONObject("user")
            ?.optString("permissionId")?.takeIf { it.isNotBlank() }
            ?: error("구글 드라이브 계정을 확인하지 못해 동기화를 중단했습니다.")

    /** A string literal inside a Drive `q=` query: backslash and single quote escaped. */
    private fun lit(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun folderId(token: String): String {
        val q = "mimeType='application/vnd.google-apps.folder' and name=${lit(FOLDER)} and trashed=false"
        val found = request(token, "GET", "$FILES?pageSize=10&fields=files(id)&q=" + enc(q)).optJSONArray("files")
        found?.optJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }?.let { return it }
        val created = request(token, "POST", "$FILES?fields=id",
            JSONObject().put("name", FOLDER).put("mimeType", "application/vnd.google-apps.folder"))
        return created.getString("id")
    }

    private fun listRemote(token: String, folder: String): Map<String, Pair<String, String>> {
        val out = linkedMapOf<String, Pair<String, String>>()
        var page: String? = null
        do {
            val q = "${lit(folder)} in parents and trashed=false"
            val json = request(token, "GET", "$FILES?pageSize=1000&fields=nextPageToken,files(id,name,appProperties)&q=" + enc(q) +
                (page?.let { "&pageToken=" + enc(it) } ?: ""))
            val files = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val props = f.optJSONObject("appProperties")
                val petId = props?.optString("petId").orEmpty()
                val name = props?.optString("petName")?.takeIf { it.isNotBlank() } ?: f.optString("name").removeSuffix(".zip")
                if (isPetId(petId) && petId !in out) out[petId] = f.getString("id") to name
            }
            page = json.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        return out
    }

    private fun upload(token: String, folder: String, petId: String, name: String, zip: ByteArray) {
        // Same as desktop: Windows-invalid file name characters (incl. control chars) removed; petName cut to 30 UTF-16
        // units (appProperties key+value must stay ≤124 bytes), without splitting a surrogate pair.
        val safe = name.filterNot { it in "\\/:*?\"<>|" || it.code < 32 }.trim().ifBlank { "캐릭터" }
        val petName = if (name.length <= 30) name else name.take(if (name[29].isHighSurrogate()) 29 else 30)
        val meta = JSONObject()
            .put("name", "$safe.zip")
            .put("parents", JSONArray().put(folder))
            .put("appProperties", JSONObject().put("petId", petId).put("petName", petName))
        val boundary = "sw" + java.util.UUID.randomUUID().toString().replace("-", "")
        val head = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$boundary\r\nContent-Type: application/zip\r\n\r\n").toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val conn = open(token, "POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
        conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(head.size.toLong() + zip.size + tail.size) // no second in-memory copy
        try {
            conn.outputStream.use { it.write(head); it.write(zip); it.write(tail) }
        } catch (e: Exception) {
            conn.disconnect(); throw e
        }
        finish(conn, allowMissing = false)
    }

    private fun downloadBytes(token: String, url: String): ByteArray {
        val conn = open(token, "GET", url)
        try {
            if (conn.responseCode !in 200..299) fail(conn)
            if (conn.contentLengthLong > MAX_ZIP) error("구글 드라이브의 캐릭터 파일이 너무 큽니다.")
            return conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_ZIP) error("구글 드라이브의 캐릭터 파일이 너무 큽니다.")
                }
                out.toByteArray()
            }
        } finally { conn.disconnect() }
    }

    private fun request(token: String, method: String, url: String, body: JSONObject? = null, allowMissing: Boolean = false): JSONObject {
        val conn = open(token, method, url)
        if (body != null) {
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            } catch (e: Exception) {
                conn.disconnect(); throw e
            }
        }
        return finish(conn, allowMissing)
    }

    private fun open(token: String, method: String, url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        setRequestProperty("Authorization", "Bearer $token")
        connectTimeout = 15000
        readTimeout = 60000
    }

    private fun finish(conn: HttpURLConnection, allowMissing: Boolean): JSONObject {
        try {
            val code = conn.responseCode
            if (allowMissing && code == 404) return JSONObject()
            if (code !in 200..299) fail(conn)
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { conn.disconnect() }
    }

    private fun fail(conn: HttpURLConnection): Nothing {
        val code = conn.responseCode
        val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val reason = runCatching { JSONObject(body).getJSONObject("error").optString("message") }.getOrNull()
        error(
            when (code) {
                401 -> "구글 로그인이 만료되었습니다. 다시 시도해 주세요."
                403 -> "구글 드라이브를 사용할 수 없습니다: ${reason ?: "권한 없음"} (Cloud 프로젝트에서 Google Drive API 사용 설정 필요)"
                else -> "구글 드라이브 오류 $code${reason?.let { ": $it" } ?: ""}"
            }
        )
    }

    /** Percent-encoding for a URL component (space as %20, not '+'; Korean as UTF-8). */
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
