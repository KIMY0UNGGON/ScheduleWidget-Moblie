package com.schedulewidget.mobile.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonMergeTest {
    // Same settings as the app (Repository.json): nulls are left out when encoding.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private val pcFile = """
        {
          "Appearance": { "ThemePreset": "Dark", "TitleFontSize": 16 },
          "StartupEnabled": true,
          "Schedules": [
            { "Id": "a", "Title": "PC title", "Period": "2026-10-01", "IsCompleted": false,
              "ReminderReceipts": { "Desktop": "2026-10-01|0|9:0" }, "PcOnly": 7 },
            { "Id": "b", "Title": "gone on phone", "Period": "2026-10-02", "IsCompleted": false }
          ]
        }
    """.trimIndent()

    private fun merged(model: AppData): JsonObject {
        val raw = json.parseToJsonElement(pcFile)
        val encoded = json.encodeToJsonElement(AppData.serializer(), model)
        return JsonMerge.merge(encoded, raw, AppData.serializer().descriptor).jsonObject
    }

    @Test
    fun keepsPcOnlyKeysAndUsesModelValues() {
        val phone = json.decodeFromString(AppData.serializer(), pcFile)
        val edited = phone.copy(schedules = phone.schedules.filter { it.id == "a" }.map { it.copy(title = "phone title") })
        val out = merged(edited)

        // Top-level PC keys survive.
        assertEquals("Dark", out["Appearance"]!!.jsonObject["ThemePreset"]!!.jsonPrimitive.content)
        assertEquals("true", out["StartupEnabled"]!!.jsonPrimitive.content)

        val schedules = out["Schedules"]!!.jsonArray
        // "b" was deleted on the phone and stays deleted.
        assertEquals(1, schedules.size)
        val a = schedules[0].jsonObject
        // The phone's edit wins, the PC-only key and the reminder receipts stay.
        assertEquals("phone title", a["Title"]!!.jsonPrimitive.content)
        assertEquals("7", a["PcOnly"]!!.jsonPrimitive.content)
        assertTrue(a["ReminderReceipts"]!!.jsonObject.containsKey("Desktop"))
    }

    @Test
    fun clearedValueDoesNotComeBack() {
        val raw = """{ "Schedules": [ { "Id": "a", "Title": "t", "Period": "2026-10-01", "Time": "09:00" } ] }"""
        val phone = json.decodeFromString(AppData.serializer(), raw)
        val cleared = phone.copy(schedules = phone.schedules.map { it.copy(time = null) })
        val out = JsonMerge.merge(
            json.encodeToJsonElement(AppData.serializer(), cleared),
            json.parseToJsonElement(raw),
            AppData.serializer().descriptor,
        ).jsonObject
        assertFalse(out["Schedules"]!!.jsonArray[0].jsonObject.containsKey("Time"))
    }
}
