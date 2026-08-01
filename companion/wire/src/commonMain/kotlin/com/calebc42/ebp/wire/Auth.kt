// SPDX-License-Identifier: GPL-3.0-or-later
// EBP 2 pairing and mutual authentication. Implements ebp/SPEC.md section 9.
package com.calebc42.ebp.wire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object EbpAuth {
    private val NONCE = Regex("[0-9a-f]{32}")
    private val PROOF = Regex("[0-9a-f]{64}")

    /** SPEC 9.2/4.4: exactly 32 lowercase hexadecimal characters. */
    fun isValidNonce(s: String): Boolean = NONCE.matches(s)

    /** SPEC 4.4: exactly 64 lowercase hexadecimal characters. */
    fun isValidProof(s: String): Boolean = PROOF.matches(s)

    /**
     * SPEC 9.1: the 22-character RFC 4648 base64url display form (padding
     * omitted) decodes to the 16 raw octets both endpoints use as HMAC key.
     */
    fun decodePairingToken(display: String): ByteArray {
        require(display.length == 22) { "pairing token must be 22 base64url characters" }
        val raw = base64UrlDecode(display)
        require(raw.size == 16) { "pairing token did not decode to 16 octets" }
        return raw
    }

    /** Fresh 32-hex nonce from the platform CSPRNG (SPEC 9.2). */
    fun generateNonce(): String = secureRandomBytes(16).toHex()

    /** SPEC 9.3 client proof over the exact ASCII concatenation. */
    fun clientProof(token: ByteArray, pairingId: String,
                    clientNonce: String, serverNonce: String): String =
        hmacText(token, "EBP/2 client:$pairingId:$clientNonce:$serverNonce").toHex()

    /** SPEC 9.3 companion proof; note the swapped nonce order. */
    fun serverProof(token: ByteArray, pairingId: String,
                    clientNonce: String, serverNonce: String): String =
        hmacText(token, "EBP/2 companion:$pairingId:$serverNonce:$clientNonce").toHex()

    /**
     * SPEC 9.2: the fixed dummy key the unknown-pairing-ID path verifies
     * against, so that branch does the same HMAC-SHA256 work as a known ID.
     * Its value is irrelevant — only that it is fixed, and that no proof an
     * attacker can produce matches against it.
     */
    val DUMMY_PROOF_KEY: ByteArray = ByteArray(16)

    /**
     * SPEC 9.3: compare two fixed-length ASCII identifiers without leaking
     * how many leading characters matched. Both operands are fixed-length by
     * grammar (SPEC 4.4), so a length difference carries nothing secret.
     */
    fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return constantTimeEquals(a.encodeToByteArray(), b.encodeToByteArray())
    }

    /** SPEC 9.3: constant-time comparison; malformed proofs never match. */
    fun verifyClientProof(proof: String, token: ByteArray, pairingId: String,
                          clientNonce: String, serverNonce: String): Boolean =
        isValidProof(proof) && constantTimeEquals(
            proof.encodeToByteArray(),
            clientProof(token, pairingId, clientNonce, serverNonce)
                .encodeToByteArray())

    fun verifyServerProof(proof: String, token: ByteArray, pairingId: String,
                          clientNonce: String, serverNonce: String): Boolean =
        isValidProof(proof) && constantTimeEquals(
            proof.encodeToByteArray(),
            serverProof(token, pairingId, clientNonce, serverNonce)
                .encodeToByteArray())

    /** SPEC 9.2 `session.hello` params (the Companion validates these). */
    fun helloParams(clientName: String, clientVersion: String, pairingId: String,
                    clientNonce: String, wants: List<String>): JsonObject =
        buildJsonObject {
            put("protocol", 2)
            put("client", buildJsonObject {
                put("name", clientName)
                put("version", clientVersion)
            })
            put("pairing_id", pairingId)
            put("client_nonce", clientNonce)
            put("wants", JsonArray(wants.map { JsonPrimitive(it) }))
        }

    /** SPEC 9.3 `auth.response` params with the computed proof. */
    fun authParams(pairingId: String, clientNonce: String, serverNonce: String,
                   token: ByteArray): JsonObject =
        buildJsonObject {
            put("pairing_id", pairingId)
            put("client_nonce", clientNonce)
            put("server_nonce", serverNonce)
            put("client_proof", clientProof(token, pairingId, clientNonce, serverNonce))
        }

    // Named hmacText (not hmacSha256) so the member never shadows the
    // top-level expect it delegates to.
    private fun hmacText(key: ByteArray, message: String): ByteArray =
        hmacSha256(key, message.encodeToByteArray())

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
