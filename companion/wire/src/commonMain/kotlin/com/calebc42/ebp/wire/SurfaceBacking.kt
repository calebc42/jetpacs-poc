// SPDX-License-Identifier: GPL-3.0-or-later
// Durable backing for the SurfaceStore. The surface histories (SPEC 13.1,
// including tombstones that survive until pairing revocation) and the input
// drafts (which ARE the SPEC 10.2 input_state / the SPEC 15.1 reconnection
// snapshot) are one atomic whole-snapshot replace, so a process death leaves
// either the old state or the new state, never a torn one — the same shape
// as the durable queue's QueueStore.
package com.calebc42.ebp.wire

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One retained surface history. `spec`/`currentView` are null for a
 * tombstone (present == false). */
data class PersistedRecord(
    val surface: String,
    val revision: Long,
    val present: Boolean,
    val spec: JsonObject?,
    val currentView: String?,
)

/**
 * One retained input draft; `value` may be null (a JSON null value).
 *
 * R6: this is the module's one documented exception to the "Kotlin null =
 * member ABSENT" convention — at the draft seam Kotlin null IS the JSON null,
 * which is exactly what the `?: JSONObject.NULL` elvis meant pre-swap. A
 * draft's existence is carried by the record existing at all, so there is no
 * absent value left to distinguish. The on-disk spelling is `"value":null` —
 * never an omitted member, never the STRING "null".
 */
data class PersistedDraft(val surface: String, val id: String, val value: JsonElement?)

data class SurfaceState(val records: List<PersistedRecord>, val drafts: List<PersistedDraft>)

interface SurfaceBacking {
    fun load(): SurfaceState
    /** LD-14: records and drafts persist SEPARATELY. A keystroke touches only
     * drafts, so it re-serializes no spec and no tombstone — the dominant
     * cost was rebuilding every present spec plus up to `max_surface_ids`
     * (4096) tombstones on every draft write. Each is its own atomic,
     * durable replace; throws on failure. */
    fun replaceRecords(records: List<PersistedRecord>)
    fun replaceDrafts(drafts: List<PersistedDraft>)
}

class MemorySurfaceBacking : SurfaceBacking {
    private var records = emptyList<PersistedRecord>()
    private var drafts = emptyList<PersistedDraft>()
    override fun load(): SurfaceState = SurfaceState(records, drafts)
    override fun replaceRecords(records: List<PersistedRecord>) { this.records = records }
    override fun replaceDrafts(drafts: List<PersistedDraft>) { this.drafts = drafts }
}
