// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import com.calebc42.ebp.wire.EbpAuth
import com.calebc42.jetpacs.companion.host.TransportStatus
import com.calebc42.jetpacs.core.ebpstore.PrivateFileSecretStore
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC 5.6 from the Companion's side: a fake daemon Emacs binds a Unix
 * socket in a private temporary directory and the desktop Companion dials
 * it, with the dialer's sleeps and jitter injected so the schedule is
 * asserted, not waited for.
 */
class EmacsUnixDialerTest {
    // Short, so the path stays far below sun_path's 108 bytes.
    private val root: Path = Files.createTempDirectory("ebp-")
    private val data: Path = root.resolve("data")
    private val socket: Path = root.resolve("ebp.sock")
    private val direct = Executor { it.run() }
    private val delays = CopyOnWriteArrayList<Long>()
    private val fastSleep: (Long) -> Unit = { delay -> delays += delay; Thread.sleep(minOf(delay, 20)) }

    @After
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun store() = PrivateFileSecretStore(data.resolve(PrivateFileSecretStore.FILE_NAME))

    private fun config() = DesktopConfig(data, port = 0, transport = TransportProfile.EMACS_UNIX, socket = socket)

    private fun dialer(
        ownerCheck: SocketOwnerCheck = SocketOwnerCheck.CurrentUser,
        helloDeadlineMs: Long = EmacsUnixDialer.HELLO_DEADLINE_MS,
        sleep: (Long) -> Unit = fastSleep,
    ) = EmacsUnixDialer(socket, JulHostLog, ownerCheck, random = { 0.0 }, sleep = sleep, helloDeadlineMs = helloDeadlineMs)

