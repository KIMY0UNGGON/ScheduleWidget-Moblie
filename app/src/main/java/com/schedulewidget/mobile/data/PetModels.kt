package com.schedulewidget.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MiniCharacterSlot(
    @SerialName("Manifest") val manifest: String = "builtin:mochi-white",
    @SerialName("Animation") val animation: String = "idle",
    @SerialName("Scale") val scale: Int? = null,
    @SerialName("Hidden") val hidden: Boolean = false,
    @SerialName("Flipped") val flipped: Boolean = false,
    @SerialName("MobileX") val x: Float? = null,
    @SerialName("MobileY") val y: Float? = null,
    @SerialName("MobileFloatingX") val floatingX: Int? = null,
    @SerialName("MobileFloatingY") val floatingY: Int? = null,
)

@Serializable
data class PetPosition(
    @SerialName("X") val x: Float? = null,
    @SerialName("Y") val y: Float? = null,
    @SerialName("FloatingX") val floatingX: Int? = null,
    @SerialName("FloatingY") val floatingY: Int? = null,
)

@Serializable
data class PetDriveSync(
    @SerialName("Enabled") val enabled: Boolean = false,
    // Opaque Drive user permission id for this sync state. Never apply old delete markers to a different account.
    @SerialName("AccountPermissionId") val accountPermissionId: String? = null,
    // Character ids that were on both sides last time; one gone here is then deleted on Drive (same rule as the PC).
    @SerialName("SyncedIds") val syncedIds: List<String> = emptyList(),
    @SerialName("LastSync") val lastSync: Long = 0,
    @SerialName("LastError") val lastError: String? = null,
)
