// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.core.ebpstore

import com.calebc42.ebp.wire.CompanionProofProvider
import com.calebc42.ebp.wire.PairingId

/** How a platform holds the pairing token and the outbox payload key. */
sealed interface CredentialProtection {
    /** A keystore or keychain the operating system guards: SPEC §9.1's norm. */
    data class Keystore(val backend: String) : CredentialProtection

    /**
     * SPEC §9.1's floor: a file only the user can read. A host that gets this
     * shows the reduced-protection notice at pairing and must not advertise
     * `sensitiveQueueEncrypted` (SPEC §21.5).
     */
    data class PrivateFile(val path: String) : CredentialProtection
}

/**
 * One pairing's authentication material behind operation-only handles: the
 * imported 16-octet token as an HMAC-SHA256 key and a generated AES-256-GCM
 * payload key. The host wires one implementation; the engine sees only the
 * [proofProvider] and the store only the [envelopeCodec].
 */
interface PairingCredentials {
    val protection: CredentialProtection

    /**
     * Imports [token] (the caller erases its copy as soon as this returns) and
     * ensures the payload key, then returns the aliases a Room partition
     * records. Provisioning the same pairing again with the same token is
     * idempotent; a different token under an existing alias is refused.
     */
    suspend fun provision(pairingId: PairingId, token: ByteArray): ProvisionedPairingAliases

    /** Removes both keys: the revocation artifact cleanup. Absent keys are fine. */
    suspend fun erase(aliases: ProvisionedPairingAliases)

    /** Known for a provisioned pairing, otherwise the fixed dummy handle. */
    val proofProvider: CompanionProofProvider

    val envelopeCodec: OutboxPayloadEnvelopeCodec
}
