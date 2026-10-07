package com.schedulewidget.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Bundle
import com.schedulewidget.mobile.data.ScheduleItem
import com.schedulewidget.mobile.pet.Characters
import com.schedulewidget.mobile.pet.PetImport
import com.schedulewidget.mobile.calendar.GoogleCalendarSync
import com.schedulewidget.mobile.calendar.GoogleCalendarApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Network-free Android checks for APIs that require Android's real assets and JSON implementation. */
class ParityInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val results = mutableListOf<String>()
        var failures = 0
        fun test(name: String, body: () -> Unit) {
            try {
                body()
                results += "PASS: $name"
            } catch (e: Throwable) {
                failures++
                results += "FAIL: $name: ${e.stackTraceToString()}"
            }
        }

        test("v4 character assets retain keyboard animation rows") {
            val character = Characters.find(targetContext, "builtin:mochi-white")
                ?: error("v4 character asset was not catalogued")
            check(character.rows == 15) { "expected 15 rows, got ${character.rows}" }
            check(character.extra["keyboard1"]?.row == 13) { "keyboard1 row was not read" }
            check(character.extra["keyboard2"]?.row == 14) { "keyboard2 row was not read" }
        }

        test("character image manifests accept only SAF content URIs") {
            check(Characters.userUri("uri:file:///data/data/example/image.png") == null)
            check(Characters.userUri("uri:https://example.test/image.png") == null)
            check(Characters.userUri("uri:content://com.example.provider/image/1") != null)
        }

        test("v5 zip import accepts a 17-row sheet and preserves keyboard rows") {
            val id = "audit-v5-${System.nanoTime()}"
            val png = ByteArrayOutputStream().use { output ->
                val bitmap = Bitmap.createBitmap(1536, 3536, Bitmap.Config.ARGB_8888)
                try {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                } finally {
                    bitmap.recycle()
                }
                output.toByteArray()
            }
            val manifest = """
                {"displayName":"v5 fixture","spritesheetPath":"spritesheet.png","spriteVersionNumber":5,
                 "animations":{"keyboard1":{"row":15,"frames":8,"duration":120},
                               "keyboard2":{"row":16,"frames":8,"duration":110}}}
            """.trimIndent().toByteArray(Charsets.UTF_8)
            val zip = ByteArrayOutputStream().use { output ->
                ZipOutputStream(output).use { archive ->
                    archive.putNextEntry(ZipEntry("pet.json")); archive.write(manifest); archive.closeEntry()
                    archive.putNextEntry(ZipEntry("spritesheet.png")); archive.write(png); archive.closeEntry()
                }
                output.toByteArray()
            }
            val scratchRoot = File(targetContext.cacheDir, "parity-v5-${System.nanoTime()}")
            val scratchFiles = File(scratchRoot, "files").apply { mkdirs() }
            val scratchCache = File(scratchRoot, "cache").apply { mkdirs() }
            val scratch = object : ContextWrapper(targetContext) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = scratchFiles
                override fun getCacheDir(): File = scratchCache
            }

            try {
                val result = PetImport.fromZipBytes(scratch, zip, id)
                if (result !is PetImport.Result.Imported) {
                    val reason = (result as? PetImport.Result.Failed)?.message ?: result.toString()
                    error("v5 sprite sheet import failed: $reason")
                }
                val imported = result
                val character = Characters.find(scratch, imported.manifest) ?: error("imported character was not catalogued")
                check(character.rows == 17) { "expected 17 rows, got ${character.rows}" }
                check(character.extra["keyboard1"]?.row == 15) { "keyboard1 row was not retained" }
                check(character.extra["keyboard2"]?.row == 16) { "keyboard2 row was not retained" }
                val saved = Json.parseToJsonElement(PetImport.dir(scratch).resolve("$id/pet.json").readText()).jsonObject
                check(saved["spriteVersionNumber"]?.jsonPrimitive?.int == 5) { "v5 format was not written" }
            } finally {
                PetImport.delete(scratch, "pet:$id")
                Characters.invalidate()
                scratchRoot.deleteRecursively()
            }
        }

        test("Google all-day range insert, parse and end-date patch round-trip") {
            val item = ScheduleItem(
                id = "range", title = "출장", period = "2026-12-30", endPeriod = "2027-01-02",
            )
            val inserted = callPrivate(
                "insertBody", arrayOf(ScheduleItem::class.java, String::class.java), item, "auditrange1234567890",
            ) as JSONObject
            check(inserted.getJSONObject("start").getString("date") == "2026-12-30")
            check(inserted.getJSONObject("end").getString("date") == "2027-01-03")

            val parsed = callPrivate("parse", arrayOf(JSONObject::class.java, ZoneId::class.java), inserted, ZoneId.of("UTC"))!!
            val parsedEnd = parsed.javaClass.getDeclaredField("endPeriod").apply { isAccessible = true }.get(parsed)
            check(parsedEnd == "2027-01-02") { "parsed end was $parsedEnd" }

            val knownHash = GoogleCalendarSync.hash(item.title, item.period, item.time, item.isCompleted, item.endDate?.toString())
            val changed = item.copy(endPeriod = "2027-01-03", googleSyncedHash = knownHash)
            val patch = callPrivate(
                "patchBody", arrayOf(ScheduleItem::class.java, Boolean::class.javaPrimitiveType!!), changed, true,
            ) as JSONObject
            check(patch.getJSONObject("start").getString("date") == "2026-12-30")
            check(patch.getJSONObject("end").getString("date") == "2027-01-04")
        }

        test("timed range insert ends on the inclusive last day at the same clock") {
            val item = ScheduleItem(title = "연수", period = "2026-10-03", endPeriod = "2026-10-05", time = "09:30")
            val body = callPrivate(
                "insertBody", arrayOf(ScheduleItem::class.java, String::class.java), item, "audittimedrange12345",
            ) as JSONObject
            check(localDateTime(body, "start") == LocalDateTime.parse("2026-10-03T09:30"))
            check(localDateTime(body, "end") == LocalDateTime.parse("2026-10-05T09:30"))
        }

        test("timed event ending at midnight parses as an inclusive previous day") {
            val event = JSONObject()
                .put("id", "audittimedrange12345")
                .put("summary", "연수")
                .put("start", JSONObject().put("dateTime", "2026-10-03T09:30:00Z"))
                .put("end", JSONObject().put("dateTime", "2026-10-06T00:00:00Z"))
            val parsed = callPrivate("parse", arrayOf(JSONObject::class.java, ZoneId::class.java), event, ZoneId.of("UTC"))!!
            val field = parsed.javaClass.getDeclaredField("endPeriod").apply { isAccessible = true }
            check(field.get(parsed) == "2026-10-05") { "midnight end parsed as ${field.get(parsed)}" }
        }

        test("moving a timed range preserves its clock duration and title-only patch omits times") {
            val original = ScheduleItem(
                title = "연수", period = "2026-10-03", endPeriod = "2026-10-05", time = "09:30", googleEndMinutes = 90,
            )
            val syncedHash = GoogleCalendarSync.hash(
                original.title, original.period, original.time, original.isCompleted, original.endDate?.toString(),
            )
            val moved = original.copy(
                period = "2026-10-04", endPeriod = "2026-10-06", googleSyncedHash = syncedHash,
            )
            val movedBody = callPrivate(
                "patchBody", arrayOf(ScheduleItem::class.java, Boolean::class.javaPrimitiveType!!), moved, false,
            ) as JSONObject
            check(localDateTime(movedBody, "start") == LocalDateTime.parse("2026-10-04T09:30"))
            check(localDateTime(movedBody, "end") == LocalDateTime.parse("2026-10-06T11:00"))

            val renamed = original.copy(title = "연수 수정", googleSyncedHash = syncedHash)
            val titleBody = callPrivate(
                "patchBody", arrayOf(ScheduleItem::class.java, Boolean::class.javaPrimitiveType!!), renamed, false,
            ) as JSONObject
            check(titleBody.optString("summary") == "연수 수정")
            check(!titleBody.has("start") && !titleBody.has("end")) { "title-only patch contained time fields: $titleBody" }
        }

        test("removing a range resets a timed event to one hour and an all-day event to one day") {
            val timed = ScheduleItem(title = "연수", period = "2026-10-03", endPeriod = "2026-10-05", time = "09:30", googleEndMinutes = 90)
            val timedHash = GoogleCalendarSync.hash(timed.title, timed.period, timed.time, false, timed.endDate?.toString())
            val timedBody = callPrivate(
                "patchBody", arrayOf(ScheduleItem::class.java, Boolean::class.javaPrimitiveType!!),
                timed.copy(endPeriod = null, googleSyncedHash = timedHash), false,
            ) as JSONObject
            check(localDateTime(timedBody, "end") == LocalDateTime.parse("2026-10-03T10:30"))

            val allDay = timed.copy(time = null, googleEndMinutes = null)
            val allDayHash = GoogleCalendarSync.hash(allDay.title, allDay.period, null, false, allDay.endDate?.toString())
            val allDayBody = callPrivate(
                "patchBody", arrayOf(ScheduleItem::class.java, Boolean::class.javaPrimitiveType!!),
                allDay.copy(endPeriod = null, googleSyncedHash = allDayHash), false,
            ) as JSONObject
            check(allDayBody.getJSONObject("end").getString("date") == "2026-10-04")
        }

        finish(if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", "\n${results.joinToString("\n")}\nFailures: $failures\n")
        })
    }

    private fun callPrivate(name: String, types: Array<Class<*>>, vararg args: Any): Any? {
        val owner = if (name == "parse") GoogleCalendarApi else GoogleCalendarSync
        return owner.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(owner, *args)
    }

    private fun localDateTime(body: JSONObject, side: String): LocalDateTime =
        OffsetDateTime.parse(body.getJSONObject(side).getString("dateTime")).toLocalDateTime()
}
