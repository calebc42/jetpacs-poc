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
import com.calebc42.jetpacs.material3.SnackbarRaises
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * A minimal Emacs for the desktop tests: frames in, frames out, in order,
 * over whatever streams the transport under test provides. It dials the
 * loopback listener ([dial]) or is the binder a dialing Companion reaches
 * ([accept]).
 */
class ScriptedEmacs private constructor(
    private val input: InputStream,
    private val output: OutputStream,
    private val closer: AutoCloseable,
) : AutoCloseable {
    private val frames = LinkedBlockingQueue<JsonObject>()

    init {
        thread(isDaemon = true, name = "scripted-emacs") {
            val decoder = FrameDecoder()
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    decoder.feed(buffer, 0, read) { frames.add(it) }
                }
            } catch (_: Exception) {
                // The connection closed with the Companion.
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
        closer.runCatching { close() }
    }

    companion object {
        /** SPEC 5.2: Emacs dials the Companion's loopback listener. */
        fun dial(port: Int): ScriptedEmacs {
            val socket = Socket("127.0.0.1", port)
            return ScriptedEmacs(socket.getInputStream(), socket.getOutputStream(), socket)
        }

        /** A scripted Emacs over an already-accepted [connection]. */
        fun over(connection: ChannelConnection): ScriptedEmacs =
            ScriptedEmacs(connection.input, connection.output, connection)

        /** SPEC 5.6: Emacs is the binder; the Companion's dial arrives on [server]. */
        fun accept(server: ServerSocketChannel, timeoutSeconds: Long = 10): ScriptedEmacs {
            val accepted = CompletableFuture<java.nio.channels.SocketChannel>()
            thread(isDaemon = true, name = "scripted-emacs-accept") {
                runCatching { server.accept() }.onSuccess(accepted::complete).onFailure(accepted::completeExceptionally)
            }
            return over(ChannelConnection(accepted.get(timeoutSeconds, TimeUnit.SECONDS), "scripted-emacs"))
        }
    }
}

/** The SPEC 9 handshake up to READY on [emacs] against [companion]. */
suspend fun ScriptedEmacs.handshakeToReady(
    companion: DesktopCompanion,
    token: ByteArray,
    wants: List<String> = listOf("theme", "presentation.snackbar", "surfaces.dialog", "offline.wake"),
): JsonObject {
    val pairingId = companion.pairing.pairingId.value
    val clientNonce = EbpAuth.generateNonce()
    send(request("h1", "session.hello", EbpAuth.helloParams("desktop-test", "1.0.0", pairingId, clientNonce, wants)))
    val serverNonce = awaitReply("h1").result().string("server_nonce")
    send(request("h2", "auth.response", EbpAuth.authParams(pairingId, clientNonce, serverNonce, token)))
    val welcome = awaitReply("h2").result()
    send(request("r1", "session.ready", JsonObject(emptyMap())))
    awaitReply("r1")
    return welcome
}

/**
 * The D5 script: pair, check the desktop's welcome, push a surface that
 * reaches the presentation, answer an action the bridge dispatched, raise a
 * snackbar. Shared by the loopback and the emacs-unix suites.
 */
suspend fun ScriptedEmacs.runPairingScript(companion: DesktopCompanion, token: ByteArray) {
    val welcome = handshakeToReady(companion, token)
    val granted = welcome.getValue("granted").jsonArray.map { it.jsonPrimitive.content }
    assertTrue(granted.toString(), granted.containsAll(listOf("theme", "presentation.snackbar", "surfaces.dialog")))
    assertFalse("SPEC 5.8: a desktop never grants offline.wake", "offline.wake" in granted)
    assertEquals(setOf("app", "dialog"), welcome.getValue("surface_profiles").jsonObject.keys)

    send(
        request(
            "s1", "surface.update",
            buildJsonObject {
                put("surface", "app:m3catalog")
                put("revision", 1)
                put("spec", buildJsonObject { put("t", "text"); put("text", "Material catalog") })
            },
        ),
    )
    assertEquals("applied", awaitReply("s1").result().string("status"))
    val shown = withTimeout(10_000) {
        companion.process.presentation.appSurface("app:m3catalog").first { it != null }
    }!!
    assertEquals("Material catalog", shown.string("text"))

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
    val event = await { (it["method"] as? JsonPrimitive)?.content == "event.action" }
    val params = event.getValue("params").jsonObject
    assertEquals("m3catalog.demo", params.string("action"))
    assertEquals("app:m3catalog", params.string("surface"))
    assertEquals("hi", params.getValue("args").jsonObject.string("message"))
    send(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", event.getValue("id"))
            put("result", buildJsonObject { put("status", "accepted") })
        },
    )
    val delivered = outcome.get(10, TimeUnit.SECONDS)
    assertTrue(delivered.toString(), delivered is RendererActionOutcome.SafelyAdmitted)
    assertEquals(SafeAdmissionEvidence.RemoteAccepted, (delivered as RendererActionOutcome.SafelyAdmitted).evidence)

    SnackbarRaises.flow.value = null
    send(request("sb1", "snackbar.show", buildJsonObject { put("message", "Hello from Emacs") }))
    withTimeout(10_000) { SnackbarRaises.flow.first { it != null } }
}

internal fun JsonObject.result(): JsonObject = getValue("result").jsonObject
internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
