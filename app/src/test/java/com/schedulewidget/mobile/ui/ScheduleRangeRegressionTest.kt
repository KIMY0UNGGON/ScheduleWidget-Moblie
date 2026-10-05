package com.schedulewidget.mobile.ui

import com.schedulewidget.mobile.data.AppData
import com.schedulewidget.mobile.data.JsonMerge
import com.schedulewidget.mobile.data.ScheduleDates
import com.schedulewidget.mobile.data.ScheduleItem
import com.schedulewidget.mobile.pet.addPet
import com.schedulewidget.mobile.pet.changePet
import com.schedulewidget.mobile.pet.petSlots
import com.schedulewidget.mobile.pet.removePet
import com.schedulewidget.mobile.pet.withPet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ScheduleRangeRegressionTest {
    private val start = LocalDate.of(2026, 12, 30)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Test fun desktopJsonRoundTripKeepsRangeAndCalendarMetadata() {
        val raw = json.parseToJsonElement("""
            { "WindowState": { "Width": 500 }, "Schedules": [{
              "Id":"trip", "Title":"출장", "Period":"2026-12-30", "EndPeriod":"2027-01-02",
              "GoogleEventId":"google-event", "GoogleSyncedHash":"known", "GoogleSyncedPeriod":"2026-12-30",
              "GoogleEndMinutes":4320, "ReminderReceipts":{"Desktop":"sent"}, "DesktopCode":"keep",
              "MobileEventId":71, "MobileMemo":"호텔", "MobileImportant":true, "MobileSeriesId":"series"
            }] }
        """.trimIndent()).jsonObject
        val loaded = json.decodeFromJsonElement(AppData.serializer(), raw)
        val edited = loaded.copy(schedules = loaded.schedules.map { it.copy(title = "출장 수정") })
        val saved = JsonMerge.merge(
            json.encodeToJsonElement(AppData.serializer(), edited), raw, AppData.serializer().descriptor,
        ).jsonObject
        val schedule = saved["Schedules"]!!.jsonArray.single().jsonObject
        val decoded = json.decodeFromJsonElement(AppData.serializer(), saved).schedules.single()

        assertEquals("2027-01-02", schedule["EndPeriod"]!!.jsonPrimitive.content)
        assertEquals("keep", schedule["DesktopCode"]!!.jsonPrimitive.content)
        assertEquals("sent", schedule["ReminderReceipts"]!!.jsonObject["Desktop"]!!.jsonPrimitive.content)
        assertEquals("google-event", decoded.googleEventId)
        assertEquals("known", decoded.googleSyncedHash)
        assertEquals(4320, decoded.googleEndMinutes)
        assertEquals(71L, decoded.eventId)
        assertEquals("호텔", decoded.memo)
        assertTrue(decoded.important)
        assertEquals("series", decoded.seriesId)
        assertEquals("출장 수정", decoded.title)

        val legacy = json.decodeFromString(ScheduleItem.serializer(), """{"Id":"legacy","Period":"2026-12-30"}""")
        assertFalse(legacy.isMultiDay)
        assertFalse(json.encodeToString(ScheduleItem.serializer(), legacy).contains("EndPeriod"))
    }

    @Test fun inclusiveRangeNormalizesCoversOverlapsAndCountsDdayFromItsEdges() {
        val range = ScheduleItem(title = "출장", period = start.toString(), endPeriod = "2027-01-02")

        assertTrue(range.isMultiDay)
        assertEquals(LocalDate.of(2027, 1, 2), range.lastDate)
        assertEquals("2026-12-30 ~ 2027-01-02", range.periodText)
        assertEquals("12.30~1.2", range.rangeLabel)
        assertFalse(range.covers(start.minusDays(1)))
        assertTrue(range.covers(start))
        assertTrue(range.covers(LocalDate.of(2027, 1, 1)))
        assertTrue(range.covers(range.lastDate!!))
        assertFalse(range.covers(range.lastDate!!.plusDays(1)))
        assertTrue(range.overlaps(LocalDate.of(2027, 1, 2), LocalDate.of(2027, 1, 5)))
        assertFalse(range.overlaps(LocalDate.of(2027, 1, 3), LocalDate.of(2027, 1, 5)))
        assertEquals("D-2", range.dDay(start.minusDays(2)))
        assertEquals("D-day", range.dDay(range.lastDate!!))
        assertEquals("D+1", range.dDay(range.lastDate!!.plusDays(1)))
        assertEquals("완료", range.copy(isCompleted = true).dDay(start))

        assertNull(ScheduleItem.normalizeEndPeriod(start.toString(), start.toString()))
        assertNull(ScheduleItem.normalizeEndPeriod(start.toString(), start.minusDays(1).toString()))
        assertEquals(start.plusDays(ScheduleItem.MAX_RANGE_DAYS.toLong()).toString(),
            ScheduleItem.normalizeEndPeriod(start.toString(), start.plusDays(ScheduleItem.MAX_RANGE_DAYS + 10L).toString()))
    }

    @Test fun movingAndEditingRangePreservesExistingCalendarLinksAndMobileFields() {
        val linked = ScheduleItem(
            id = "trip", title = "출장", period = start.toString(), endPeriod = "2027-01-02", time = "09:00",
            color = "#FF141414",
            eventId = 71L, googleEventId = "google-event", googleSyncedHash = "known", googleSyncedPeriod = start.toString(),
            googleEndMinutes = 4320, googleRefusedHash = "refused", memo = "호텔", important = true, seriesId = "series",
        )
        val movedStart = LocalDate.of(2027, 1, 9)
        val moved = linked.movedTo(movedStart)
        assertEquals(movedStart.plusDays(3), moved.endDate)
        assertEquals(linked.id, moved.id)
        assertEquals(linked.googleEventId, moved.googleEventId)
        assertEquals(linked.googleSyncedHash, moved.googleSyncedHash)
        assertEquals(linked.googleSyncedPeriod, moved.googleSyncedPeriod)
        assertEquals(linked.googleEndMinutes, moved.googleEndMinutes)
        assertEquals(linked.googleRefusedHash, moved.googleRefusedHash)
        assertEquals(linked.eventId, moved.eventId)
        assertEquals(linked.color, moved.color)
        assertEquals(linked.memo, moved.memo)
        assertEquals(linked.seriesId, moved.seriesId)

        val form = ScheduleFormState.of(linked, start).apply {
            title = "출장 수정"
            date = movedStart
            until = movedStart.plusDays(4)
        }
        val edited = form.applyTo(linked)
        assertEquals(movedStart.toString(), edited.period)
        assertEquals(movedStart.plusDays(4), edited.endDate)
        assertEquals(linked.id, edited.id)
        assertEquals(linked.googleEventId, edited.googleEventId)
        assertEquals(linked.googleSyncedHash, edited.googleSyncedHash)
        assertEquals(linked.googleSyncedPeriod, edited.googleSyncedPeriod)
        assertEquals(linked.googleEndMinutes, edited.googleEndMinutes)
        assertEquals(linked.googleRefusedHash, edited.googleRefusedHash)
        assertEquals(linked.eventId, edited.eventId)
        assertEquals(linked.color, edited.color)
        assertEquals(linked.memo, edited.memo)
        assertEquals(linked.important, edited.important)
        assertEquals(linked.seriesId, edited.seriesId)
    }

    @Test fun continuousEntryCreatesOneRangeAndCalendarDaysAreClippedToTheVisibleWindow() {
        val form = ScheduleFormState.of(null, start).apply {
            title = "연수"
            repeat = Repeat.CONTINUOUS
            until = start.plusDays(2)
        }
        val created = form.build(quick = false).single()
        assertEquals(start.toString(), created.period)
        assertEquals(start.plusDays(2), created.endDate)
        assertNull(created.seriesId)

        val days = ScheduleDates.byDay(listOf(created), start.plusDays(1), start.plusDays(2))
        assertEquals(listOf(start.plusDays(1), start.plusDays(2)), days.keys.toList())
        assertEquals(listOf(created), days[start.plusDays(1)])
        assertEquals(listOf(created), days[start.plusDays(2)])
    }

    @Test fun upcomingShowsTwelveWithinTwentyEightDaysAndFallsBackToTheNearestLaterDay() {
        val from = LocalDate.of(2027, 2, 1)
        val within = (0..12).map { ScheduleItem(id = "near-$it", title = "near-$it", period = from.plusDays(it.toLong()).toString()) }
        val later = ScheduleItem(id = "later", title = "later", period = from.plusDays(28).toString())
        val completed = ScheduleItem(id = "done", title = "done", period = from.toString(), isCompleted = true)

        val compact = ScheduleDates.upcoming(within + later + completed, from)
        assertEquals(within.take(12).map { it.id }, compact.items.map { it.id })
        assertEquals(2, compact.more)
        val expanded = ScheduleDates.upcoming(within + later + completed, from, expanded = true)
        assertEquals(14, expanded.items.size)
        assertEquals(0, expanded.more)
        assertFalse(expanded.items.any { it.id == completed.id })

        val nearest = listOf(
            ScheduleItem(id = "nearest-a", period = from.plusDays(28).toString()),
            ScheduleItem(id = "nearest-b", period = from.plusDays(28).toString()),
            ScheduleItem(id = "farther", period = from.plusDays(35).toString()),
        )
        val fallback = ScheduleDates.upcoming(nearest, from)
        assertEquals(listOf("nearest-a", "nearest-b"), fallback.items.map { it.id })
        assertEquals(1, fallback.more)
    }

    @Test fun duplicatePetPositionMemoryFollowsRosterChangesAndStopsAtThreeSlots() {
        val fox = "pet:fox"
        var data = AppData(characterManifest = "builtin:codex", petX = 0.1f, petY = 0.2f)
            .addPet(fox)
        data = data.withPet(data.petSlots()[1].copy(x = 0.25f, y = 0.35f))
            .addPet(fox)
        data = data.withPet(data.petSlots()[2].copy(x = 0.85f, y = 0.95f, scale = 135, hidden = true, flipped = true))

        assertEquals(3, data.petSlots().size)
        assertEquals(data, data.addPet("pet:fourth"))

        val removedFirst = data.removePet(1)
        assertEquals(0.85f, removedFirst.petSlots()[1].x!!, 0f)
        val readded = removedFirst.addPet(fox)
        assertEquals(0.25f, readded.petSlots()[2].x!!, 0f)

        val configured = readded.withPet(readded.petSlots()[2].copy(scale = 135, hidden = true, flipped = true))
        val changed = configured.changePet(2, "pet:other")
        assertEquals(135, changed.petSlots()[2].scale)
        assertTrue(changed.petSlots()[2].hidden)
        assertTrue(changed.petSlots()[2].flipped)
        assertEquals("idle", changed.petSlots()[2].animation)
        val restored = changed.changePet(2, fox)
        assertEquals(0.25f, restored.petSlots()[2].x!!, 0f)
    }
}
