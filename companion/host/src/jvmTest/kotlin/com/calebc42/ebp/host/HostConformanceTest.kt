// SPDX-License-Identifier: GPL-3.0-or-later
// RF-2.6 K2: the host pump pinned over a REAL loopback socket — threading,
// flush behavior, and newest-wins independent of Emacs, so an ERT failure
// later bisects to the elisp side. The engine's own conformance lives in
// :wire's suites; this file tests the SOCKET wrapping of it.
package com.calebc42.ebp.host

import com.calebc42.ebp.wire.CompanionEngine
import com.calebc42.ebp.wire.EbpAuth
import com.calebc42.ebp.wire.FrameDecoder
import com.calebc42.ebp.wire.encodeFrame
import com.calebc42.ebp.wire.request
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HostConformanceTest {

    private val katToken = EbpAuth.decodePairingToken("AAECAwQFBgcICQoLDA0ODw")
    private val katPid = "101112131415161718191a1b1c1d1e1f"
    private val katCn = "202122232425262728292a2b2c2d2e2f"
    private val katSn = "303132333435363738393a3b3c3d3e3f"

    /** One dialed connection: send frames, pump replies with a deadline. */
    private class Dial(port: Int) : AutoCloseable {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 500 }
        private val out = socket.getOutputStream()
        private val input = socket.getInputStream()
        private val decoder = FrameDecoder()
        val replies = mutableListOf<JsonObject>()

        fun send(msg: JsonObject) {
            out.write(encodeFrame(msg.toString()))
            out.flush()
        }

        /** Pump until [pred] holds or ~5 s elapse; returns whether it held. */
        fun await(pred: () -> Boolean): Boolean {
            val deadline = System.currentTimeMillis() + 5_000
            val buffer = ByteArray(8192)
            while (!pred() && System.currentTimeMillis() < deadline) {
                val n = try { input.read(buffer) } catch (_: SocketTimeoutException) { continue }
                if (n < 0) break
                decoder.feed(buffer.copyOf(n)) { replies.add(it) }
            }
            return pred()
        }

        fun replyTo(id: String): JsonObject =
            replies.last { it["id"] == JsonPrimitive(id) }

        override fun close() { socket.runCatching { close() } }
    }

    private fun handshakeFrames(dial: Dial) {
        dial.send(request("h1", "session.hello",
            EbpAuth.helloParams("k2-pin", "0.0.1", katPid, katCn, listOf("theme"))))
        assertTrue("hello reply", dial.await { dial.replies.any { it["id"] == JsonPrimitive("h1") } })
        dial.send(request("h2", "auth.response",
            EbpAuth.authParams(katPid, katCn, katSn, katToken)))
        assertTrue("welcome", dial.await { dial.replies.any { it["id"] == JsonPrimitive("h2") } })
        dial.send(request("r1", "queue.replay", JsonObject(emptyMap())))
        assertTrue("replay reply", dial.await { dial.replies.any { it["id"] == JsonPrimitive("r1") } })
        dial.send(request("r2", "session.ready", JsonObject(emptyMap())))
        assertTrue("ready reply", dial.await { dial.replies.any { it["id"] == JsonPrimitive("r2") } })
    }

    @Test
    fun katHandshakeOverRealSocketReachesReady() {
        val server = HostServer(hostConfig(kat = true), 0)
        server.start()
        try {
            Dial(server.port).use { dial ->
                handshakeFrames(dial)

                // The --kat server nonce is the SPEC 9.3 vector.
                val nonce = dial.replyTo("h1").jsonObject["result"]!!
                    .jsonObject["server_nonce"]!!.jsonPrimitive.content
                assertEquals(katSn, nonce)

                // The welcome carries all 8 members the elisp client
                // hard-requires (ebp--welcome-required) — a missing one is
                // a client-side (welcome-incomplete) close.
                val welcome = dial.replyTo("h2").jsonObject["result"]!!.jsonObject
                for (member in listOf("server_proof", "protocol", "server", "granted",
                        "surface_profiles", "surfaces", "queued_events", "limits"))
                    assertNotNull("welcome.$member", welcome[member])

                // KAT byte-exact: the proof over the fixed nonces.
                assertEquals(
                    EbpAuth.serverProof(katToken, katPid, katCn, katSn),
                    welcome["server_proof"]!!.jsonPrimitive.content)
                assertEquals(2L, welcome["protocol"]!!.jsonPrimitive.content.toLong())
                assertEquals(listOf("theme"),
                    welcome["granted"]!!.jsonArray.map { it.jsonPrimitive.content })
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun withoutKatFlagTheNonceIsFreshAndTheHandshakeStillCompletes() {
        val server = HostServer(hostConfig(kat = false), 0)
        server.start()
        try {
            Dial(server.port).use { dial ->
                dial.send(request("h1", "session.hello",
                    EbpAuth.helloParams("k2-pin", "0.0.1", katPid, katCn, listOf("theme"))))
                assertTrue(dial.await { dial.replies.any { it["id"] == JsonPrimitive("h1") } })
                val sn = dial.replyTo("h1").jsonObject["result"]!!
                    .jsonObject["server_nonce"]!!.jsonPrimitive.content
                assertTrue("32-hex nonce", EbpAuth.isValidNonce(sn))
                dial.send(request("h2", "auth.response",
                    EbpAuth.authParams(katPid, katCn, sn, katToken)))
                assertTrue(dial.await { dial.replies.any { it["id"] == JsonPrimitive("h2") } })
                val welcome = dial.replyTo("h2").jsonObject["result"]!!.jsonObject
                assertTrue(EbpAuth.verifyServerProof(
                    welcome["server_proof"]!!.jsonPrimitive.content,
                    katToken, katPid, katCn, sn))
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun newestWinsClosesThePreviousTransportAndTheNewOneHandshakes() {
        val server = HostServer(hostConfig(kat = true), 0)
        server.start()
        try {
            val first = Dial(server.port)
            handshakeFrames(first)
            // SPEC 5.2: exactly one live transport. Dialing again must CLOSE
            // the first — asserted by reading it to EOF, which is the only
            // falsifiable statement available here. (queued_events == 0 on
            // the second welcome is not: with no UI to admit events it is
            // structurally 0 whether or not the stores were fresh, so it
            // would pass with the newest-wins close deleted.)
            Dial(server.port).use { second ->
                handshakeFrames(second)
                val evicted = run {
                    val deadline = System.currentTimeMillis() + 5_000
                    val buf = ByteArray(256)
                    while (System.currentTimeMillis() < deadline) {
                        val n = try { first.socket.getInputStream().read(buf) }
                        catch (_: Exception) { -1 }   // reset counts as closed
                        if (n < 0) return@run true
                    }
                    false
                }
                assertTrue("first transport evicted by the second dial", evicted)
                // The survivor is fully functional, on its own fresh stores.
                val welcome = second.replyTo("h2").jsonObject["result"]!!.jsonObject
                assertEquals(0L,
                    welcome["queued_events"]!!.jsonPrimitive.content.toLong())
            }
            first.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun aConfigThatFailsEngineValidationIsRejectedBeforeAnySocketExists() {
        // The engine validates limits in its constructor, and the host builds
        // one per CONNECTION — so a bad config would otherwise bind, print a
        // port, and then fail every dial. main() constructs a throwaway
        // engine up front for exactly this; the pin is that the throw really
        // happens, so that startup validation has something to catch.
        //
        // The invalidity is injected rather than borrowed from a real flag:
        // this pin previously advertised editor.sync without
        // max_editor_bytes, which stopped being invalid once hostLimits
        // gained that member to make `--caps editor.sync` usable. A pin that
        // depends on a gap dies when the gap is closed.
        val broken = hostConfig().copy(
            limits = JsonObject(hostConfig().limits - "max_frame_bytes"))
        try {
            CompanionEngine(broken) { }
            fail("expected checkLimits to reject limits without max_frame_bytes")
        } catch (e: IllegalArgumentException) {
            assertTrue("names the missing limit: ${e.message}",
                e.message!!.contains("max_frame_bytes"))
        }
    }

    @Test
    fun theHostCanActuallyGrantEveryCapabilityItsCapsFlagAccepts() {
        // --caps advertises an option; the config it produces must be one the
        // engine will accept, or the flag is decorative. editor.sync is the
        // one with a limits precondition, so it is the one worth pinning.
        for (cap in listOf("editor.sync", "triggers", "capabilities",
                "presentation.toast", "reminders.owner")) {
            CompanionEngine(hostConfig(
                capabilities = setOf("theme", "surfaces.dialog", cap))) { }
        }
    }

    // ------------------------------------------------------------- RF-3

    @Test
    fun echoConfigCarriesTheTenantAndTheDefaultCarriesNone() {
        // The same discipline the --caps pin applies: a flag that produces a
        // config the engine refuses is decorative. And the no-flag host must
        // stay module-free — behavior-identical to RF-2.6.
        val withEcho = hostConfig(echo = true)
        assertEquals(listOf("jetpacs.echo"), withEcho.modules.map { it.namespace })
        CompanionEngine(withEcho) { }
        assertTrue("default host carries modules", hostConfig().modules.isEmpty())
    }

    @Test
    fun echoTenantRoundTripsOverARealSocket() {
        // The socket-level half of RF-3 gate (2): granted over a real dial,
        // ping echoes its params in the reply AND the handler's pulse
        // notification rides out after it. (The elisp half is
        // test/ebp-seam-test.el, over this same host with --echo.)
        val server = HostServer(hostConfig(kat = true, echo = true), 0)
        server.start()
        try {
            Dial(server.port).use { dial ->
                dial.send(request("h1", "session.hello", EbpAuth.helloParams(
                    "rf3-pin", "0.0.1", katPid, katCn, listOf("jetpacs.echo"))))
                assertTrue(dial.await { dial.replies.any { it["id"] == JsonPrimitive("h1") } })
                dial.send(request("h2", "auth.response",
                    EbpAuth.authParams(katPid, katCn, katSn, katToken)))
                assertTrue(dial.await { dial.replies.any { it["id"] == JsonPrimitive("h2") } })
                assertEquals(listOf("jetpacs.echo"),
                    dial.replyTo("h2").jsonObject["result"]!!.jsonObject["granted"]!!
                        .jsonArray.map { it.jsonPrimitive.content })
                dial.send(request("r1", "queue.replay", JsonObject(emptyMap())))
                assertTrue(dial.await { dial.replies.any { it["id"] == JsonPrimitive("r1") } })
                dial.send(request("r2", "session.ready", JsonObject(emptyMap())))
                assertTrue(dial.await { dial.replies.any { it["id"] == JsonPrimitive("r2") } })

                dial.send(request("p1", "jetpacs.echo.ping",
                    buildJsonObject { put("payload", "live") }))
                assertTrue("reply and pulse arrive", dial.await {
                    dial.replies.any { it["id"] == JsonPrimitive("p1") } &&
                        dial.replies.any {
                            it["method"] == JsonPrimitive("jetpacs.echo.pulse")
                        }
                })
                val reply = dial.replyTo("p1").jsonObject["result"]!!.jsonObject
                assertEquals("live", reply["payload"]!!.jsonPrimitive.content)
                val pulse = dial.replies.first {
                    it["method"] == JsonPrimitive("jetpacs.echo.pulse")
                }
                assertEquals("live", pulse.jsonObject["params"]!!
                    .jsonObject["payload"]!!.jsonPrimitive.content)
                assertTrue("pulse before reply",
                    dial.replies.indexOf(dial.replyTo("p1")) < dial.replies.indexOf(pulse))
            }
        } finally {
            server.stop()
        }
    }
}
