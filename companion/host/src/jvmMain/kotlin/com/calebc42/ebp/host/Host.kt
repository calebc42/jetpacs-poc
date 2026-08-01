// SPDX-License-Identifier: GPL-3.0-or-later
// RF-2.6: the headless JVM loopback host. CompanionEngine + its default
// Memory stores behind a ServerSocket — the socket pump is DeviceBridge's
// shape (SPEC 5.2 newest-wins accept, one reader thread per connection)
// minus every Android concern: all 14 engine listener hooks stay null and
// the engine is complete without them. Consumers: the ERT live-loopback
// suite (test/ebp-host-test.el), the smoke drivers on 8765, and the
// RF-1c/RF-3/RF-4/RF-6 rungs that dial a Companion without a device.
package com.calebc42.ebp.host

import com.calebc42.ebp.wire.CompanionConfig
import com.calebc42.ebp.wire.CompanionEngine
import com.calebc42.ebp.wire.EbpAuth
import com.calebc42.ebp.wire.SessionState
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** SPEC 9.3 KAT server nonce — what --kat pins nonceSource to. */
private const val KAT_SN = "303132333435363738393a3b3c3d3e3f"

/** The renderer-free profile literal (the app derives its own from the
 * renderer registry, which is :app-only). Mirrors the ERT harness's welcome
 * fixture: eight node types, two builtins, no features — so a test written
 * against the fake sees the same advertisement here. */
private fun hostProfiles(): JsonObject = buildJsonObject {
    putJsonObject("app") {
        put("node_types", JsonArray(listOf(
            "text", "row", "column", "box", "spacer", "divider",
            "button", "text_input").map(::JsonPrimitive)))
        put("builtins", JsonArray(listOf(
            "view.switch", "companion.settings.open").map(::JsonPrimitive)))
        put("features", JsonArray(emptyList()))
    }
}

/** The nine-member limits core, integer-spelled (the engine's readers are
 * strict; a 4194304.0 spelling is a construction-time throw). Values match
 * the :wire test corpus and the ERT harness's welcome fixture. */
private fun hostLimits(): JsonObject = buildJsonObject {
    put("max_frame_bytes", 4_194_304)
    put("max_queued_events", 256)
    put("max_queued_bytes", 8_388_608)
    put("max_event_bytes", 262_144)
    put("max_surfaces", 16)
    put("max_surface_ids", 1024)
    put("max_field_bytes", 65_536)
    put("max_input_state_bytes", 262_144)
    put("max_capture_fields", 64)
}

/**
 * Host configuration. The pairing is the SPEC 9.3 public KAT vector — the
 * same one DeviceBridge ships for smoke scope and the entire test corpus
 * dials. This host is a test/loopback fixture; it is not a deployment
 * surface, and the KAT pairing authenticates nothing by design.
 *
 * deviceReport stays empty: no `trigger_types` means the validator refuses
 * time triggers (so no alarm scheduler exists here), and no `caps` means
 * capability.invoke answers 1001 before any handler question arises.
 */
fun hostConfig(
    kat: Boolean = false,
    capabilities: Set<String> = setOf("theme"),
): CompanionConfig = CompanionConfig(
    serverName = "headless-host",
    serverVersion = "0.1.0",
    pairings = mapOf(
        "101112131415161718191a1b1c1d1e1f" to
            EbpAuth.decodePairingToken("AAECAwQFBgcICQoLDA0ODw")),
    supportedCapabilities = capabilities,
    surfaceProfiles = hostProfiles(),
    limits = hostLimits(),
    nonceSource = if (kat) ({ KAT_SN }) else EbpAuth::generateNonce,
)

/**
 * The loopback listener. Binds eagerly (so [port] is readable before
 * [start]), accepts newest-wins per SPEC 5.2, and runs one engine per
 * connection over the constructor-default Memory stores — a fresh
 * Companion per dial, which is exactly the ERT harness's per-test
 * lifecycle. Nothing here is a thread the engine needs; both threads are
 * pure transport.
 */
class HostServer(private val config: CompanionConfig, requestedPort: Int) {
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress("127.0.0.1", requestedPort))
    }
    val port: Int get() = server.localPort

    @Volatile private var current: Socket? = null
    @Volatile private var running = true

    /** Non-daemon: the accept thread is what keeps a `java -jar` host alive. */
    fun start(): Thread = thread(name = "ebp-host-accept", isDaemon = false) {
        while (running) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            // SPEC 5.2: exactly one live transport — newest wins.
            current?.runCatching { close() }
            current = socket
            thread(name = "ebp-host-conn", isDaemon = true) { serve(socket) }
        }
    }

    fun stop() {
        running = false
        runCatching { server.close() }
        current?.runCatching { close() }
    }

    private fun serve(socket: Socket) {
        val out = socket.getOutputStream()
        // The sink runs on whatever thread emitted. Today only the reader
        // thread emits, but the write is locked anyway: OutputStream.write
        // is not atomic across threads, and a future control channel must
        // not be able to interleave a frame.
        val engine = CompanionEngine(config) { bytes ->
            try {
                synchronized(out) {
                    out.write(bytes)
                    out.flush()
                }
            } catch (_: java.io.IOException) {
                socket.runCatching { close() }
            }
        }
        val input = socket.getInputStream()
        val buffer = ByteArray(8192)
        try {
            while (engine.state != SessionState.CLOSED) {
                val n = input.read(buffer)
                if (n < 0) break
                engine.feed(buffer.copyOf(n))
            }
        } catch (_: Exception) {
            // Taxonomy throws (FrameClose and kin) already emitted their
            // error frame inside feed(); SPEC 6.2 says the session ends.
        } finally {
            runCatching { engine.close("transport closed") }
            socket.runCatching { close() }
        }
    }
}

fun main(args: Array<String>) {
    var port = 8765
    var kat = false
    var capabilities = setOf("theme")
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> port = args[++i].toInt()
            "--kat" -> kat = true
            "--caps" -> capabilities = args[++i].split(',').filter { it.isNotEmpty() }.toSet()
            else -> {
                System.err.println("usage: host [--port N|0] [--kat] [--caps a,b,c]")
                return
            }
        }
        i++
    }
    if (kat) System.err.println(
        "EBP-HOST: --kat pins the server nonce to the SPEC 9.3 public vector; " +
            "the handshake authenticates NOTHING. Test use only.")
    val hostServer = HostServer(hostConfig(kat, capabilities), port)
    // The launcher contract: the ERT suite reads this line to learn an
    // ephemeral port. Printed for fixed ports too — one contract, no modes.
    println("EBP-HOST PORT=${hostServer.port}")
    System.out.flush()
    hostServer.start().join()
}
