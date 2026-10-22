// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import com.calebc42.ebp.renderer.model.RendererActionContext
import com.calebc42.ebp.renderer.model.RendererActionOutcome
import com.calebc42.ebp.renderer.model.RendererActionRequest
import com.calebc42.ebp.wire.EbpAuth
import com.calebc42.ebp.wire.FrameDecoder
import com.calebc42.ebp.wire.SafeAdmissionEvidence
import com.calebc42.ebp.wire.encodeFrame
import com.calebc42.ebp.wire.request
import com.calebc42.jetpacs.companion.host.TransportStatus
import com.calebc42.jetpacs.core.ebpstore.PrivateFileSecretStore
import com.calebc42.jetpacs.material3.SnackbarRaises
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D5 end to end without a window: a scripted Emacs dials the desktop
 * Companion's loopback listener, pairs with the generated token, receives
 * the welcome the desktop advertises, pushes a surface that reaches the
 * presentation, answers an action the bridge dispatched, and raises a
 * snackbar. Then the Companion reopens on the same directory with the same
 * pairing and no token to show.
 */
class DesktopCompanionTest {
    private val root: Path = Files.createTempDirectory("jetpacs-desktop-e2e-")
    private val data: Path = root.resolve("data")
    private val direct = Executor { it.run() }

    @After
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun store() = PrivateFileSecretStore(data.resolve(PrivateFileSecretStore.FILE_NAME))

    @Test
    fun aScriptedEmacsPairsPushesASurfaceAndRoundTripsAnAction() = runBlocking {
        val config = DesktopConfig(data, port = 0)
        val companion = DesktopCompanion.create(config, direct, store())
        val pairingId = companion.pairing.pairingId.value
        try {
            assertTrue(companion.start())
            val port = (withTimeout(10_000) { companion.transportStatus.first { it is TransportStatus.Listening } } as TransportStatus.Listening).port
            val token = EbpAuth.decodePairingToken(companion.pairing.freshTokenDisplay!!)
            ScriptedEmacs(port).use { emacs ->
                val clientNonce = EbpAuth.generateNonce()
                emacs.send(
                    request(
                        "h1", "session.hello",
                        EbpAuth.helloParams(
                            "desktop-test", "1.0.0", pairingId, clientNonce,
                            listOf("theme", "presentation.snackbar", "surfaces.dialog", "offline.wake"),
                        ),
                    ),
                )
                val serverNonce = emacs.awaitReply("h1").result().string("server_nonce")
                emacs.send(request("h2", "auth.response", EbpAuth.authParams(pairingId, clientNonce, serverNonce, token)))
                val welcome = emacs.awaitReply("h2").result()
                val granted = welcome.getValue("granted").jsonArray.map { it.jsonPrimitive.content }
                assertTrue(granted.toString(), granted.containsAll(listOf("theme", "presentation.snackbar", "surfaces.dialog")))
                assertFalse("SPEC 5.8: a desktop never grants offline.wake", "offline.wake" in granted)
                assertEquals(setOf("app", "dialog"), welcome.getValue("surface_profiles").jsonObject.keys)
                emacs.send(request("r1", "session.ready", JsonObject(emptyMap())))
                emacs.awaitReply("r1")

                emacs.send(
                    request(
                        "s1", "surface.update",
                        buildJsonObject {
                            put("surface", "app:m3catalog")
                            put("revision", 1)
                            put("spec", buildJsonObject { put("t", "text"); put("text", "Material catalog") })
                        },
                    ),
                )
                assertEquals("applied", emacs.awaitReply("s1").result().string("status"))
                val shown = withTimeout(10_000) {
                    companion.process.presentation.appSurface("app:m3catalog").first { it != null }
                }!!
                assertEquals("Material catalog", shown.string("text"))

                // The action round trip: the bridge dispatches, Emacs accepts.
                val outcome = CompletableFuture<RendererActionOutcome>()
                companion.bridge.dispatch(
                    RendererActionRequest(
                        context = RendererActionContext.Surface("app:m3catalog"),
                        descriptor = buildJsonObject {
                            put("action", "m3catalog.demo")
                            put("args", buildJsonObject { put("message", "hi") })
                        },
                    ),
                ) { outcome.complete(it) }
                val event = emacs.await { it.method() == "event.action" }
                val params = event.getValue("params").jsonObject
                assertEquals("m3catalog.demo", params.string("action"))
                assertEquals("app:m3catalog", params.string("surface"))
                assertEquals("hi", params.getValue("args").jsonObject.string("message"))
                emacs.send(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", event.getValue("id"))
                        put("result", buildJsonObject { put("status", "accepted") })
                    },
                )
                val delivered = outcome.get(10, TimeUnit.SECONDS)
                assertTrue(delivered.toString(), delivered is RendererActionOutcome.SafelyAdmitted)
                assertEquals(SafeAdmissionEvidence.RemoteAccepted, (delivered as RendererActionOutcome.SafelyAdmitted).evidence)

                // SPEC 18.2.1: the snackbar reaches the window's scaffold host.
                SnackbarRaises.flow.value = null
                emacs.send(request("sb1", "snackbar.show", buildJsonObject { put("message", "Hello from Emacs") }))
                withTimeout(10_000) { SnackbarRaises.flow.first { it != null } }
            }
        } finally {
            companion.shutdown()
        }
        assertFalse(companion.transportStatus.value.isActive)

        val again = DesktopCompanion.create(config, direct, store())
        assertEquals(pairingId, again.pairing.pairingId.value)
        assertNull(again.pairing.freshTokenDisplay)
        again.shutdown()
    }

    private fun JsonObject.result(): JsonObject = getValue("result").jsonObject
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.method(): String? = (this["method"] as? JsonPrimitive)?.content

    /** A minimal Emacs: frames in, frames out, in order. */
    private class ScriptedEmacs(port: Int) : AutoCloseable {
        private val socket = Socket("127.0.0.1", port)
        private val output = socket.getOutputStream()
        private val frames = LinkedBlockingQueue<JsonObject>()

        init {
            thread(isDaemon = true, name = "scripted-emacs") {
                val decoder = FrameDecoder()
                val buffer = ByteArray(8192)
                val input = socket.getInputStream()
                try {
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        decoder.feed(buffer, 0, read) { frames.add(it) }
                    }
                } catch (_: Exception) {
                    // The socket closed with the Companion.
                }
            }
        }

        fun send(frame: JsonObject) {
            output.write(encodeFrame(frame.toString()))
            output.flush()
        }

        fun await(timeoutSeconds: Long = 10, predicate: (JsonObject) -> Boolean): JsonObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (true) {
                val remaining = deadline - System.nanoTime()
                val frame = frames.poll(remaining.coerceAtLeast(1), TimeUnit.NANOSECONDS)
                    ?: throw AssertionError("no matching frame within $timeoutSeconds s")
                if (predicate(frame)) return frame
            }
        }

        fun awaitReply(id: String): JsonObject =
            await { (it["id"] as? JsonPrimitive)?.content == id }.also { reply ->
                assertNull("error reply for $id: ${reply["error"]}", reply["error"])
            }

        override fun close() {
            socket.close()
        }
    }
}

@Suppress("unused")
private fun JsonElement.describe(): String = toString()
