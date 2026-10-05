package com.schedulewidget.mobile.data

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Keeps what the phone doesn't model when it writes schedules.json: the PC app's own keys (Appearance, WindowState,
 * GoogleCalendar, hotkeys, Music.VolumeBeforeMute, …) survive a save or export made on the phone.
 *
 * The encoded model is laid OVER the raw JSON last loaded or imported, guided by the model's descriptor:
 *  - class: keys the model declares come from the model (a null one is left out, like a plain encode); every other
 *    raw key is kept as it was. Raw key order is kept, new model keys follow.
 *  - list of classes with an identity key ("Id": schedules, playlists; else "Source": tracks): elements are merged
 *    pairwise by that key, in the model's order. Raw elements the model no longer has are dropped; model elements
 *    without a raw counterpart are written as they are.
 *  - maps, primitives and other lists: the model's value replaces the raw one (so removed map entries stay removed).
 */
internal object JsonMerge {
    private val identityKeys = listOf("Id", "Source")

    fun merge(model: JsonElement, raw: JsonElement?, descriptor: SerialDescriptor): JsonElement {
        if (raw == null) return model
        return when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT ->
                if (model is JsonObject && raw is JsonObject) mergeObject(model, raw, descriptor) else model
            StructureKind.LIST ->
                if (model is JsonArray && raw is JsonArray) mergeList(model, raw, descriptor.getElementDescriptor(0)) else model
            else -> model
        }
    }

    private fun mergeObject(model: JsonObject, raw: JsonObject, descriptor: SerialDescriptor): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, rawValue) in raw) {
            val index = descriptor.getElementIndex(key)
            if (index == CompositeDecoder.UNKNOWN_NAME) {
                out[key] = rawValue
                continue
            }
            // A modelled key missing from the model's encoding is null there: leave it out rather than keep a stale value.
            val value = model[key] ?: continue
            out[key] = merge(value, rawValue, descriptor.getElementDescriptor(index))
        }
        for ((key, value) in model) if (!raw.containsKey(key)) out[key] = value
        return JsonObject(out)
    }

    private fun mergeList(model: JsonArray, raw: JsonArray, element: SerialDescriptor): JsonArray {
        if (element.kind != StructureKind.CLASS) return model
        val idKey = identityKeys.firstOrNull { element.getElementIndex(it) != CompositeDecoder.UNKNOWN_NAME } ?: return model
        // Queues so duplicates (the same track added twice) pair up in order.
        val pool = HashMap<String, ArrayDeque<JsonObject>>()
        for (item in raw) {
            val obj = item as? JsonObject ?: continue
            val id = identity(obj, idKey) ?: continue
            pool.getOrPut(id) { ArrayDeque() }.addLast(obj)
        }
        return JsonArray(model.map { item ->
            val obj = item as? JsonObject ?: return@map item
            val match = identity(obj, idKey)?.let { pool[it]?.removeFirstOrNull() } ?: return@map item
            mergeObject(obj, match, element)
        })
    }

    // Guids are compared case-insensitively (.NET writes them lower-case, but a hand-edited file may not).
    private fun identity(obj: JsonObject, key: String): String? {
        val value = obj[key] as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        return value.content.lowercase()
    }
}
