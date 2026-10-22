// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.core.ebpstore

import com.calebc42.ebp.wire.PairingId

/**
 * Reserved key names for one pairing. Naming only: holding these is not
 * evidence that either key was provisioned, which is why this is not a
 * [ProvisionedPairingAliases].
 */
data class PairingKeyNames(
    val credentialKeyAlias: String,
    val payloadKeyAlias: String,
) {
    init {
        require(credentialKeyAlias.isNotBlank())
        require(payloadKeyAlias.isNotBlank())
        require(credentialKeyAlias != payloadKeyAlias)
    }
}

/**
 * The stable v1, purpose-separated key names every platform uses, so an
 * Android Keystore entry, a desktop keychain item and a Room partition row
 * all spell one pairing's keys the same way.
 */
object PairingKeyAliases {
    private const val NAMESPACE = "com.calebc42.jetpacs.ebp.pairing.v1"

    /** The fixed credential that answers unknown pairing IDs (SPEC §9.2). */
    const val DUMMY_CREDENTIAL_ALIAS = "$NAMESPACE.unknown.credential-hmac-sha256"

    fun v1(pairingId: PairingId): PairingKeyNames = PairingKeyNames(
        credentialKeyAlias = "$NAMESPACE.${pairingId.value}.credential-hmac-sha256",
        payloadKeyAlias = "$NAMESPACE.${pairingId.value}.payload-aes256-gcm",
    )
}
