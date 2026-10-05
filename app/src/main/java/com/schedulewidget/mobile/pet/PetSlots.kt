package com.schedulewidget.mobile.pet

import com.schedulewidget.mobile.data.AppData
import com.schedulewidget.mobile.data.MiniCharacterSlot
import com.schedulewidget.mobile.data.PetPosition

/** One pet in the mini calendar and floating overlay. Index zero is the legacy primary pet. */
data class PetSlot(
    val index: Int,
    val manifest: String,
    val animation: String,
    val scale: Int,
    val hidden: Boolean,
    val flipped: Boolean,
    val x: Float?,
    val y: Float?,
    val floatingX: Int?,
    val floatingY: Int?,
)

private fun positionKey(manifest: String, occurrence: Int) = "$manifest#$occurrence"

private fun List<PetSlot>.positionKey(index: Int): String {
    val slot = get(index)
    return positionKey(slot.manifest, take(index).count { it.manifest == slot.manifest })
}

private fun PetSlot.position() = PetPosition(x, y, floatingX, floatingY)

private fun Map<String, PetPosition>.history(manifest: String, activeCount: Int): List<PetPosition> {
    val prefix = "$manifest#"
    return entries.mapNotNull { (key, position) ->
        key.removePrefix(prefix).takeIf { key.startsWith(prefix) }?.toIntOrNull()?.let { it to position }
    }.filter { it.first >= activeCount }.sortedBy { it.first }.map { it.second }
}

private fun snapshotPositions(positions: Map<String, PetPosition>, roster: List<PetSlot>): Map<String, PetPosition> =
    roster.indices.fold(positions) { saved, index -> saved + (roster.positionKey(index) to roster[index].position()) }

private fun Map<String, PetPosition>.withManifestPositions(
    manifest: String,
    roster: List<PetSlot>,
    history: List<PetPosition>,
): Map<String, PetPosition> {
    val prefix = "$manifest#"
    val saved = filterKeys { !it.startsWith(prefix) }.toMutableMap()
    var occurrence = 0
    roster.filter { it.manifest == manifest }.forEach { slot ->
        saved[positionKey(manifest, occurrence++)] = slot.position()
    }
    history.forEachIndexed { index, position -> saved[positionKey(manifest, occurrence + index)] = position }
    return saved
}

/** Reads the legacy primary fields and at most two extra pets into a uniform roster. */
fun AppData.petSlots(): List<PetSlot> {
    val raw = buildList {
        add(
            PetSlot(
                index = 0,
                manifest = characterManifest.ifBlank { Characters.DEFAULT },
                animation = characterAnimation.ifBlank { "idle" },
                scale = miniCharacterScale,
                hidden = miniFirstPetHidden,
                flipped = miniFirstPetFlipped,
                x = petX,
                y = petY,
                floatingX = floatingX,
                floatingY = floatingY,
            ),
        )
        miniExtraCharacters.take(2).forEach { extra ->
            add(
                PetSlot(
                    index = size,
                    manifest = extra.manifest.ifBlank { Characters.DEFAULT },
                    animation = extra.animation.ifBlank { "idle" },
                    scale = extra.scale ?: miniCharacterScale,
                    hidden = extra.hidden,
                    flipped = extra.flipped,
                    x = extra.x,
                    y = extra.y,
                    floatingX = extra.floatingX,
                    floatingY = extra.floatingY,
                ),
            )
        }
    }
    return raw.map { slot ->
        val remembered = petPositions[positionKey(slot.manifest, raw.take(slot.index).count { it.manifest == slot.manifest })]
        slot.copy(
            x = slot.x ?: remembered?.x,
            y = slot.y ?: remembered?.y,
            floatingX = remembered?.floatingX ?: slot.floatingX,
            floatingY = remembered?.floatingY ?: slot.floatingY,
        )
    }
}

