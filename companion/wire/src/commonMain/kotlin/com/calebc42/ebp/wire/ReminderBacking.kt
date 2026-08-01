// SPDX-License-Identifier: GPL-3.0-or-later
// Durable backing for SPEC 18.6 reminders. The accepted per-owner sets and
// the fired receipts MUST both survive process and device restarts ("persist
// the accepted set across process and device restarts"; "persist fired state
// before or atomically with presentation so a restart does not deliberately
// re-fire it"). Every mutation is one atomic whole-snapshot replace, the
// same shape as QueueStore/SurfaceBacking.
package com.calebc42.ebp.wire

import kotlinx.serialization.json.JsonObject

/** One durable state: owner -> ordered reminder list, plus the fired
 * receipts keyed "owner id at_ms" (the SPEC 18.6 at-most-once tuple). */
data class ReminderState(
    val owners: Map<String, List<JsonObject>>,
    val fired: Set<String>,
)

interface ReminderBacking {
    fun load(): ReminderState
    /** Atomically and durably replace the whole state; throws on failure. */
    fun replace(state: ReminderState)
}

class MemoryReminderBacking : ReminderBacking {
    private var state = ReminderState(emptyMap(), emptySet())
    override fun load(): ReminderState = state
    override fun replace(state: ReminderState) { this.state = state }
}
