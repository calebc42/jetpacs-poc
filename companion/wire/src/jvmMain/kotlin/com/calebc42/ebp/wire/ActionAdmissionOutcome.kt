// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import kotlinx.serialization.json.JsonObject

/** The evidence that makes one remote EBP action occurrence safely admitted. */
enum class SafeAdmissionEvidence {
    /** A complete queue/wake record committed to the durable outbox. */
    DurableQueued,

    /** Emacs durably accepted a live drop occurrence. */
    RemoteAccepted,

    /** Emacs had already durably accepted the same live occurrence. */
    RemoteDuplicate,
}

/** Why one remote action occurrence failed to reach safe admission. */
enum class UnsafeAdmissionReason {
    OfflineDrop,
    NotReady,
    InvalidContext,
    ContentInvalid,
    QueueFull,
    StorageFailed,
    RemoteStale,
    RemoteRejected,
    TransientError,
    Overloaded,
    Cancelled,
    Timeout,
    TransportClosed,
    Unknown,
}

/**
 * One terminal admission conclusion for a remote action occurrence.
 *
 * This is deliberately narrower than remote completion: durable queue/wake
 * actions conclude here as soon as their local record commits, while a live
 * drop concludes only when Emacs returns `accepted` or `duplicate` (SPEC
 * 14.4). Callers receive exactly one instance for every occurrence passed to
 * the engine, including local refusal and transport teardown.
 */
sealed interface ActionAdmissionOutcome {
    data class SafelyAdmitted(
        val evidence: SafeAdmissionEvidence,
    ) : ActionAdmissionOutcome

    data class NotAdmitted(
        val reason: UnsafeAdmissionReason,
        /** The engine or peer error when one exists; never admission evidence. */
        val error: JsonObject? = null,
    ) : ActionAdmissionOutcome

    /** A receiver-local builtin completed and has no remote admission phase. */
    data object LocallyCompleted : ActionAdmissionOutcome
}

/** Convert one peer/synthetic result into the renderer-safe admission model. */
internal fun actionAdmissionOutcome(
    status: String?,
    error: JsonObject?,
): ActionAdmissionOutcome = when (status) {
    "queued" -> ActionAdmissionOutcome.SafelyAdmitted(
        SafeAdmissionEvidence.DurableQueued,
    )
    "accepted" -> ActionAdmissionOutcome.SafelyAdmitted(
        SafeAdmissionEvidence.RemoteAccepted,
    )
    "duplicate" -> ActionAdmissionOutcome.SafelyAdmitted(
        SafeAdmissionEvidence.RemoteDuplicate,
    )
    "stale" -> ActionAdmissionOutcome.NotAdmitted(
        UnsafeAdmissionReason.RemoteStale,
    )
    "rejected" -> ActionAdmissionOutcome.NotAdmitted(
        UnsafeAdmissionReason.RemoteRejected,
    )
    else -> ActionAdmissionOutcome.NotAdmitted(errorReason(error), error)
}

private fun errorReason(error: JsonObject?): UnsafeAdmissionReason {
    val kind = error?.objOrNull("data")?.stringOrNull("kind")
    return when (kind) {
        "content-invalid" -> UnsafeAdmissionReason.ContentInvalid
        "queue-full" -> UnsafeAdmissionReason.QueueFull
        "internal-error" -> UnsafeAdmissionReason.StorageFailed
        "event-retry" -> UnsafeAdmissionReason.TransientError
        "overloaded" -> UnsafeAdmissionReason.Overloaded
        "cancelled", "canceled" -> UnsafeAdmissionReason.Cancelled
        "timeout" -> UnsafeAdmissionReason.Timeout
        "connection-closed" -> UnsafeAdmissionReason.TransportClosed
        else -> UnsafeAdmissionReason.Unknown
    }
}
