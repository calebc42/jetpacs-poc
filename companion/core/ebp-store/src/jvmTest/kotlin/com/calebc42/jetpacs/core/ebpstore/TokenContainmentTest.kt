// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.core.ebpstore

import com.calebc42.ebp.wire.ActivatePairingCommand
import com.calebc42.ebp.wire.ActivatePairingResult
import com.calebc42.ebp.wire.AdmitOutboxCommand
import com.calebc42.ebp.wire.DurableOutboxPolicy
import com.calebc42.ebp.wire.EventId
import com.calebc42.ebp.wire.OutboxAdmissionResult
import com.calebc42.ebp.wire.OutboxLimits
import com.calebc42.ebp.wire.PairingId
import com.calebc42.ebp.wire.activatePairing
import com.calebc42.ebp.wire.admitOutbox
import com.calebc42.jetpacs.core.database.JETPACS_DATABASE_NAME
import com.calebc42.jetpacs.core.database.buildJetpacsDatabase
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * D4's containment proof: a token provisioned through the file floor, a
 * pairing activated in Room and an outbox event sealed through the JVM
 * codec, and the token's raw bytes and display form appear in the credential
 * file only: not in any database file, log line or exception message. Erasing
 * the pairing removes it from the credential file too.
 */
class TokenContainmentTest {
    @Test
    fun theTokenReachesOnlyTheCredentialStore() = runTest {
        val root = Files.createTempDirectory("jetpacs-containment-")
        val data = root.resolve("data")
        val token = ByteArray(16).also(SecureRandom()::nextBytes)
        val display = Base64.getUrlEncoder().withoutPadding().encodeToString(token)
        val pairing = PairingId("0123456789abcdef0123456789abcdef")
        val logs = LogCapture()
        try {
            lateinit var credentials: KeychainPairingCredentials
            lateinit var aliases: ProvisionedPairingAliases
            logs.capture {
                credentials = KeychainPairingCredentials(
                    PrivateFileSecretStore(data.resolve(PrivateFileSecretStore.FILE_NAME)),
                )
                aliases = credentials.provision(pairing, token.copyOf())
                val database = buildJetpacsDatabase(data)
                val store = RoomEbpDurableStore(
                    database = database,
                    aliasResolver = SnapshotProvisionedPairingAliasResolver(mapOf(pairing to aliases)),
                    envelopeCodec = credentials.envelopeCodec,
                    generationSource = SecureRandomStorageGenerationSource(),
                )
                assertTrue(
                    store.activatePairing(ActivatePairingCommand(pairing, createdAtMs = 10))
                        is ActivatePairingResult.Activated,
                )
                assertTrue(store.admitOutbox(eventCommand(pairing)) is OutboxAdmissionResult.Admitted)
                assertTrue(store.restore(pairing).outbox.isNotEmpty())

                // Another token under the same alias fails without echoing either.
                val other = ByteArray(16).also(SecureRandom()::nextBytes)
                try {
                    credentials.provision(pairing, other)
                    fail("a different token must be refused")
                } catch (failure: IllegalStateException) {
                    assertClean(failure.message.orEmpty(), token, display)
                }
                database.close()
            }

            val databaseFiles = data.listDirectoryEntries().filter { it.name.startsWith(JETPACS_DATABASE_NAME) }
            assertTrue("the database file was written", databaseFiles.any { it.name == JETPACS_DATABASE_NAME })
            databaseFiles.forEach { file -> assertClean(Files.readAllBytes(file), token, display, file.toString()) }
            logs.lines().forEach { line -> assertClean(line, token, display) }

            val credentialFile = data.resolve(PrivateFileSecretStore.FILE_NAME)
            assertTrue(Files.readString(credentialFile).contains(display))
            assertEquals(
                CredentialProtection.PrivateFile(credentialFile.toAbsolutePath().toString()),
                credentials.protection,
            )

            credentials.erase(aliases)

            assertFalse(Files.readString(credentialFile).contains(display))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun assertClean(text: String, token: ByteArray, display: String) {
        assertFalse("token display form in: $text", text.contains(display))
        assertClean(text.toByteArray(Charsets.ISO_8859_1), token, display, "text")
    }

    private fun assertClean(bytes: ByteArray, token: ByteArray, display: String, where: String) {
        assertFalse("raw token bytes in $where", bytes.indexOf(token) >= 0)
        assertFalse("token display form in $where", bytes.indexOf(display.toByteArray(Charsets.US_ASCII)) >= 0)
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > size) return -1
        outer@ for (start in 0..size - needle.size) {
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        return -1
    }

    private fun eventCommand(pairing: PairingId): AdmitOutboxCommand {
        val eventId = EventId("1".repeat(32))
        return AdmitOutboxCommand(
            pairingId = pairing,
            eventId = eventId,
            payload = buildJsonObject {
                put("event_id", eventId.value)
                put("occurred_at_ms", 9)
                put("queued_at_ms", 10)
                put("kind", "interaction")
            },
            policy = DurableOutboxPolicy.QUEUE,
            occurredAtMs = 9,
            queuedAtMs = 10,
            expiresAtMs = 20,
            corroboratedEffectiveNowMs = 10,
            dedupeKey = "intent:${eventId.value}",
            accountedBytes = 128,
            limits = OutboxLimits(maxEvents = 32, maxBytes = 1_000_000),
        )
    }

    /** java.util.logging records plus everything printed to System.out and System.err. */
    private class LogCapture {
        private val records = mutableListOf<String>()
        private val streams = ByteArrayOutputStream()

        suspend fun capture(block: suspend () -> Unit) {
            val root = Logger.getLogger("")
            val previousLevel = root.level
            val handler = object : Handler() {
                override fun publish(record: LogRecord) {
                    synchronized(records) {
                        records += record.message.orEmpty() + " " + (record.thrown?.toString() ?: "")
                    }
                }
                override fun flush() {}
                override fun close() {}
            }
            val out = System.out
            val err = System.err
            val tee = PrintStream(streams, true, Charsets.UTF_8)
            root.addHandler(handler)
            root.level = Level.ALL
            System.setOut(tee)
            System.setErr(tee)
            try {
                block()
            } finally {
                System.setOut(out)
                System.setErr(err)
                root.removeHandler(handler)
                root.level = previousLevel
            }
        }

        fun lines(): List<String> =
            records + streams.toString(Charsets.UTF_8).lines()
    }
}