private fun AppData.writeSlots(slots: List<PetSlot>, positions: Map<String, PetPosition>): AppData {
    val roster = slots.take(3)
    val first = roster.firstOrNull() ?: return this
    return copy(
        characterManifest = first.manifest,
        characterAnimation = first.animation,
        miniCharacterScale = first.scale,
        miniFirstPetHidden = first.hidden,
        miniFirstPetFlipped = first.flipped,
        petX = first.x,
        petY = first.y,
        floatingX = first.floatingX ?: 60,
        floatingY = first.floatingY ?: 600,
        miniExtraCharacters = roster.drop(1).map { slot ->
            MiniCharacterSlot(
                manifest = slot.manifest,
                animation = slot.animation,
                scale = slot.scale,
                hidden = slot.hidden,
                flipped = slot.flipped,
                x = slot.x,
                y = slot.y,
                floatingX = slot.floatingX,
                floatingY = slot.floatingY,
            )
        },
        petPositions = positions,
    )
}

/** Updates a pet's settings or live position without changing the roster. */
fun AppData.withPet(slot: PetSlot): AppData {
    val roster = petSlots().toMutableList()
    if (slot.index !in roster.indices || roster[slot.index].manifest != slot.manifest) return this
    roster[slot.index] = slot
    val positions = petPositions + (roster.positionKey(slot.index) to slot.position())
    return writeSlots(roster, positions)
}

/** Changes one pet, preserving its settings and restoring that character's remembered position when available. */
fun AppData.changePet(index: Int, manifest: String): AppData {
    val roster = petSlots().toMutableList()
    val current = roster.getOrNull(index) ?: return this
    if (current.manifest == manifest) return this

    val oldManifest = current.manifest
    val saved = snapshotPositions(petPositions, roster)
    val oldHistory = saved.history(oldManifest, roster.count { it.manifest == oldManifest })
    val newHistory = saved.history(manifest, roster.count { it.manifest == manifest })
    val oldPosition = saved[roster.positionKey(index)] ?: current.position()
    val restored = newHistory.firstOrNull()
    roster[index] = current.copy(manifest = manifest, animation = "idle", x = null, y = null, floatingX = null, floatingY = null)
    roster[index] = roster[index].copy(
        x = restored?.x,
        y = restored?.y,
        floatingX = restored?.floatingX,
        floatingY = restored?.floatingY,
    )
    val positions = saved
        .withManifestPositions(oldManifest, roster, listOf(oldPosition) + oldHistory)
        .withManifestPositions(manifest, roster, newHistory.drop(if (restored == null) 0 else 1))
    return writeSlots(roster, positions)
}

/** Adds another pet if the three-slot limit allows it; duplicates are supported. */
fun AppData.addPet(manifest: String): AppData {
    val roster = petSlots().toMutableList()
    if (roster.size >= 3) return this
    val saved = snapshotPositions(petPositions, roster)
    val oldCount = roster.count { it.manifest == manifest }
    val history = saved.history(manifest, oldCount)
    val position = history.firstOrNull()
    val slot = PetSlot(roster.size, manifest, "idle", miniCharacterScale, false, false, position?.x, position?.y, position?.floatingX, position?.floatingY)
    roster += slot
    val positions = saved.withManifestPositions(manifest, roster, history.drop(if (position == null) 0 else 1))
    return writeSlots(roster, positions)
}

/** Removes one pet and remembers its position so the same character occurrence can be restored later. */
fun AppData.removePet(index: Int): AppData {
    val roster = petSlots().toMutableList()
    if (roster.size <= 1 || index !in roster.indices) return this
    val removed = roster[index]
    val saved = snapshotPositions(petPositions, roster)
    val history = saved.history(removed.manifest, roster.count { it.manifest == removed.manifest })
    roster.removeAt(index)
    val positions = saved.withManifestPositions(removed.manifest, roster, listOf(removed.position()) + history)
    return writeSlots(roster, positions)
}
