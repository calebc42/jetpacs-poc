// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.core.ebpstore

import com.calebc42.ebp.wire.CompanionHmacKeyHandle
import com.calebc42.ebp.wire.CompanionProofKeyResult
import com.calebc42.ebp.wire.CompanionProofProvider
import com.calebc42.ebp.wire.PairingId
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The desktop's [PairingCredentials]: the token and the payload key live in
 * a [SecretStore] as base64url text under the common [PairingKeyAliases]
 * names, are loaded once into [SecretKeySpec]s, and never appear in Room,
 * logs or exception messages. HMAC-SHA256 and AES-256-GCM run through the
 * JDK's providers; the IV is twelve random bytes and the AAD is the common
 * [OutboxEnvelopeFormat], so an envelope has one format on every platform.
 */
class KeychainPairingCredentials(
    private val secrets: SecretStore,
    private val json: Json = Json,
) : PairingCredentials {
    private val lock = Any()
    private val random = SecureRandom()
    private val keys = ConcurrentHashMap<String, SecretKey>()

    override val protection: CredentialProtection get() = secrets.protection

    override suspend fun provision(
        pairingId: PairingId,
        token: ByteArray,
    ): ProvisionedPairingAliases = withContext(Dispatchers.IO) {
        require(token.size == CREDENTIAL_SIZE_BYTES) { "EBP pairing credential must contain 128 bits" }
        val names = PairingKeyAliases.v1(pairingId)
        synchronized(lock) {
            importCredentialIfAbsent(names.credentialKeyAlias, token)
            ensurePayloadKey(names.payloadKeyAlias)
            importCredentialIfAbsent(PairingKeyAliases.DUMMY_CREDENTIAL_ALIAS, ByteArray(CREDENTIAL_SIZE_BYTES))
        }
        ProvisionedPairingAliases(names.credentialKeyAlias, names.payloadKeyAlias)
    }

    override suspend fun erase(aliases: ProvisionedPairingAliases) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            listOf(aliases.credentialKeyAlias, aliases.payloadKeyAlias).forEach { alias ->
                secrets.delete(alias)
                keys.remove(alias)
            }
        }
    }

    override val proofProvider: CompanionProofProvider = CompanionProofProvider { pairingId ->
        val alias = try {
            PairingKeyAliases.v1(PairingId(pairingId)).credentialKeyAlias
        } catch (_: IllegalArgumentException) {
            null
        }
        val key = alias?.let { loadKey(it, HMAC_ALGORITHM) }
        if (key != null) {
            CompanionProofKeyResult.Known(handle(key))
        } else {
            CompanionProofKeyResult.Unknown(handle(dummyKey()))
        }
    }

    override val envelopeCodec: OutboxPayloadEnvelopeCodec = object : OutboxPayloadEnvelopeCodec {
        override suspend fun encode(
            payload: JsonObject,
            metadata: OutboxEnvelopeMetadata,
            payloadKeyAlias: String,
        ): ByteArray = withContext(Dispatchers.IO) {
            val key = payloadKey(payloadKeyAlias)
            val iv = ByteArray(OutboxEnvelopeFormat.IV_SIZE_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(OutboxEnvelopeFormat.TAG_SIZE_BITS, iv))
            cipher.updateAAD(OutboxEnvelopeFormat.aad(metadata, payloadKeyAlias))
            val plaintext = json.encodeToString(JsonObject.serializer(), payload).encodeToByteArray()
            try {
                OutboxEnvelopeFormat.envelope(iv, cipher.doFinal(plaintext))
            } finally {
                plaintext.fill(0)
            }
        }

        override suspend fun decode(
            envelope: ByteArray,
            metadata: OutboxEnvelopeMetadata,
            payloadKeyAlias: String,
        ): JsonObject = withContext(Dispatchers.IO) {
            val parsed = OutboxEnvelopeFormat.parse(envelope)
            val key = payloadKey(payloadKeyAlias)
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(OutboxEnvelopeFormat.TAG_SIZE_BITS, parsed.iv),
            )
            cipher.updateAAD(OutboxEnvelopeFormat.aad(metadata, payloadKeyAlias))
            val plaintext = cipher.doFinal(parsed.ciphertextAndTag)
            try {
                json.decodeFromString(JsonObject.serializer(), plaintext.decodeToString())
            } finally {
                plaintext.fill(0)
            }
        }
    }

    private fun importCredentialIfAbsent(alias: String, credential: ByteArray) {
        val existing = loadBytes(alias)
        if (existing == null) {
            secrets.set(alias, encode(credential))
            return
        }
        try {
            check(MessageDigest.isEqual(existing, credential)) {
                "credential alias contains a different pairing token: $alias"
            }
        } finally {
            existing.fill(0)
        }
    }

    private fun ensurePayloadKey(alias: String) {
        val existing = loadBytes(alias)
        if (existing == null) {
            val generated = ByteArray(PAYLOAD_KEY_SIZE_BYTES).also(random::nextBytes)
            try {
                secrets.set(alias, encode(generated))
            } finally {
                generated.fill(0)
            }
            return
        }
        try {
            check(existing.size == PAYLOAD_KEY_SIZE_BYTES) { "Outbox payload key must be 256 bits: $alias" }
        } finally {
            existing.fill(0)
        }
    }

    private fun payloadKey(alias: String): SecretKey =
        loadKey(alias, AES_ALGORITHM) ?: error("Outbox payload key does not exist: $alias")

    private fun dummyKey(): SecretKey =
        loadKey(PairingKeyAliases.DUMMY_CREDENTIAL_ALIAS, HMAC_ALGORITHM)
            ?: SecretKeySpec(ByteArray(CREDENTIAL_SIZE_BYTES), HMAC_ALGORITHM)

    /** The cached key for [alias], or null when the store has no value for it. */
    private fun loadKey(alias: String, algorithm: String): SecretKey? =
        keys[alias] ?: loadBytes(alias)?.let { bytes ->
            try {
                SecretKeySpec(bytes, algorithm)
            } finally {
                bytes.fill(0)
            }
        }?.also { key -> keys[alias] = key }

    private fun loadBytes(alias: String): ByteArray? =
        secrets.get(alias)?.let { Base64.getUrlDecoder().decode(it) }

    private fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun handle(key: SecretKey) = CompanionHmacKeyHandle { message ->
        Mac.getInstance(HMAC_ALGORITHM).run {
            init(key)
            doFinal(message)
        }
    }

    private companion object {
        const val CREDENTIAL_SIZE_BYTES = 16
        const val PAYLOAD_KEY_SIZE_BYTES = 32
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val AES_ALGORITHM = "AES"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
