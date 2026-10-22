// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import com.calebc42.ebp.wire.CompanionProofKeyResult
import com.calebc42.ebp.wire.EbpAuth
import com.calebc42.ebp.wire.PairingId
import com.calebc42.jetpacs.companion.host.KnownAnswerPairing
import com.calebc42.jetpacs.core.database.JetpacsDatabase
import com.calebc42.jetpacs.core.ebpstore.CredentialProtection
import com.calebc42.jetpacs.core.ebpstore.PairingCredentials
import com.calebc42.jetpacs.core.ebpstore.ProvisionedPairingAliases

/**
 * The desktop's one pairing (SPEC §9.1). The first run generates both values
 * with the platform CSPRNG, provisions the token into the credentials and
 * exposes its display form once, for the pairing screen; every later run
 * reads the identity back from the active Room partition and never sees
 * the token again. The token leaves this process only through that screen.
 */
data class DesktopPairing(
    val pairingId: PairingId,
    val aliases: ProvisionedPairingAliases,
    /** The token's 22-character display form when this run generated it; null otherwise. */
    val freshTokenDisplay: String?,
    val protection: CredentialProtection,
    /** True when the public SPEC §9.3 credential was substituted on request (development only). */
    val isDevKnownAnswer: Boolean,
) {
    companion object {
        suspend fun loadOrCreate(
            database: JetpacsDatabase,
            credentials: PairingCredentials,
            devKnownAnswer: Boolean,
        ): DesktopPairing {
            if (devKnownAnswer) {
                val pairingId = PairingId(KnownAnswerPairing.PAIRING_ID)
                val token = EbpAuth.decodePairingToken(KnownAnswerPairing.TOKEN)
                val aliases = try {
                    credentials.provision(pairingId, token)
                } finally {
                    token.fill(0)
                }
                return DesktopPairing(pairingId, aliases, null, credentials.protection, true)
            }
            val existing = database.pairingDao().listActivePartitions()
                .firstOrNull { it.pairingId != KnownAnswerPairing.PAIRING_ID }
            if (existing != null) {
                val pairingId = PairingId(existing.pairingId)
                check(credentials.proofProvider.resolve(pairingId.value) is CompanionProofKeyResult.Known) {
                    "The pairing ${pairingId.value} is recorded in the database but its token is " +
                        "no longer in the credential store; start with a new data directory to pair again"
                }
                return DesktopPairing(
                    pairingId,
                    ProvisionedPairingAliases(existing.credentialKeyAlias, existing.payloadKeyAlias),
                    freshTokenDisplay = null,
                    protection = credentials.protection,
                    isDevKnownAnswer = false,
                )
            }
            val pairingId = PairingId(EbpAuth.generatePairingId())
            val token = EbpAuth.generatePairingToken()
            val display: String
            val aliases = try {
                display = EbpAuth.encodePairingToken(token)
                credentials.provision(pairingId, token)
            } finally {
                token.fill(0)
            }
            return DesktopPairing(pairingId, aliases, display, credentials.protection, false)
        }
    }
}
