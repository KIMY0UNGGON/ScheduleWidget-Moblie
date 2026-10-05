package com.schedulewidget.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.Bundle
import android.system.Os
import com.schedulewidget.mobile.calendar.GoogleCalendarSync
import com.schedulewidget.mobile.data.GoogleCalendarLink
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.data.ScheduleItem
import com.schedulewidget.mobile.notes.NoteStore
import com.schedulewidget.mobile.notes.editor.EditorState
import com.schedulewidget.mobile.notes.editor.TextEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLStreamHandler
import java.time.LocalDate

/** Android storage/network regression checks; run on a disposable emulator with adb am instrument -w. */
class AuditInstrumentation : Instrumentation() {
    private var listed = true
    private var offline = false
    private var deletes = 0
    private var identityUnavailable = false
    private val eventId = "sched0123456789abcdef"
    private val account = "audit@example.invalid"
    private val day = LocalDate.now()
    private val event = """{"id":"sched0123456789abcdef","summary":"Delete test","start":{"date":"$day"},"end":{"date":"${day.plusDays(1)}"},"extendedProperties":{"private":{"scheduleWidget":"1"}}}"""

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val repo = Repository.get(targetContext)
        val original = repo.data.value
        val originalJson = repo.exportJson()
        val results = mutableListOf<String>()
        var failures = 0
        fun test(name: String, action: () -> Unit) {
            try { action(); results += "PASS: $name" }
            catch (e: Throwable) { failures++; results += "FAIL: $name: " + e.stackTraceToString() }
        }
        try {
            URL.setURLStreamHandlerFactory { protocol ->
                if (protocol != "https") null else object : URLStreamHandler() {
                    override fun openConnection(url: URL) = object : HttpURLConnection(url) {
                        private val body: String by lazy { response(url, requestMethod) }
                        override fun connect() {}
                        override fun disconnect() {}
                        override fun usingProxy() = false
                        override fun getResponseCode(): Int { body; return if (requestMethod == "DELETE") 204 else 200 }
                        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
                    }
                }
            }
            test("Unrelated JSON cannot replace schedules") {
                repo.update { original.copy(schedules = listOf(ScheduleItem(title = "Keep", period = day.toString())), googleCalendar = GoogleCalendarLink()) }
                val before = repo.exportJson()
                for (input in listOf("{}", "{\"unrelated\":1}", "{\"Schedules\":null}", "{\"Schedules\":{}}")) {
                    check(repo.importDesktopJson(input).isFailure) { "Accepted unrelated JSON: $input" }
                    check(repo.exportJson() == before) { "Rejected import changed the data" }
                }
                check(repo.importDesktopJson("\uFEFF{\"Schedules\":[]}").isSuccess) { "Valid empty/BOM desktop file rejected" }
            }
            for (inWindow in listOf(true, false)) test(if (inWindow) "Retry failed Google DELETE" else "Retry failed Google lookup before DELETE") {
                listed = inWindow
                offline = true
                deletes = 0
                repo.update {
                    it.copy(schedules = emptyList(), googleCalendar = GoogleCalendarLink(
                        enabled = true, account = account, syncedEventIds = listOf(eventId), ownedEventIds = listOf(eventId),
                    ))
                }
                val failed = runCatching { runBlocking { GoogleCalendarSync.sync(targetContext, "fake-token") } }
                check(failed.isFailure) { "Expected a transient network failure" }
                val pending = repo.data.value.googleCalendar
                check(eventId in pending.syncedEventIds && eventId in pending.ownedEventIds) { "Failed deletion lost its pending record" }
                check(repo.data.value.schedules.isEmpty()) { "Deleted schedule was resurrected" }
                offline = false
                val outcome = runBlocking { GoogleCalendarSync.sync(targetContext, "fake-token") }
                check(outcome.deleted == 1 && deletes == 1) { "Next sync did not retry the deletion" }
                check(eventId !in repo.data.value.googleCalendar.syncedEventIds) { "Successful deletion remained pending" }
                check(repo.data.value.schedules.isEmpty()) { "Deleted schedule was reimported" }
            }
            test("Unknown Google account cannot delete linked events") {
                repo.update {
                    it.copy(schedules = emptyList(), googleCalendar = GoogleCalendarLink(
                        enabled = true, account = account, syncedEventIds = listOf(eventId), ownedEventIds = listOf(eventId),
                    ))
                }
                deletes = 0
                identityUnavailable = true
                try {
                    check(runCatching { runBlocking { GoogleCalendarSync.sync(targetContext, "fake-token") } }.isFailure)
                    check(deletes == 0) { "Unknown account performed a remote deletion" }
                    check(eventId in repo.data.value.googleCalendar.ownedEventIds) { "Unknown account discarded pending links" }
                } finally { identityUnavailable = false }
            }
            test("Note saves survive write/erase failures and queued undo") {
                runBlocking {
                    val files = File(targetContext.cacheDir, "note-save-regression").apply { mkdirs() }
                    val context = object : ContextWrapper(targetContext) {
                        override fun getFilesDir() = files
                        override fun getApplicationContext() = this
                    }
                    val meta = NoteStore.createBlank(context, "Save regression", "plain")
                    val note = checkNotNull(NoteStore.load(context, meta.id))
                    val uid = note.pages.first().uid
                    val inkDir = File(files, "notes/" + meta.id + "/ink").apply { mkdirs() }
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
                    val state = withContext(Dispatchers.Main) { EditorState(context, note, emptyMap(), 1f, scope) }
                    try {
                        Os.chmod(inkDir.path, 0x16D) // 0555: simulate a write failure without filling the device.
                        val write = withContext(Dispatchers.Main) {
                            state.applyText(TextEdit(uid, null, 10f, 10f), "A", 16f)
                            state.saveNow(reportError = false)
                        }
                        check(runCatching { write?.await() }.isFailure) { "Write failure was reported as success" }
                        Os.chmod(inkDir.path, 0x1C0) // 0700
                        withContext(Dispatchers.Main) { state.saveNow(reportError = false) }?.await()
                        check(NoteStore.loadInk(context, meta.id, uid).texts.single().text == "A") { "Failed save could not be retried" }

                        Os.chmod(inkDir.path, 0x16D)
                        val erase = withContext(Dispatchers.Main) {
                            state.applyText(TextEdit(uid, state.inkOf(uid).texts.single(), 10f, 10f), "", 16f)
                            state.saveNow(reportError = false)
                        }
                        check(runCatching { erase?.await() }.isFailure) { "Failed ink deletion was reported as success" }
                        check(NoteStore.loadInk(context, meta.id, uid).texts.single().text == "A") { "Failed erase lost the saved ink" }
                        Os.chmod(inkDir.path, 0x1C0)
                        withContext(Dispatchers.Main) { state.saveNow(reportError = false) }?.await()
                        check(NoteStore.loadInk(context, meta.id, uid).isEmpty) { "Failed erase could not be retried" }

                        NoteStore.writeMutex.lock()
                        val queued = try {
                            withContext(Dispatchers.Main) {
                                state.applyText(TextEdit(uid, null, 10f, 10f), "A", 16f)
                                val first = state.saveNow(reportError = false)
                                state.applyText(TextEdit(uid, state.inkOf(uid).texts.single(), 10f, 10f), "B", 16f)
                                val second = state.saveNow(reportError = false)
                                state.undo() // A again while both writes are still queued.
                                first to second
                            }
                        } finally { NoteStore.writeMutex.unlock() }
                        queued.first?.await()
                        queued.second?.await()
                        withContext(Dispatchers.Main) { state.saveNow(reportError = false) }?.await()
                        check(NoteStore.loadInk(context, meta.id, uid).texts.single().text == "A") { "Older queued save overwrote the undo" }

                        val inkFile = File(inkDir, "$uid.json")
                        val goodInk = inkFile.readText()
                        try {
                            inkFile.writeText("{")
                            check(runCatching { NoteStore.loadInk(context, meta.id, uid) }.isFailure) { "Corrupt ink was treated as empty" }
                            check(inkFile.readText() == "{") { "Reading corrupt ink changed the original file" }
                        } finally { inkFile.writeText(goodInk) }

                        NoteStore.writeMutex.lock()
                        val pending = try {
                            val save = withContext(Dispatchers.Main) {
                                state.applyText(TextEdit(uid, state.inkOf(uid).texts.single(), 10f, 10f), "C", 16f)
                                state.saveNow(reportError = false)
                            }
                            val deletion = async(Dispatchers.IO) { NoteStore.delete(context, meta.id) }
                            delay(50)
                            check(!deletion.isCompleted) { "Deletion raced ahead of the pending save" }
                            save to deletion
                        } finally { NoteStore.writeMutex.unlock() }
                        pending.first?.await()
                        pending.second.await()
                        check(!File(files, "notes/" + meta.id).exists()) { "Save resurrected the deleted notebook" }
                    } finally {
                        scope.cancel()
                        if (inkDir.exists()) Os.chmod(inkDir.path, 0x1C0)
                        NoteStore.delete(context, meta.id)
                        files.deleteRecursively()
                    }
                }
            }
        } catch (e: Throwable) {
            failures++
            results += e.stackTraceToString()
        } finally {
            repo.importDesktopJson(originalJson).getOrThrow()
            repo.update { original }
        }
        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", "\n" + results.joinToString("\n") + "\nFailures: $failures\n")
        })
    }

    private fun response(url: URL, method: String): String = when {
        url.path.endsWith("/calendars/primary") -> {
            if (identityUnavailable) throw IOException("Simulated calendar identity failure")
            "{\"id\":\"$account\"}"
        }
        url.path.endsWith("/events") -> if (listed) "{\"items\":[$event]}" else "{\"items\":[]}"
        url.path.endsWith("/events/$eventId") -> {
            if (offline) throw IOException("Simulated network interruption")
            if (method == "DELETE") { deletes++; "" } else event
        }
        else -> throw IOException("Unexpected URL in test: $url")
    }
}
