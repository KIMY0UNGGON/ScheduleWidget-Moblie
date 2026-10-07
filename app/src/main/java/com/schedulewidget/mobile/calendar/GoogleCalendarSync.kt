package com.schedulewidget.mobile.calendar

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.GoogleAuthUtil
import com.schedulewidget.mobile.data.AppData
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.data.ScheduleItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.time.DateTimeException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Unknown saved account identity means its event IDs cannot safely be used against the signed-in primary calendar. */
internal fun forGoogleCalendarAccount(snapshot: AppData, account: String): AppData {
    val settings = snapshot.googleCalendar
    val hasPriorLinks = settings.syncedEventIds.isNotEmpty() || settings.ownedEventIds.isNotEmpty() || settings.hiddenEvents.isNotEmpty() ||
        snapshot.schedules.any {
            it.googleEventId != null || it.googleSyncedHash != null || it.googleSyncedPeriod != null ||
                it.googleEndMinutes != null || it.googleRefusedHash != null
        }
    if (!settings.account.isNullOrBlank() || !hasPriorLinks) return snapshot
    return snapshot.copy(
        schedules = snapshot.schedules.map {
            it.copy(
                googleEventId = null, googleSyncedHash = null, googleSyncedPeriod = null,
                googleEndMinutes = null, googleRefusedHash = null,
            )
        },
        googleCalendar = settings.copy(
            account = account, syncedEventIds = emptyList(), ownedEventIds = emptyList(), hiddenEvents = emptyMap(),
        ),
    )
}

/**
 * 구글 캘린더 양방향 연동 — the same sync the PC app does: the app's schedules and the signed-in account's primary Google
 * calendar are kept in step (same fields GoogleEventId / GoogleSyncedHash / GoogleEndMinutes / GoogleRefusedHash, same
 * private properties "scheduleWidgetCompleted" and "scheduleWidget"), so the phone, the PC and Google Calendar all show the
 * same list and both apps can sync the same account without fighting.
 *
 * Rules (desktop GoogleCalendarSync.Plan): every schedule here goes to Google and every Google event comes here (a
 * repeating one only for its next 8 weeks); the side that changed since the last sync wins (both → here wins). A schedule
 * deleted here deletes its Google event only when an app made it (the "scheduleWidget" mark) and it has no guests; any
 * other event only leaves the app and is never brought back (HiddenEvents). An event deleted on Google leaves the
 * schedule here. An event Google refuses is skipped until the schedule is edited; the rest of the sync goes on.
 */
object GoogleCalendarSync {
    private class DesktopImportSuperseded : IllegalStateException("일정 파일을 가져오는 동안 구글 동기화를 중단했습니다. 가져온 일정으로 다시 동기화해 주세요.")

    /**
     * Line for a delete confirmation of [item] with the sync on: "구글 캘린더에서도 삭제됩니다." when deleting it also
     * deletes its Google event, "구글 캘린더에는 남습니다." when the event stays on Google; null when not synced.
     */
    fun deleteNote(context: Context, item: com.schedulewidget.mobile.data.ScheduleItem): String? {
        val settings = Repository.get(context.applicationContext).data.value.googleCalendar
        if (item.googleEventId.isNullOrEmpty()) return null
        val deletes = deletesOnGoogle(settings.ownedEventIds, item)
        if (!settings.enabled) return if (deletes) "구글 캘린더 연동을 다시 켜면 구글에서도 삭제됩니다." else null
        return if (deletes) "구글 캘린더에서도 삭제됩니다." else "구글 캘린더에는 남습니다."
    }

    /** desktop DeletesOnGoogle: made by an app (owned, which never holds shared or repeating events). */
    private fun deletesOnGoogle(owned: Collection<String>, item: ScheduleItem): Boolean {
        val id = item.googleEventId
        return !id.isNullOrEmpty() && id in owned && recurringInstanceDate(id) == null
    }

    // New events sent in one sync at most, with a pause between them (Google limits how fast events are made).
    private const val MAX_INSERTS = 100
    private const val INSERT_PACING_MS = 250L
    private val mutex = Mutex()

    sealed interface Auth {
        data class Token(val value: String) : Auth
        data class NeedsConsent(val request: IntentSenderRequest) : Auth
        data class Failed(val message: String) : Auth
    }

