// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.core.ebpstore

import com.calebc42.ebp.wire.CompanionProofKeyResult
import com.calebc42.ebp.wire.DurableOutboxPolicy
import com.calebc42.ebp.wire.EbpAuth
import com.calebc42.ebp.wire.EventId
import com.calebc42.ebp.wire.PairingId
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The desktop credentials over an in-memory [SecretStore]: the shapes of
 * wire's CompanionProofProviderTest and the Keystore device suite, on the JVM.
 */
class KeychainPairingCredentialsTest {
    private val store = MemorySecretStore()
    private val credentials = KeychainPairingCredentials(store)
    private val pairing = PairingId("0123456789abcdef0123456789abcdef")
    private val tokenDisplay = "AAECAwQFBgcICQoLDA0ODw"
    private val token = EbpAuth.decodePairingToken(tokenDisplay)

    @Test
    fun provisionYieldsTheVersionedAliasesAndAnOpaqueProofHandle() = runTest {
        val aliases = credentials.provision(pairing, token.copyOf())

        assertEquals(
            "com.calebc42.jetpacs.ebp.pairing.v1.${pairing.value}.credential-hmac-sha256",
            aliases.credentialKeyAlias,
        )
        assertEquals(
            "com.calebc42.jetpacs.ebp.pairing.v1.${pairing.value}.payload-aes256-gcm",
            aliases.payloadKeyAlias,
        )
        // The store holds the credential's display form, the payload key and the dummy.
        assertEquals(tokenDisplay, store.values[aliases.credentialKeyAlias])
        assertEquals(
            setOf(aliases.credentialKeyAlias, aliases.payloadKeyAlias, PairingKeyAliases.DUMMY_CREDENTIAL_ALIAS),
            store.values.keys,
        )

        val known = credentials.proofProvider.resolve(pairing.value) as CompanionProofKeyResult.Known
        val message = "EBP/3 client:${pairing.value}:$CLIENT_NONCE:$SERVER_NONCE".toByteArray(Charsets.US_ASCII)
        val proof = known.key.hmacSha256(message)
        assertEquals(32, proof.size)
        assertEquals(EbpAuth.clientProof(token, pairing.value, CLIENT_NONCE, SERVER_NONCE), proof.toLowerHex())

        val unknown = credentials.proofProvider.resolve("f".repeat(32))
        assertTrue(unknown is CompanionProofKeyResult.Unknown)
        assertEquals(32, (unknown as CompanionProofKeyResult.Unknown).dummyKey.hmacSha256(message).size)
        assertTrue(credentials.proofProvider.resolve("not-a-pairing-id") is CompanionProofKeyResult.Unknown)
        assertEquals(CredentialProtection.Keystore("memory"), credentials.protection)
    }

    @Test
    fun provisioningAgainIsIdempotentAndADifferentTokenIsRefused() = runTest {
        val first = credentials.provision(pairing, token.copyOf())
        val payloadValue = store.values[first.payloadKeyAlias]
        val sealed = credentials.envelopeCodec.encode(payload(), metadata(), first.payloadKeyAlias)

        val second = credentials.provision(pairing, token.copyOf())

        assertEquals(first, second)
        assertEquals(payloadValue, store.values[second.payloadKeyAlias])
        assertEquals(payload(), credentials.envelopeCodec.decode(sealed, metadata(), second.payloadKeyAlias))

        val other = EbpAuth.decodePairingToken("Dw4NDAsKCQgHBgUEAwIBAA")
        try {
            credentials.provision(pairing, other)
            fail("a different token under the same alias must be refused")
        } catch (failure: IllegalStateException) {
            val message = failure.message.orEmpty()
            assertFalse(message.contains(tokenDisplay))
            assertFalse(message.contains("Dw4NDAsKCQgHBgUEAwIBAA"))
        }
        assertEquals(tokenDisplay, store.values[first.credentialKeyAlias])
    }

