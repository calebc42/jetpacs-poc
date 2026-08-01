// SPDX-License-Identifier: GPL-3.0-or-later
// RF-3: the extension-dispatch seam (PLAN-rf3-seam.md). A negotiated
// non-core module registers (namespace, negotiation entry, method table,
// handler) as CONFIGURATION — validated at engine construction, never
// mutated mid-session. The shape mirrors CapabilityHandler/
// CapabilityOutcome: handlers return an outcome, the engine remains the
// only emitter.
package com.calebc42.ebp.wire

import kotlinx.serialization.json.JsonObject

/**
 * One extension module. `namespace` owns every method key in `methods`
 * (all `namespace.`-prefixed); `capability` is the module's negotiation
 * entry — granted iff wanted, exactly like a core capability (SPEC 10.2's
 * silent-omission rule included). Method specs reuse the core
 * [MethodSpec] vocabulary (sender, class, legal states), so the seam
 * state-gates tenant traffic with the same taxonomy the registry uses.
 * I3: the `ebp.` namespace is the spec's — registration refuses it until
 * a rung arrives with a ratified registry entry (PLAN-rf3-seam.md D7).
 */
data class EbpModule(
    val namespace: String,
    val capability: String,
    val methods: Map<String, MethodSpec>,
    val handler: ModuleHandler,
)

/** A notification a handler asks the engine to emit after the reply.
 * Validated against the module's own table (Companion-sender,
 * notification-class, state-legal) — an invalid entry is dropped, never
 * emitted. */
data class ModuleNotification(val method: String, val params: JsonObject)

sealed class ModuleOutcome {
    /** The request's result (ignored for notification-class dispatch),
     * plus notifications to emit through the engine's one outbound
     * funnel. */
    data class Ok(
        val result: JsonObject,
        val notify: List<ModuleNotification> = emptyList(),
    ) : ModuleOutcome()

    /** A typed refusal, §8-shaped: the engine answers
     * `errorResponse(id, code, message, kind, data)`. */
    data class Fail(
        val code: Int,
        val message: String,
        val kind: String,
        val data: JsonObject = JsonObject(emptyMap()),
    ) : ModuleOutcome()
}

/**
 * The tenant's dispatch target. Called under the engine's lock with the
 * decoded OBJECT params (the funnel already answered -32602/dropped
 * non-object params) only when the module's capability was granted this
 * session and the method passed the same direction/class/state gates the
 * core applies. Throwing [ContentInvalid] answers `1201 content-invalid`
 * with path/reason; any other throw answers `-32603` — a tenant request
 * always concludes (SPEC 7.1). Do not block: the reader thread is under
 * the WireLock here.
 */
fun interface ModuleHandler {
    fun handle(method: String, params: JsonObject): ModuleOutcome
}
