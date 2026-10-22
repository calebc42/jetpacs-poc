// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import com.calebc42.ebp.wire.CompanionProofKeyResult
import com.calebc42.ebp.wire.EbpAuth
import com.calebc42.jetpacs.companion.host.KnownAnswerPairing
import com.calebc42.jetpacs.core.ebpstore.CredentialProtection
import com.calebc42.jetpacs.core.ebpstore.PrivateFileSecretStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC §9.1 on the desktop: generated once, reused by identity, shown once. */
class DesktopPairingTest {
    private val root: Path = Files.createTempDirectory("jetpacs-desktop-pairing-")
    private val data: Path = root.resolve("data")
    private val direct = Executor { it.run() }

    @After
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun store() = PrivateFileSecretStore(data.resolve(PrivateFileSecretStore.FILE_NAME))

    @Test
    fun theFirstRunGeneratesAndLaterRunsReuseTheIdentityWithoutTheToken() = runBlocking {
        val config = DesktopConfig(data, port = 0)
        val first = DesktopCompanion.create(config, direct, store())
        val display = first.pairing.freshTokenDisplay
        assertNotNull(display)
        assertEquals(22, display!!.length)
        assertTrue(EbpAuth.isValidPairingId(first.pairing.pairingId.value))
        assertNotEquals(KnownAnswerPairing.PAIRING_ID, first.pairing.pairingId.value)
        assertFalse(first.pairing.isDevKnownAnswer)
        assertTrue(first.pairing.protection is CredentialProtection.PrivateFile)
        // The credential store holds the token under the common v1 alias and
        // the proof provider knows the pairing.
        assertEquals(display, store().get(first.pairing.aliases.credentialKeyAlias))
        assertTrue(
            first.process.credentials.proofProvider.resolve(first.pairing.pairingId.value)
                is CompanionProofKeyResult.Known,
        )
        first.shutdown()

        // Only the credential file carries the token; not Room, not the settings.
        data.listDirectoryEntries()
            .filter { Files.isRegularFile(it) && it.name != PrivateFileSecretStore.FILE_NAME }
            .forEach { file ->
                assertFalse(file.name, String(Files.readAllBytes(file), Charsets.ISO_8859_1).contains(display))
            }

        val second = DesktopCompanion.create(config, direct, store())
        assertEquals(first.pairing.pairingId, second.pairing.pairingId)
        assertEquals(first.pairing.aliases, second.pairing.aliases)
        assertNull(second.pairing.freshTokenDisplay)
        second.shutdown()
    }

    @Test
    fun theDevelopmentFlagSubstitutesThePublicCredential() = runBlocking {
        val companion = DesktopCompanion.create(
            DesktopConfig(data, port = 0, devKnownAnswerPairing = true),
            direct,
            store(),
        )
        assertEquals(KnownAnswerPairing.PAIRING_ID, companion.pairing.pairingId.value)
        assertTrue(companion.pairing.isDevKnownAnswer)
        assertNull(companion.pairing.freshTokenDisplay)
        val known = companion.process.credentials.proofProvider.resolve(KnownAnswerPairing.PAIRING_ID)
            as CompanionProofKeyResult.Known
        val token = EbpAuth.decodePairingToken(KnownAnswerPairing.TOKEN)
        val message = "EBP/3 client:${KnownAnswerPairing.PAIRING_ID}:$CLIENT_NONCE:$SERVER_NONCE"
        assertEquals(
            EbpAuth.clientProof(token, KnownAnswerPairing.PAIRING_ID, CLIENT_NONCE, SERVER_NONCE),
            known.key.hmacSha256(message.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) },
        )
        companion.shutdown()
    }

    @Test
    fun aRecordedPairingWhoseTokenIsGoneIsRefused() = runBlocking {
        val config = DesktopConfig(data, port = 0)
        DesktopCompanion.create(config, direct, store()).shutdown()
        Files.delete(data.resolve(PrivateFileSecretStore.FILE_NAME))

        val failure = runCatching { DesktopCompanion.create(config, direct, store()) }.exceptionOrNull()

        assertTrue(failure.toString(), failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("new data directory"))
    }

    private companion object {
        const val CLIENT_NONCE = "0123456789abcdef0123456789abcdef"
        const val SERVER_NONCE = "fedcba9876543210fedcba9876543210"
    }
}
