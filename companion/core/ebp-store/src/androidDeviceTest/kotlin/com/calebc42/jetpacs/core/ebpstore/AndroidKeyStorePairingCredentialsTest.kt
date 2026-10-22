// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.core.ebpstore

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calebc42.ebp.wire.CompanionProofKeyResult
import com.calebc42.ebp.wire.DurableOutboxPolicy
import com.calebc42.ebp.wire.EbpAuth
import com.calebc42.ebp.wire.EventId
import com.calebc42.ebp.wire.PairingId
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Locale
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Android adapter of the common PairingCredentials interface, which the
 * app's composition root now provisions through (D5): the same three Keystore
 * steps as before, behind one call, plus the proof provider and erase.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeyStorePairingCredentialsTest {
    private val aliasesToDelete = mutableSetOf<String>()

    @After
    fun deleteTestKeys() {
        val keyStore = androidKeyStore()
        aliasesToDelete.forEach { alias ->
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    @Test
    fun provisionYieldsTheVersionedAliasesAndAnOpaqueProofHandle() = runTest {
        val pairing = randomPairingId()
        val names = PairingKeyAliases.v1(pairing)
        aliasesToDelete += names.credentialKeyAlias
        aliasesToDelete += names.payloadKeyAlias
        val credentials = AndroidKeyStorePairingCredentials()
        val token = ByteArray(16).also(SecureRandom()::nextBytes)

        val aliases = credentials.provision(pairing, token.copyOf())

        assertEquals(names.credentialKeyAlias, aliases.credentialKeyAlias)
        assertEquals(names.payloadKeyAlias, aliases.payloadKeyAlias)
        assertTrue(androidKeyStore().containsAlias(aliases.credentialKeyAlias))
        assertTrue(androidKeyStore().containsAlias(aliases.payloadKeyAlias))
        assertTrue(androidKeyStore().containsAlias(PairingKeyAliases.DUMMY_CREDENTIAL_ALIAS))
        assertEquals(CredentialProtection.Keystore("AndroidKeyStore"), credentials.protection)

        val known = credentials.proofProvider.resolve(pairing.value) as CompanionProofKeyResult.Known
        val message = "EBP/3 client:${pairing.value}:$CLIENT_NONCE:$SERVER_NONCE".toByteArray(Charsets.US_ASCII)
        assertEquals(
            EbpAuth.clientProof(token, pairing.value, CLIENT_NONCE, SERVER_NONCE),
            known.key.hmacSha256(message).toHex(),
        )
        assertTrue(credentials.proofProvider.resolve(randomPairingId().value) is CompanionProofKeyResult.Unknown)

        val sealed = credentials.envelopeCodec.encode(payload(), metadata(pairing), aliases.payloadKeyAlias)
        assertEquals(payload(), credentials.envelopeCodec.decode(sealed, metadata(pairing), aliases.payloadKeyAlias))

        credentials.erase(aliases)

        assertFalse(androidKeyStore().containsAlias(aliases.credentialKeyAlias))
        assertFalse(androidKeyStore().containsAlias(aliases.payloadKeyAlias))
        assertTrue(credentials.proofProvider.resolve(pairing.value) is CompanionProofKeyResult.Unknown)
    }

    private fun payload() = buildJsonObject {
        put("event_id", EVENT_ID)
        put("occurred_at_ms", 100)
        put("queued_at_ms", 101)
    }

    private fun metadata(pairingId: PairingId) = OutboxEnvelopeMetadata(
        pairingId = pairingId,
        eventId = EventId(EVENT_ID),
        queueSequence = 1,
        storageGeneration = "00000000000000000000000000000003",
        policy = DurableOutboxPolicy.QUEUE,
        occurredAtMs = 100,
        queuedAtMs = 101,
        expiresAtMs = 1_000,
        dedupeKey = null,
        accountedBytes = 256,
        triggerIdentity = null,
    )

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun randomPairingId(): PairingId = PairingId(
        ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { byte ->
            String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
        },
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val EVENT_ID = "00000000000000000000000000000002"
        const val CLIENT_NONCE = "0123456789abcdef0123456789abcdef"
        const val SERVER_NONCE = "fedcba9876543210fedcba9876543210"
    }
}
