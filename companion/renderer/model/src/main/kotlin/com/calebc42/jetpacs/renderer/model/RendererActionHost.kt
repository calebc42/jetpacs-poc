// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.model

import com.calebc42.ebp.wire.InputDisplay
import com.calebc42.ebp.wire.SafeAdmissionEvidence
import com.calebc42.ebp.wire.UnsafeAdmissionReason
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Synchronous proof that a renderer occurrence entered the ordinary host path. */
enum class ActionHandoff {
    HandedOff,
    Ignored,
}

/** The EBP context in which a renderer-owned interaction occurred. */
sealed interface RendererActionContext {
    data class Surface(val surface: String) : RendererActionContext
    data class Dialog(val dialogId: String) : RendererActionContext
}

/**
 * One renderer occurrence of an accepted EBP ActionDescriptor.
 *
 * The host owns confirmation, capture, durability, offline policy, and wire
 * delivery. Renderers supply only the accepted descriptor and occurrence-time
 * values; no presentation module acquires a transport or storage boundary.
 */
data class RendererActionRequest(
    val context: RendererActionContext,
    val descriptor: JsonObject,
    val value: JsonElement? = null,
    val injected: JsonObject? = null,
    val fields: JsonObject? = null,
    val sourceId: String? = null,
)

/** One terminal result delivered exactly once for a handed-off occurrence. */
sealed interface RendererActionOutcome {
    data class SafelyAdmitted(
        val evidence: SafeAdmissionEvidence,
    ) : RendererActionOutcome

    /** A receiver-local builtin completed without a remote admission concept. */
    data object LocallyCompleted : RendererActionOutcome

    data class NotAdmitted(
        val reason: UnsafeAdmissionReason,
        val error: JsonObject? = null,
    ) : RendererActionOutcome
}

/**
 * Toolkit-neutral state and action operations used by every Compose renderer.
 *
 * [dispatch] returns after handing the occurrence to the host. Its callback is
 * delivered on the Android main thread exactly once; safe admission may occur
 * later. This split lets accessibility report a successful handoff immediately
 * while `clear_on_submit` waits for the stronger SPEC 14.4 conclusion.
 */
interface RendererActionStateHost {
    /** Latest accepted display value and reset generation for each input. */
    val inputDisplays: StateFlow<Map<Pair<String, String>, InputDisplay>>

    /** Negotiated ceiling for one JCS-encoded logical input value. */
    val maxFieldBytes: Int

    fun dispatch(
        request: RendererActionRequest,
        onOutcome: (RendererActionOutcome) -> Unit = {},
    ): ActionHandoff

    /** Admit one logical non-secret draft before any paired action occurrence. */
    fun publishState(
        surface: String,
        id: String,
        value: JsonElement?,
        caret: Int? = null,
    )

    /** Authored fallback values for one currently presented dialog. */
    fun dialogDefaults(dialogId: String): JsonObject?

    /**
     * Complete a dialog response through the host transport.
     *
     * [onOutcome] concludes only after the complete response has reached the
     * sink or a local refusal has made that impossible.
     */
    fun submitDialog(
        dialogId: String,
        value: JsonElement?,
        fields: JsonObject,
        onOutcome: (RendererActionOutcome) -> Unit = {},
    ): ActionHandoff

    /** Dismiss one outstanding dialog without inventing submitted values. */
    fun dismissDialog(dialogId: String)
}