    /** [more]: new schedules still wait (sent in batches, the next one follows shortly). [refused]: skipped until edited. */
    data class Outcome(val added: Int, val sent: Int, val deleted: Int, val updated: Int, val refused: Int = 0, val more: Boolean = false)
    fun hash(title: String?, period: String?, time: String?, completed: Boolean, endPeriod: String? = null) =
        GoogleCalendarProtocol.hash(title, period, time, completed, endPeriod)

    private fun hash(i: ScheduleItem) = GoogleCalendarProtocol.hash(i)
    private fun hash(e: CalendarRemote) = GoogleCalendarProtocol.hash(e)
    private fun isRefused(i: ScheduleItem) = GoogleCalendarProtocol.isRefused(i)
    fun eventIdFor(item: ScheduleItem): String = GoogleCalendarProtocol.eventIdFor(item)
    private fun newEventId() = GoogleCalendarProtocol.newEventId()
    private fun recurringInstanceDate(id: String?) = GoogleCalendarProtocol.recurringInstanceDate(id)
    private fun insertBody(item: ScheduleItem, id: String) = GoogleCalendarProtocol.insertBody(item, id)
    private fun patchBody(item: ScheduleItem, owned: Boolean) = GoogleCalendarProtocol.patchBody(item, owned)

    private fun readEvents(token: String, today: LocalDate) = GoogleCalendarApi.readEvents(token, today)
    private fun parseOrNull(e: JSONObject?) = GoogleCalendarApi.parseOrNull(e)
    private fun isShared(e: JSONObject) = GoogleCalendarApi.isShared(e)
    private fun eventUrl(id: String) = GoogleCalendarApi.eventUrl(id)
    private fun get(token: String, url: String) = GoogleCalendarApi.get(token, url)
    private fun request(token: String, method: String, url: String, body: JSONObject?, allowMissing: Boolean) =
        GoogleCalendarApi.request(token, method, url, body, allowMissing)

    // ---- sign-in ----

    suspend fun authorize(context: Context): Auth = GoogleCalendarAuthorization.authorize(context)

    fun tokenFrom(activity: Activity, data: Intent?): Auth = GoogleCalendarAuthorization.tokenFrom(activity, data)

