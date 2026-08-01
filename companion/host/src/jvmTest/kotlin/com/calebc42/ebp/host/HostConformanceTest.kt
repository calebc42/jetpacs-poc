// SPDX-License-Identifier: GPL-3.0-or-later
// RF-2.6 K2: the host pump pinned over a REAL loopback socket — threading,
// flush behavior, and newest-wins independent of Emacs, so an ERT failure
// later bisects to the elisp side. The engine's own conformance lives in
// :wire's suites; this file tests the SOCKET wrapping of it.
package com.calebc42.ebp.host

import com.calebc42.ebp.wire.EbpAuth
import com.calebc42.ebp.wire.FrameDecoder
import com.calebc42.ebp.wire.encodeFrame
import com.calebc42.ebp.wire.request
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    fun newestWinsASecondDialCompletesItsOwnHandshake() {
        val server = HostServer(hostConfig(kat = true), 0)
        server.start()
        try {
            val first = Dial(server.port)
            handshakeFrames(first)
            // Dial again: SPEC 5.2 — the previous transport is closed, the
            // new one gets a fresh engine (fresh Memory stores) and must
            // reach READY on its own.
            Dial(server.port).use { second ->
                handshakeFrames(second)
                val welcome = second.replyTo("h2").jsonObject["result"]!!.jsonObject
                assertEquals(0L,
                    welcome["queued_events"]!!.jsonPrimitive.content.toLong())
            }
            first.close()
        } finally {
            server.stop()
        }
    }
}
