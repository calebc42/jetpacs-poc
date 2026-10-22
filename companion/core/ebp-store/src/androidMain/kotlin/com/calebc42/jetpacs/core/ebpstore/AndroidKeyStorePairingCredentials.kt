// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.core.ebpstore

import com.calebc42.ebp.wire.CompanionHmacKeyHandle
import com.calebc42.ebp.wire.CompanionProofKeyResult
import com.calebc42.ebp.wire.CompanionProofProvider
import com.calebc42.ebp.wire.PairingId
import java.security.KeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * [PairingCredentials] over AndroidKeyStore: the existing provisioners,
 * signer and codec, unchanged, behind the common interface a host wires.
 * The proof provider answers a pairing whose credential alias exists in the
 * Keystore with that key and every other ID with the dummy key [provision]
 * ensures.
 */
class AndroidKeyStorePairingCredentials(
    private val aliasPolicy: AndroidPairingKeyAliasPolicy = VersionedAndroidPairingAliasPolicy,
    json: Json = Json,
) : PairingCredentials {
    private val credentialProvisioner = AndroidKeyStoreCredentialProvisioner(aliasPolicy)
    private val payloadProvisioner = AndroidKeyStorePayloadKeyProvisioner(aliasPolicy)

    override val protection: CredentialProtection = CredentialProtection.Keystore(ANDROID_KEY_STORE)

    override suspend fun provision(
        pairingId: PairingId,
        token: ByteArray,
    ): ProvisionedPairingAliases {
        val credential = credentialProvisioner.ensureKey(pairingId, token)
        val payload = payloadProvisioner.ensureKey(pairingId)
        val dummy = credentialProvisioner.ensureDummyKey()
        check(dummy == PairingKeyAliases.DUMMY_CREDENTIAL_ALIAS)
        return ProvisionedPairingAliases(credential.alias, payload.alias)
    }

    override suspend fun erase(aliases: ProvisionedPairingAliases) = withContext(Dispatchers.IO) {
        val keyStore = keyStore()
        listOf(aliases.credentialKeyAlias, aliases.payloadKeyAlias).forEach { alias ->
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    override val proofProvider: CompanionProofProvider = CompanionProofProvider { pairingId ->
        val alias = try {
            aliasPolicy.aliasesFor(PairingId(pairingId)).credentialKeyAlias
        } catch (_: IllegalArgumentException) {
            null
        }
        if (alias != null && keyStore().containsAlias(alias)) {
            CompanionProofKeyResult.Known(handle(alias))
        } else {
            CompanionProofKeyResult.Unknown(handle(PairingKeyAliases.DUMMY_CREDENTIAL_ALIAS))
        }
    }

    override val envelopeCodec: OutboxPayloadEnvelopeCodec =
        AndroidKeyStoreOutboxPayloadEnvelopeCodec(json)

    private fun handle(alias: String) = CompanionHmacKeyHandle { message ->
        AndroidKeyStoreHmacSigner(alias).hmacSha256(message)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}
