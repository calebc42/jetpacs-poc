// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionAdmissionOutcomeTest {
    @Test
    fun permanentPeerStatusesMapOnlyAcceptedAndDuplicateToSafeAdmission() {
        assertEquals(
            ActionAdmissionOutcome.SafelyAdmitted(SafeAdmissionEvidence.RemoteAccepted),
            actionAdmissionOutcome("accepted", null),
        )
        assertEquals(
            ActionAdmissionOutcome.SafelyAdmitted(SafeAdmissionEvidence.RemoteDuplicate),
            actionAdmissionOutcome("duplicate", null),
        )
        assertEquals(
            ActionAdmissionOutcome.NotAdmitted(UnsafeAdmissionReason.RemoteStale),
            actionAdmissionOutcome("stale", null),
        )
        assertEquals(
            ActionAdmissionOutcome.NotAdmitted(UnsafeAdmissionReason.RemoteRejected),
            actionAdmissionOutcome("rejected", null),
        )
    }

    @Test
    fun localAndTransportErrorsRetainTheirUnsafeReasonAndPayload() {
        val error = buildJsonObject {
            put("code", 1401)
            put("message", "Outstanding requests exhausted")
            putJsonObject("data") { put("kind", "overloaded") }
        }

        assertEquals(
            ActionAdmissionOutcome.NotAdmitted(
                UnsafeAdmissionReason.Overloaded,
                error,
            ),
            actionAdmissionOutcome(null, error),
        )
    }
}