    /**
     * 연결 해제 (desktop SignOutAsync): the sync goes off, the account and every Google link (synced / owned / hidden ids,
     * the schedules' event ids) are forgotten, and the access token Play services cached for this app is dropped. Nothing
     * is deleted on Google. The grant itself is not revoked on Google: the same OAuth project also serves the PC app and
     * the Drive character backup, which would be signed out with it.
     */
    suspend fun signOut(context: Context) {
        val app = context.applicationContext
        WorkManager.getInstance(app).apply {
            cancelUniqueWork("google-calendar-periodic")
            cancelUniqueWork("google-calendar-soon")
            cancelUniqueWork("google-calendar-more")
        }
        // Under the sync lock: a sync still running would otherwise write its links back after they were cleared.
        mutex.withLock {
            Repository.get(app).update { d ->
                d.copy(
                    schedules = d.schedules.map { s ->
                        if (s.googleEventId == null && s.googleSyncedHash == null && s.googleSyncedPeriod == null &&
                            s.googleEndMinutes == null && s.googleRefusedHash == null) s
                        else s.copy(googleEventId = null, googleSyncedHash = null, googleSyncedPeriod = null,
                            googleEndMinutes = null, googleRefusedHash = null)
                    },
                    googleCalendar = d.googleCalendar.copy(
                        enabled = false, account = null, syncedEventIds = emptyList(), ownedEventIds = emptyList(),
                        hiddenEvents = emptyMap(), lastSync = 0, lastError = null,
                    ),
                )
            }
        }
        // Only a silently available token is looked up (no consent screen); clearToken blocks, so off the main thread.
        val token = (runCatching { authorize(app) }.getOrNull() as? Auth.Token)?.value ?: return
        withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(app, token) } }
    }

    // ---- background triggers ----

    // Offline runs would only record a "no connection" error; wait for the network instead.
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Soon after a schedule changes (like the PC's 4-second timer), plus every 15 minutes while switched on. */
    fun requestSoon(context: Context) {
        val app = context.applicationContext
        if (!Repository.get(app).data.value.googleCalendar.enabled) return
        WorkManager.getInstance(app).enqueueUniqueWork(
            "google-calendar-soon", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(4, TimeUnit.SECONDS).setConstraints(online).build(),
        )
    }

    /** The next batch of new events (Outcome.more): appended, so a running batch is never cancelled by its follower. */
    fun requestMore(context: Context) {
        val app = context.applicationContext
        if (!Repository.get(app).data.value.googleCalendar.enabled) return
        WorkManager.getInstance(app).enqueueUniqueWork(
            "google-calendar-more", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(5, TimeUnit.SECONDS).setConstraints(online).build(),
        )
    }

    fun schedulePeriodic(context: Context) {
        val app = context.applicationContext
        val wm = WorkManager.getInstance(app)
        if (Repository.get(app).data.value.googleCalendar.enabled) {
            wm.enqueueUniquePeriodicWork(
                "google-calendar-periodic", ExistingPeriodicWorkPolicy.UPDATE, // UPDATE: installs pick up the network constraint
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build(),
            )
        } else wm.cancelUniqueWork("google-calendar-periodic")
    }

    class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            syncIfEnabled(applicationContext)
            return Result.success()
        }
    }

    /** Silent sync: only when switched on and access was granted before. */
    suspend fun syncIfEnabled(context: Context): Outcome? {
        val app = context.applicationContext
        val repo = Repository.get(app)
        if (!repo.data.value.googleCalendar.enabled) return null
        val token = when (val auth = authorize(app)) {
            is Auth.Token -> auth.value
            is Auth.NeedsConsent -> {
                val message = "구글 캘린더 권한을 업데이트해야 합니다. 캘린더 설정에서 ‘지금 동기화’를 눌러 권한을 허용해 주세요."
                if (repo.data.value.googleCalendar.lastError != message) {
                    repo.update { it.copy(googleCalendar = it.googleCalendar.copy(lastError = message)) }
                }
                return null
            }
            is Auth.Failed -> {
                if (repo.data.value.googleCalendar.enabled && repo.data.value.googleCalendar.lastError != auth.message) {
                    repo.update { d ->
                        if (!d.googleCalendar.enabled) d
                        else d.copy(googleCalendar = d.googleCalendar.copy(lastError = auth.message))
                    }
                }
                return null
            }
        }
        return try {
            runSync(app, token, requireEnabled = true)?.also { if (it.more) requestMore(app) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Repository.get(app).update { it.copy(googleCalendar = it.googleCalendar.copy(lastError = e.message)) }
            null
        }
    }

    // One event Google will not take as it is, or whose date cannot be written: skipped, the others go on (desktop IsRefusal).
    private fun isRefusal(e: Exception) = if (e is GoogleCalendarApi.RequestFailed) e.refusal else e is DateTimeException || e is IllegalArgumentException

    // ---- sync ----

    suspend fun sync(context: Context, token: String): Outcome =
        checkNotNull(runSync(context.applicationContext, token, requireEnabled = false))

    private suspend fun runSync(app: Context, token: String, requireEnabled: Boolean): Outcome? = mutex.withLock {
        // A worker may finish authorization after disconnect already cleared the links. Check again while holding the
        // same lock used by signOut, so that this late worker cannot recreate them after the disconnect.
        val repo = Repository.get(app)
        if (!repo.data.value.googleCalendar.enabled) {
            if (requireEnabled) return@withLock null
            throw IllegalStateException("구글 캘린더 연동이 꺼져 있습니다.")
        }
        withContext(Dispatchers.IO) {
            try {
                syncLocked(app, token)
            } catch (e: GoogleCalendarApi.Unauthorized) {
                // authorize() hands back Play services' cached token; drop it so the next attempt gets a fresh one.
                runCatching { GoogleAuthUtil.clearToken(app, token) }
                throw e
            } catch (e: IOException) {
                throw IllegalStateException("구글에 연결할 수 없습니다. 인터넷 연결을 확인해 주세요.", e)
            }
        }
    }

    private fun syncLocked(app: Context, token: String): Outcome {
        val repo = Repository.get(app)
        val (importEpoch, snapshot) = repo.captureImportSnapshot()
        check(snapshot.googleCalendar.enabled) { "구글 캘린더 연동이 꺼져 있습니다." }
        fun ensureNoImport() {
            if (repo.currentImportEpoch() != importEpoch) throw DesktopImportSuperseded()
        }
        // Event ids and delete ownership are account-specific. Never plan a sync against the primary calendar if its
        // identity could not be read: treating an unknown account as the last account can patch/delete that account's
        // events using links saved for a different Google account.
        val account = get(token, "https://www.googleapis.com/calendar/v3/users/me/calendarList/primary?fields=id")
            .optString("id")
            .ifBlank { throw IllegalStateException("구글 기본 캘린더 계정을 확인하지 못해 동기화를 중단했습니다.") }
        val today = LocalDate.now()
        val remote = readEvents(token, today)
        val plan = GoogleCalendarPlanner.plan(forGoogleCalendarAccount(snapshot, account), account, today, remote)
        ensureNoImport()
        val local = plan.local
        val syncedIds = plan.syncedIds
        val owned = plan.owned
        val hidden = plan.hidden
        val localByEvent = plan.localByEvent
        val remoteById = plan.remoteById
        val deleteRemote = plan.deleteRemote
        val unlink = plan.unlink
        val patch = plan.patch
        val insert = plan.insert
        val fresh = plan.fresh
        val updateLocal = plan.updateLocal
        val updateLength = plan.updateLength
        val addLocal = plan.addLocal
        val prune = plan.prune
        val remoteOf = plan.remoteOf
        // ---- run ----
        val work = LinkedHashMap<String, ScheduleItem>().apply { local.forEach { put(it.id, it) } }
        val synced = syncedIds.toMutableSet()
        // Ids this sync may keep as synced although no schedule has them at the end: one the user deletes while the
        // requests are out must stay "synced", so the next sync handles the delete instead of bringing it back.
        val ours = (syncedIds + local.mapNotNull { it.googleEventId?.ifEmpty { null } }).toMutableSet()
        var patched = 0
        var inserted = 0
        var deletedCount = 0
        var refused = 0
        var more = false
        val read = remoteById.keys

        fun pending(item: ScheduleItem, id: String): ScheduleItem {
            synced += id; owned += id; ours += id
            return item.copy(googleEventId = id, googleSyncedHash = null, googleSyncedPeriod = null).also { work[it.id] = it }
        }
        fun drop(id: String) { synced -= id; owned -= id; ours -= id }
        fun refuse(item: ScheduleItem, h: String) {
            work[item.id] = item.copy(googleRefusedHash = h) // not sent again until it is edited here
            refused++
        }

        for ((id, date) in unlink) {
            synced -= id; owned -= id
            hidden[id] = date
        }
        // A request failing for the whole sync (offline, signed out, rate limit) stops it (as on the PC), but what already
        // happened on Google is still recorded below — otherwise events created before the failure would come back as
        // duplicate schedules next time. One event Google refuses is skipped and the sync goes on.
        var failure: Exception? = null
        try {
            for (id in deleteRemote) {
                ensureNoImport()
                // Not read this time (outside the read range): looked at first. One that got guests or became a repeating
                // event on Google meanwhile stays there, out of the app only; one already gone there needs nothing.
                if (id !in read) {
                    ensureNoImport()
                    val there = try { request(token, "GET", eventUrl(id), null, allowMissing = true) }
                    catch (e: GoogleCalendarApi.RequestFailed) { if (!e.refusal) throw e; JSONObject().put("_unknown", true) } // cannot look: deleted as planned
                    if (there == null || there.optString("status") == "cancelled") { drop(id); continue }
                    if (!there.has("_unknown") && (isShared(there) || there.has("recurrence") || there.has("recurringEventId"))) {
                        hidden[id] = parseOrNull(there)?.lastPeriod ?: ""
                        drop(id)
                        continue
                    }
                }
                try {
                    ensureNoImport()
                    request(token, "DELETE", eventUrl(id), null, allowMissing = true)
                    deletedCount++
                } catch (e: GoogleCalendarApi.RequestFailed) {
                    if (!e.refusal) throw e
                    hidden[id] = ""; refused++ // Google keeps it: out of the app only
                }
                // A transient GET/DELETE failure must keep the deletion pending for the next sync.
                drop(id)
            }

            for (p in patch) {
                ensureNoImport()
                var item = work[p.id] ?: continue
                val h = hash(item)
                remoteOf[item.id]?.let { there ->
                    // Its length on Google now: a move keeps it.
                    if (item.googleEndMinutes != there.endMinutes) item = item.copy(googleEndMinutes = there.endMinutes).also { work[it.id] = it }
                }
                val answer = try {
                    val body = patchBody(item, item.googleEventId in owned)
                    if (body.length() == 0) { work[item.id] = markSynced(item, h); continue }
                    ensureNoImport()
                    request(token, "PATCH", eventUrl(item.googleEventId!!), body, allowMissing = true)
                } catch (e: Exception) {
                    if (!isRefusal(e)) throw e
                    refuse(item, h); continue
                }
                if (answer == null || answer.optString("status") == "cancelled") {
                    // Gone on Google meanwhile: the edited schedule is added again, under a new id (a deleted one stays taken).
                    fresh += item.id
                    insert += item
                    continue
                }
                work[item.id] = accept(item, answer, h)
                patched++
            }

            var sent = 0
            for (p in insert) {
                ensureNoImport()
                var item = work[p.id] ?: continue
                if (sent >= MAX_INSERTS) { more = true; break }
                if (sent++ > 0) Thread.sleep(INSERT_PACING_MS) // a first sync can add hundreds: Google limits how fast
                val h = hash(item)
                val old = item.googleEventId?.ifEmpty { null }
                var id = if (item.id in fresh) newEventId() else old ?: eventIdFor(item)
                if (old != null && old != id && work.values.none { it.id != item.id && it.googleEventId == old }) drop(old)
                item = pending(item, id)
                var adopted = false
                var answer: JSONObject? = try {
                    ensureNoImport()
                    request(token, "POST", GoogleCalendarApi.EVENTS, insertBody(item, id), allowMissing = false)
                } catch (e: Exception) {
                    if (e is GoogleCalendarApi.RequestFailed && e.code == 409) {
                        // The id is taken: by an earlier request of this app or the PC (its answer never came), or by an
                        // event deleted since.
                        val there = try { request(token, "GET", eventUrl(id), null, allowMissing = true) }
                        catch (g: GoogleCalendarApi.RequestFailed) { if (!g.refusal) throw g; refuse(item, h); continue }
                        adopted = there != null && there.optString("status") != "cancelled"
                        if (adopted) there else null
                    } else {
                        if (!isRefusal(e)) throw e
                        refuse(item, h); continue
                    }
                }
                if (answer == null) {
                    drop(id)
                    id = newEventId()
                    item = pending(item, id)
                    answer = try {
                        ensureNoImport()
                        request(token, "POST", GoogleCalendarApi.EVENTS, insertBody(item, id), allowMissing = false)
                    }
                    catch (e: Exception) { if (!isRefusal(e)) throw e; refuse(item, h); continue }
                }
                val given = answer!!.optString("id")
                if (given.isNotEmpty() && given != id) { drop(id); id = given; item = pending(item, id) } // Google chose its own id
                val made = parseOrNull(answer)
                if (made?.shared == true) owned -= id
                work[item.id] = if (adopted && made != null) {
                    // Linked to the event made the first time: whatever differs from it now goes up with the next sync.
                    item.copy(googleRefusedHash = null, googleEndMinutes = made.endMinutes, googleSyncedHash = hash(made), googleSyncedPeriod = made.period)
                } else accept(item, answer, h)
                inserted++
            }

            for ((item, e) in updateLocal) {
                work[item.id] = applyRemote(work[item.id] ?: item, e)
                synced += e.id
            }
            for ((item, e) in updateLength) {
                val w = work[item.id] ?: item
                if (hash(w) == w.googleSyncedHash) work[item.id] = w.copy(googleEndMinutes = e.endMinutes) // lengthened / shortened on Google
            }
        } catch (e: Exception) {
            failure = e
        }
        val added = if (failure != null) emptyList()
        else addLocal.map { e -> applyRemote(ScheduleItem(googleEventId = e.id), e).also { synced += e.id } }
        // Last, once every request went through (a sync cut short must not leave them looking "deleted here").
        val pruneIds = if (failure != null) emptySet() else prune.map { it.id }.toSet()

        // ---- merge back ----
        // Edits the user made while the requests were out are kept (their content differs from the synced hash, so they
        // are sent next time); the Google link this sync made (new/changed event id, synced hash) is always kept, or the
        // new event would come back as a second schedule. A schedule deleted meanwhile keeps its event id in
        // SyncedEventIds so the next sync handles its delete (instead of bringing it back).
        val before = snapshot.schedules.associateBy { it.id }
        val committed = repo.updateIfImportUnchanged(importEpoch) { cur ->
            // Pure: MutableStateFlow.update may run this more than once.
            val pruned = cur.schedules.filter { it.id in pruneIds && it == before[it.id] }
            val prunedEvents = pruned.mapNotNull { it.googleEventId?.ifEmpty { null } }.toSet()
            val merged = cur.schedules.filter { it !in pruned }.map { c ->
                val w = work[c.id] ?: return@map c
                val b = before[c.id]
                when {
                    b == null || c == b -> w
                    c.googleEventId == b.googleEventId -> c.copy(
                        googleEventId = w.googleEventId, googleSyncedHash = w.googleSyncedHash,
                        googleSyncedPeriod = w.googleSyncedPeriod, googleEndMinutes = w.googleEndMinutes,
                        googleRefusedHash = w.googleRefusedHash,
                    )
                    else -> c
                }
            } + added
            val now = merged.mapNotNull { it.googleEventId?.ifEmpty { null } }.toSet()
            // desktop: SyncedEventIds = what is linked now, plus what was here during this sync and got deleted meanwhile.
            val keptSynced = (synced + now).filter { (it in now || it in ours) && it !in prunedEvents }.distinct().sorted()
            val kept = keptSynced.toSet()
            cur.copy(
                schedules = merged,
                googleCalendar = cur.googleCalendar.copy(
                    account = account ?: cur.googleCalendar.account,
                    syncedEventIds = keptSynced,
                    ownedEventIds = owned.filter { it in kept }.sorted(),
                    hiddenEvents = trimHidden(hidden, kept, read, today),
                    lastSync = if (failure == null) System.currentTimeMillis() else cur.googleCalendar.lastSync,
                    lastError = when {
                        failure != null -> cur.googleCalendar.lastError
                        refused > 0 -> "구글이 받지 않은 일정 ${refused}개는 수정하면 다시 보냅니다."
                        else -> null
                    },
                ),
            )
        }
        if (!committed) throw DesktopImportSuperseded()
        failure?.let { throw it }
        return Outcome(added.size, inserted + patched, deletedCount, updateLocal.size, refused, more)
    }

    // Events taken out of the app stay out while a read may still bring them: dropped once their date is over a month past
    // (no read reaches further back), or, date unknown, once a (full) read no longer has them. At most 5000, latest kept.
    private fun trimHidden(hidden: Map<String, String>, linked: Set<String>, read: Set<String>, today: LocalDate): Map<String, String> {
        val past = today.minusDays(31)
        return hidden.filter { (id, date) ->
            val gone = if (date.isEmpty()) id !in read else date.toDateOrNull()?.isBefore(past) ?: true
            !gone && id !in linked
        }.entries.sortedByDescending { it.value.ifEmpty { "9999" } }.take(5000).sortedBy { it.key }.associate { it.key to it.value }
    }

    private fun String.toDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

    private fun markSynced(item: ScheduleItem, h: String) = item.copy(googleRefusedHash = null, googleSyncedHash = h, googleSyncedPeriod = item.period)

    // A PATCH / insert went through: the schedule takes the event Google sent back — which also brings what was changed
    // only on Google meanwhile (the fields not sent). An edit made here during the request is kept by the merge above.
    private fun accept(item: ScheduleItem, answer: JSONObject, h: String): ScheduleItem {
        val there = parseOrNull(answer)
        return if (there != null && hash(item) == h) applyRemote(item.copy(googleRefusedHash = null), there)
        else markSynced(item, h)
    }

    private fun applyRemote(item: ScheduleItem, e: CalendarRemote) = item.copy(
        title = e.title, period = e.period, endPeriod = e.endPeriod, time = e.time, isCompleted = e.completed,
        googleEndMinutes = e.endMinutes, googleSyncedHash = hash(e), googleSyncedPeriod = e.period,
    )

}