    @Test
    fun codecRoundTripsWithFreshIvsAndRejectsTampering() = runTest {
        val aliases = credentials.provision(pairing, token.copyOf())
        val codec = credentials.envelopeCodec
        val metadata = metadata()

        val first = codec.encode(payload(extraField = true), metadata, aliases.payloadKeyAlias)
        val second = codec.encode(payload(extraField = true), metadata, aliases.payloadKeyAlias)

        assertEquals(OutboxEnvelopeFormat.ENVELOPE_VERSION, first[0].toInt())
        val firstIv = OutboxEnvelopeFormat.parse(first).iv
        val secondIv = OutboxEnvelopeFormat.parse(second).iv
        assertEquals(OutboxEnvelopeFormat.IV_SIZE_BYTES, firstIv.size)
        assertFalse(firstIv.contentEquals(secondIv))
        assertEquals(payload(extraField = true), codec.decode(first, metadata, aliases.payloadKeyAlias))
        assertEquals(payload(extraField = true), codec.decode(second, metadata, aliases.payloadKeyAlias))
        assertFalse(String(first, Charsets.ISO_8859_1).contains("event_id"))

        val ciphertextOffset = 1 + OutboxEnvelopeFormat.IV_SIZE_BYTES
        assertBadTag { codec.decode(first.copyOf().also { it[1] = it[1] xor 1 }, metadata, aliases.payloadKeyAlias) }
        assertBadTag {
            codec.decode(
                first.copyOf().also { it[ciphertextOffset] = it[ciphertextOffset] xor 1 },
                metadata,
                aliases.payloadKeyAlias,
            )
        }
        assertBadTag {
            codec.decode(first.copyOf().also { it[it.lastIndex] = it[it.lastIndex] xor 1 }, metadata, aliases.payloadKeyAlias)
        }
        assertBadTag {
            codec.decode(first, metadata.copy(accountedBytes = metadata.accountedBytes + 1), aliases.payloadKeyAlias)
        }
    }

    @Test
    fun codecRejectsAnotherPairingsKeyAndAnErasedAlias() = runTest {
        val aliases = credentials.provision(pairing, token.copyOf())
        val otherPairing = PairingId("fedcba9876543210fedcba9876543210")
        val otherAliases = credentials.provision(otherPairing, EbpAuth.decodePairingToken("Dw4NDAsKCQgHBgUEAwIBAA"))
        val codec = credentials.envelopeCodec
        val envelope = codec.encode(payload(), metadata(), aliases.payloadKeyAlias)

        assertBadTag { codec.decode(envelope, metadata(), otherAliases.payloadKeyAlias) }

        credentials.erase(aliases)

        assertNull(store.values[aliases.credentialKeyAlias])
        assertNull(store.values[aliases.payloadKeyAlias])
        assertNotEquals(null, store.values[otherAliases.credentialKeyAlias])
        try {
            codec.decode(envelope, metadata(), aliases.payloadKeyAlias)
            fail("an erased payload key must raise")
        } catch (_: IllegalStateException) {
            // Expected.
        }
        assertTrue(credentials.proofProvider.resolve(pairing.value) is CompanionProofKeyResult.Unknown)
        assertTrue(credentials.proofProvider.resolve(otherPairing.value) is CompanionProofKeyResult.Known)
    }

    private suspend fun assertBadTag(operation: suspend () -> Unit) {
        try {
            operation()
            fail("expected AEADBadTagException")
        } catch (_: AEADBadTagException) {
            // Expected.
        }
    }

    private fun payload(extraField: Boolean = false) = buildJsonObject {
        put("event_id", EVENT_ID)
        put("occurred_at_ms", 100)
        put("queued_at_ms", 101)
        if (extraField) put("answer", 42)
    }

    private fun metadata() = OutboxEnvelopeMetadata(
        pairingId = pairing,
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

    private infix fun Byte.xor(mask: Int): Byte = (toInt() xor mask).toByte()

    private companion object {
        const val EVENT_ID = "00000000000000000000000000000002"
        const val CLIENT_NONCE = "0123456789abcdef0123456789abcdef"
        const val SERVER_NONCE = "fedcba9876543210fedcba9876543210"
    }
}

/** The fake every credential test shares; `values` is inspectable. */
internal class MemorySecretStore : SecretStore {
    val values = linkedMapOf<String, String>()
    override val protection: CredentialProtection = CredentialProtection.Keystore("memory")
    override fun get(account: String): String? = values[account]
    override fun set(account: String, value: String) { values[account] = value }
    override fun delete(account: String) { values.remove(account) }
}
