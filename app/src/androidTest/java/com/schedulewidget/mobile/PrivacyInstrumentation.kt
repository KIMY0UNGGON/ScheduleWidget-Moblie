package com.schedulewidget.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import com.schedulewidget.mobile.data.Repository
import com.schedulewidget.mobile.notes.NoteStore
import com.schedulewidget.mobile.notes.editor.PageImport
import com.schedulewidget.mobile.privacy.GoogleDocumentConsent
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.xmlpull.v1.XmlPullParser
import java.io.File

/** Conversion cannot inherit pet consent, and OFFLINE rejects legacy Office before Google authorization. */
class PrivacyInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        var failure: Throwable? = null
        val scratch = File(targetContext.cacheDir, "privacy-test-${System.nanoTime()}").apply { mkdirs() }
        val prefsName = "privacy-test-${System.nanoTime()}"
        val context = object : ContextWrapper(targetContext) {
            override fun getApplicationContext() = this
            override fun getFilesDir() = File(scratch, "files").apply { mkdirs() }
            override fun getCacheDir() = File(scratch, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                targetContext.getSharedPreferences("$prefsName-$name", mode)
        }
        val repo = Repository.get(targetContext)
        val originalNotes = repo.data.value.notes
        try {
            runBlocking {
                check(!GoogleDocumentConsent.isAllowed(context))
                val denied = async { GoogleDocumentConsent.awaitPermission(context, "selected document") }
                val request = withTimeout(2000) { GoogleDocumentConsent.pending.first { it != null }!! }
                check(!denied.isCompleted) { "Upload started before an explicit answer" }
                GoogleDocumentConsent.respond(context, request, false)
                check(!denied.await())
                check(!GoogleDocumentConsent.isAllowed(context))

                val allowed = async { GoogleDocumentConsent.awaitPermission(context, "selected document") }
                val approved = withTimeout(2000) { GoogleDocumentConsent.pending.first { it != null }!! }
                GoogleDocumentConsent.respond(context, approved, true)
                check(allowed.await())
                check(withTimeout(2000) { GoogleDocumentConsent.awaitPermission(context, "next document") })
                check(GoogleDocumentConsent.pending.value == null)
                GoogleDocumentConsent.revoke(context)
                val cancelled = async { GoogleDocumentConsent.awaitPermission(context, "cancelled document") }
                withTimeout(2000) { GoogleDocumentConsent.pending.first { it != null } }
                cancelled.cancelAndJoin()
                check(GoogleDocumentConsent.pending.value == null) { "Cancelled import left an upload prompt" }

                repo.update { it.copy(notes = it.notes.copy(convert = "offline")) }
                val note = NoteStore.createBlank(context, "Offline conversion", "plain")
                val input = File(scratch, "old-format.ppt").apply {
                    writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0, 0, 0, 0))
                }
                val result = runCatching {
                    PageImport.prepare(context, note.id, Uri.fromFile(input)) {}
                }
                check(result.isFailure && result.exceptionOrNull()?.message?.contains("예전 형식") == true) {
                    "OFFLINE did not reject a legacy document before authorization: $result"
                }
                check(GoogleDocumentConsent.pending.value == null)
                NoteStore.delete(context, note.id)
            }
            checkBackupRules(R.xml.backup_rules, setOf("full-backup-content"))
            checkBackupRules(R.xml.data_extraction_rules, setOf("cloud-backup", "device-transfer"))
        } catch (e: Throwable) {
            failure = e
        } finally {
            repo.update { it.copy(notes = originalNotes) }
            targetContext.deleteSharedPreferences("$prefsName-privacy_choices")
            scratch.deleteRecursively()
        }
        finish(
            if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", failure?.stackTraceToString() ?: "PASS: document consent, cancellation, offline legacy rejection, token backup exclusions\n") },
        )
    }

    private fun checkBackupRules(resource: Int, expectedGroups: Set<String>) {
        val exclusions = expectedGroups.associateWith { mutableSetOf<String>() }
        var group: String? = null
        targetContext.resources.getXml(resource).use { xml ->
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG) {
                    if (xml.name in expectedGroups) group = xml.name
                    if (xml.name == "exclude" && xml.getAttributeValue(null, "domain") == "sharedpref")
                        group?.let { exclusions.getValue(it) += xml.getAttributeValue(null, "path") }
                } else if (xml.eventType == XmlPullParser.END_TAG && xml.name == group) group = null
                xml.next()
            }
        }
        exclusions.forEach { (name, paths) ->
            check(paths.containsAll(setOf("youtube_login.xml", "privacy_choices.xml"))) { "$name can back up login or upload consent" }
        }
    }
}
