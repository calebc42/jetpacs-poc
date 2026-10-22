// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Host-owned state seam shared by `state.get` and trigger gates. */
interface DeviceStateAccess {
    val stateTypes: Set<String>
    fun sample(type: String): JsonObject?
    fun unavailableReason(type: String): String = "unavailable"
    fun predicatesHold(predicates: JsonArray): Boolean
}

/** Runtime dependencies that keep the capability catalog adapter separate from app policy. */
data class AppCapabilityBindings(
    val pairingIdentity: String,
    val state: DeviceStateAccess,
    val fireManual: (String) -> Boolean,
    val setKeepScreenOn: (Boolean) -> Unit,
)
