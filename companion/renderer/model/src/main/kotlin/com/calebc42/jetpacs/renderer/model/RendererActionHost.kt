// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.model

import com.calebc42.ebp.wire.InputDisplay
import com.calebc42.ebp.wire.SafeAdmissionEvidence
import com.calebc42.ebp.wire.UnsafeAdmissionReason
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicReference

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
class RendererActionRequest(
    val context: RendererActionContext,
    val descriptor: JsonObject,
    val value: JsonElement? = null,
    val injected: JsonObject? = null,
    /** Non-secret dialog-local capture. Surface renderers must leave this null. */
    val fields: JsonObject? = null,
    /** Explicit volatile secret capture; never interchangeable with [fields]. */
    val secret: RendererVolatileSecret? = null,
    val sourceId: String? = null,
) {
    override fun toString(): String =
        "RendererActionRequest(context=$context, descriptor=$descriptor, " +
            "value=$value, injected=${injected != null}, " +
            "fields=${fields?.size ?: 0}<redacted>, secret=$secret, " +
            "sourceId=$sourceId)"
}

/**
 * One controlled, renderer-owned password capture.
 *
 * The type deliberately does not expose its values through `toString`,
 * equality, or hashing. The session borrows [fieldsOrNull] only while building
 * the one live drop request, then calls [releaseCapturedValues]. A terminal
 * outcome calls [erase] on the main thread to clear both the borrowed fields
 * and the native editing state exactly once.
 */
class RendererVolatileSecret private constructor(
    fields: JsonObject,
    /** Exact field identifiers whose values are secret. */
    val secretIds: Set<String>,
    private val lifetime: SecretLifetime,
) {
    constructor(
        fields: JsonObject,
        secretIds: Set<String>,
        eraseNativeState: () -> Unit,
    ) : this(fields, secretIds, SecretLifetime(eraseNativeState))

    private val captured = AtomicReference<JsonObject?>(fields)

    init {
        require(secretIds.isNotEmpty()) { "A volatile secret needs an identifier" }
        require(fields.keys.containsAll(secretIds)) {
            "Every volatile secret identifier must have a captured value"
        }
        lifetime.register(captured)
    }

    private constructor(
        fields: JsonObject,
        secretIds: Set<String>,
        lifetime: SecretLifetime,
        eraseAdditionalState: () -> Unit,
    ) : this(fields, secretIds, lifetime) {
        lifetime.registerAdditionalEraser(eraseAdditionalState)
    }

    /** Borrow the capture for immediate synchronous request construction. */
    fun fieldsOrNull(): JsonObject? = captured.get()

    /** Drop this controlled capture without touching Compose state. */
    fun releaseCapturedValues() {
        captured.set(null)
    }

    /** Release all related captures and erase their native owners once. */
    fun erase() {
        lifetime.erase()
    }

    /**
     * Create a combined dialog capture sharing this capture's erasure life.
     * Erasing either instance clears every registered value reference.
     */
    fun derive(
        fields: JsonObject,
        secretIds: Set<String>,
        eraseAdditionalState: () -> Unit,
    ): RendererVolatileSecret = RendererVolatileSecret(
        fields,
        secretIds,
        lifetime,
        eraseAdditionalState,
    )

    override fun toString(): String =
        "RendererVolatileSecret(ids=${secretIds.size}, values=<redacted>)"
}

/** All controlled copies and native erasers for one password occurrence. */
private class SecretLifetime(initialEraser: () -> Unit) {
    private val references = mutableListOf<AtomicReference<JsonObject?>>()
    private val erasers = mutableListOf(initialEraser)
    private var erased = false

    @Synchronized
    fun register(reference: AtomicReference<JsonObject?>) {
        if (erased) reference.set(null) else references += reference
    }

    fun registerAdditionalEraser(eraser: () -> Unit) {
        val invokeNow = synchronized(this) {
            if (erased) true
            else {
                erasers += eraser
                false
            }
        }
        if (invokeNow) eraser()
    }

    fun erase() {
        val callbacks = synchronized(this) {
            if (erased) return
            erased = true
            references.forEach { it.set(null) }
            references.clear()
            erasers.toList().also { erasers.clear() }
        }
        callbacks.forEach { it() }
    }
}

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
        secret: RendererVolatileSecret? = null,
        onOutcome: (RendererActionOutcome) -> Unit = {},
    ): ActionHandoff

    /** Dismiss one outstanding dialog without inventing submitted values. */
    fun dismissDialog(dialogId: String)
}
