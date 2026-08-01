// SPDX-License-Identifier: GPL-3.0-or-later
// Durable backing for SPEC 21 trigger registrations. Persists each identity's
// normalized entries AND the runtime records SPEC 21.2 requires survive a
// restart (throttle floor, one-shot completed marker, boot generation
// receipt, repeating schedule anchor / last-fire floor). Silent baselines and
// edge levels are DELIBERATELY NOT persisted: SPEC 21.5 requires them to be
// re-established silently from the CURRENT state after a restart, so a change
// that happened while the process was dead must not fire. Same atomic
// whole-snapshot replace shape as QueueStore/ReminderBacking.
package com.calebc42.ebp.wire

import kotlinx.serialization.json.JsonObject

data class PersistedRegistration(
    val entry: JsonObject,
    val throttleFloorMs: Long?,
    val oneShotCompleted: Boolean,
    val scheduleAnchorMs: Long?,
    val lastFireFloorMs: Long?,
    val bootGeneration: String?,
)

/** identity -> its ordered registrations. */
data class TriggerState(val identities: Map<String, List<PersistedRegistration>>)

interface TriggerBacking {
    fun load(): TriggerState
    /** Atomically and durably replace the whole state; throws on failure. */
    fun replace(state: TriggerState)
}

class MemoryTriggerBacking : TriggerBacking {
    private var state = TriggerState(emptyMap())
    override fun load(): TriggerState = state
    override fun replace(state: TriggerState) { this.state = state }
}