    private fun bind(): ServerSocketChannel =
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).also { it.bind(UnixDomainSocketAddress.of(socket)) }

    private suspend fun companion(transport: EmacsUnixDialer) =
        DesktopCompanion.create(config(), direct, store(), transport).also { assertTrue(it.start()) }

    private fun token(companion: DesktopCompanion) = EbpAuth.decodePairingToken(companion.pairing.freshTokenDisplay!!)

    @Test
    fun dialsTheBoundSocketAndRunsTheFullScript() = runBlocking<Unit> {
        bind().use { server ->
            val companion = companion(dialer())
            try {
                ScriptedEmacs.accept(server).use { emacs ->
                    emacs.runPairingScript(companion, token(companion))
                    assertEquals(TransportStatus.Connected(socket.toString()), companion.transportStatus.value)
                }
            } finally {
                companion.shutdown()
            }
        }
        assertFalse(companion(dialer()).also { it.shutdown() }.transportStatus.value.isActive)
    }

    @Test
    fun aSocketThatAppearsLateIsDialedAfterBackoff() = runBlocking<Unit> {
        val companion = companion(dialer())
        try {
            withTimeout(10_000) {
                companion.transportStatus.first { it is TransportStatus.Dialing && it.attempt >= 2 }
            }
            bind().use { server ->
                ScriptedEmacs.accept(server).use { emacs ->
                    emacs.handshakeToReady(companion, token(companion))
                }
            }
            assertEquals(listOf(750L, 1500L), delays.take(2))
        } finally {
            companion.shutdown()
        }
    }

    @Test
    fun aBinderCloseIsFollowedByARedialToASecondSession() = runBlocking<Unit> {
        bind().use { server ->
            val companion = companion(dialer())
            try {
                ScriptedEmacs.accept(server).use { emacs -> emacs.handshakeToReady(companion, token(companion)) }
                delays.clear()
                // The binder went away: a close the Companion did not initiate.
                ScriptedEmacs.accept(server).use { emacs -> emacs.handshakeToReady(companion, token(companion)) }
                assertEquals("the attempt counter reset after READY", 750L, delays.first())
            } finally {
                companion.shutdown()
            }
        }
    }

    @Test
    fun aCompanionInitiatedCloseRedialsAtOnce() = runBlocking<Unit> {
        bind().use { server ->
            val companion = companion(dialer())
            try {
                ScriptedEmacs.accept(server).use { emacs ->
                    emacs.handshakeToReady(companion, token(companion))
                    delays.clear()
                    // The design-runtime toggle reconnects on purpose.
                    companion.bridge.setExperimentalDesignRuntimeEnabled(!companion.settings.designRuntimeEnabled)
                    ScriptedEmacs.accept(server).use { again -> again.handshakeToReady(companion, token(companion)) }
                }
                assertTrue("no backoff after a Companion-initiated close: $delays", delays.isEmpty())
            } finally {
                companion.shutdown()
            }
        }
    }

    @Test
    fun anOwnerMismatchIsTerminalAndVisible() = runBlocking<Unit> {
        bind().use { server ->
            server.configureBlocking(false)
            val companion = companion(dialer(ownerCheck = SocketOwnerCheck { SocketVerdict.Refused("owned by nobody") }))
            try {
                val failed = withTimeout(10_000) { companion.transportStatus.first { it is TransportStatus.Failed } }
                assertTrue(failed.toString(), (failed as TransportStatus.Failed).reason.contains("nobody"))
                assertFalse(failed.isActive)
                Thread.sleep(300)
                assertNull("the refused socket was never dialed", server.accept())
            } finally {
                companion.shutdown()
            }
            assertTrue(companion.transportStatus.value is TransportStatus.Failed)
        }
    }

    @Test
    fun aMutePeerIsAbortedAfterTheHelloDeadline() = runBlocking<Unit> {
        bind().use { server ->
            val companion = companion(dialer(helloDeadlineMs = 200))
            try {
                val accepted = acceptChannel(server)
                // Emacs must speak first (SPEC 5.1); this one never does.
                assertEquals(-1, readOne(accepted, 5_000))
                withTimeout(10_000) { companion.transportStatus.first { it is TransportStatus.Dialing } }
            } finally {
                companion.shutdown()
            }
        }
    }

    @Test
    fun stopWhileSleepingReturnsPromptly() = runBlocking<Unit> {
        val companion = companion(dialer(sleep = Thread::sleep))
        withTimeout(10_000) { companion.transportStatus.first { it is TransportStatus.Dialing && it.attempt >= 1 } }
        val stopped = withTimeoutOrNull(3_000) { companion.shutdown(); true }
        assertEquals(true, stopped)
        assertEquals(TransportStatus.Idle, companion.transportStatus.value)
    }

    @Test
    fun stopWhileConnectedClosesTheChannel() = runBlocking<Unit> {
        bind().use { server ->
            val companion = companion(dialer())
            val accepted = acceptChannel(server)
            ScriptedEmacsOver(accepted).use { emacs -> emacs.handshakeToReady(companion, token(companion)) }
            companion.shutdown()
            assertEquals(-1, readOne(accepted, 5_000))
            assertEquals(TransportStatus.Idle, companion.transportStatus.value)
        }
    }

    @Test
    fun delayScheduleIsOneSecondDoublingToFifteenWithJitter() {
        assertEquals(listOf(750L, 1500L, 3000L, 6000L, 11250L, 11250L), (0..5).map { RedialBackoff.delayMs(it, 0.0) })
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 15000L, 15000L), (0..5).map { RedialBackoff.delayMs(it, 1.0) })
        assertEquals(15000L, RedialBackoff.delayMs(40, 1.0))
    }

    private fun acceptChannel(server: ServerSocketChannel): SocketChannel {
        val accepted = CompletableFuture<SocketChannel>()
        thread(isDaemon = true) { runCatching { server.accept() }.onSuccess(accepted::complete).onFailure(accepted::completeExceptionally) }
        return accepted.get(10, TimeUnit.SECONDS)
    }

    /** One blocking read on [channel] from another thread: -1 on EOF, or the byte count, within [timeoutMs]. */
    private fun readOne(channel: SocketChannel, timeoutMs: Long): Int {
        val result = CompletableFuture<Int>()
        thread(isDaemon = true) { runCatching { channel.read(ByteBuffer.allocate(1)) }.onSuccess(result::complete).onFailure { result.complete(-1) } }
        return result.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    @Suppress("TestFunctionName")
    private fun ScriptedEmacsOver(channel: SocketChannel): ScriptedEmacs =
        ScriptedEmacs.over(ChannelConnection(channel, "scripted-emacs"))
}
