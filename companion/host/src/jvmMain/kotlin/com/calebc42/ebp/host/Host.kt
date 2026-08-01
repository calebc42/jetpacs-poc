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
import com.calebc42.ebp.wire.EbpModule
import com.calebc42.ebp.wire.MethodSpec
import com.calebc42.ebp.wire.ModuleHandler
import com.calebc42.ebp.wire.ModuleNotification
import com.calebc42.ebp.wire.ModuleOutcome
import com.calebc42.ebp.wire.Sender
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

private val HOST_NODE_TYPES = listOf(
    "text", "row", "column", "box", "spacer", "divider", "button", "text_input")

/** The renderer-free profile literal (the app derives its own from the
 * renderer registry, which is :app-only). Mirrors the ERT harness's welcome
 * fixture — eight node types, two builtins, no features — so a test written
 * against the fake sees the same advertisement here.
 *
 * The `dialog` target is advertised because the host grants
 * `surfaces.dialog`: SPEC 10.2's profiles are POSITIVE knowledge, and
 * `handleDialogShow` validates a dialog spec against
 * `surface_profiles.dialog.node_types`. Granting the capability while
 * advertising no dialog profile left dialog content un-gated — the client
 * was told "I show dialogs" and nothing about what may go in one. */
private fun hostProfiles(): JsonObject = buildJsonObject {
    putJsonObject("app") {
        put("node_types", JsonArray(HOST_NODE_TYPES.map(::JsonPrimitive)))
        put("builtins", JsonArray(listOf(
            "view.switch", "companion.settings.open").map(::JsonPrimitive)))
        put("features", JsonArray(emptyList()))
    }
    putJsonObject("dialog") {
        put("node_types", JsonArray(HOST_NODE_TYPES.map(::JsonPrimitive)))
        put("builtins", JsonArray(emptyList()))
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
    // A tenth member, not part of that core, so that `--caps editor.sync`
    // is actually takeable: the engine requires this (>= 65536) the moment
    // editor.sync is advertised, so without it the flag made the host
    // refuse to start — an advertised option that could not be used.
    // Inert when editor.sync is not granted.
    put("max_editor_bytes", 262_144)
}

/**
 * RF-3 (PLAN-rf3-seam.md): the trivial tenant that proves the extension
 * seam. One `ping` request (Emacs→Companion, echoes its params) whose
 * handler asks the engine to emit one `pulse` notification
 * (Companion→Emacs, same payload) — a single round trip exercises both
 * message classes through both ends of the seam. "Lives in test code
 * only": this host is the test/loopback fixture, the tenant rides it
 * only behind `--echo` (default off), and the no-flag host is
 * behavior-identical to RF-2.6. ExtensionSeamTest's echoModule() in
 * :wire jvmTest mirrors this shape exactly.
 */
fun jetpacsEchoModule(): EbpModule = EbpModule(
    namespace = "jetpacs.echo",
    capability = "jetpacs.echo",
    methods = mapOf(
        "jetpacs.echo.ping" to
            MethodSpec(Sender.EMACS, true, setOf(SessionState.READY)),
        "jetpacs.echo.pulse" to
            MethodSpec(Sender.COMPANION, false, setOf(SessionState.READY)),
    ),
    handler = ModuleHandler { _, params ->
        ModuleOutcome.Ok(params,
            listOf(ModuleNotification("jetpacs.echo.pulse", params)))
    },
)

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
    // What a headless Companion can honestly honor at the protocol level.
    // surfaces.dialog belongs here: the engine accepts dialog.show and
    // holds it outstanding per SPEC 18.1 — and with no user to act, it
    // holds it forever, which is exactly what the sender-ceiling gate
    // needs. A client is granted wants-intersect-supported, so asking
    // for less still grants less.
    capabilities: Set<String> = setOf("theme", "surfaces.dialog"),
    // RF-3: carry the jetpacs.echo tenant. Off by default so the no-flag
    // host stays behavior-identical to RF-2.6.
    echo: Boolean = false,
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
    modules = if (echo) listOf(jetpacsEchoModule()) else emptyList(),
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
        // EVERYTHING is inside the try, construction included. Getting the
        // streams can throw when newest-wins closed this socket a moment
        // ago, and the engine constructor throws on any limits/profile
        // inconsistency (checkLimits) — with either outside, the only
        // cleanup path is skipped, the thread dies with an uncaught
        // exception, and the accepted socket is stranded with no reader and
        // no writer. Measured before the fix: 290 uncaught traces over 300
        // sequential dials on the DEFAULT config.
        var engine: CompanionEngine? = null
        try {
            val out = socket.getOutputStream()
            // The sink runs on whatever thread emitted. Today only the
            // reader thread emits, but the write is locked anyway:
            // OutputStream.write is not atomic across threads, and a future
            // control channel must not be able to interleave a frame.
            engine = CompanionEngine(config) { bytes ->
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
            while (engine.state != SessionState.CLOSED) {
                val n = input.read(buffer)
                if (n < 0) break
                engine.feed(buffer.copyOf(n))
            }
        } catch (e: Exception) {
            // Taxonomy throws (FrameClose and kin) already emitted their
            // error frame inside feed(); SPEC 6.2 says the session ends.
            // A setup failure on a socket WE closed is the newest-wins race
            // — this connection was superseded before its thread got going,
            // which is the design working. Anything else killed the session
            // before it could answer, and the client only sees a
            // disconnect, so name it.
            if (engine == null && !socket.isClosed)
                System.err.println("EBP-HOST: connection setup failed: $e")
        } finally {
            engine?.runCatching { close("transport closed") }
            socket.runCatching { close() }
        }
    }
}

private const val USAGE =
    "usage: host [--port N|0] [--kat] [--caps a,b,c] [--echo]\n" +
        "  --port  listen port; 0 picks an ephemeral one (default 8765)\n" +
        "  --kat   pin the server nonce to the SPEC 9.3 public vector\n" +
        "  --caps  EXTRA capabilities, added to theme + surfaces.dialog\n" +
        "  --echo  carry the jetpacs.echo test tenant (RF-3)"

fun main(args: Array<String>) {
    var port = 8765
    var kat = false
    var echo = false
    var extraCaps = emptySet<String>()
    var i = 0
    fun operand(flag: String): String? =
        if (i + 1 < args.size) args[++i] else {
            System.err.println("EBP-HOST: $flag needs a value\n$USAGE"); null
        }
    while (i < args.size) {
        when (val arg = args[i]) {
            "--port" -> {
                val v = operand(arg) ?: return
                port = v.toIntOrNull() ?: run {
                    System.err.println("EBP-HOST: --port takes a number, got '$v'")
                    return
                }
            }
            "--kat" -> kat = true
            "--echo" -> echo = true
            // Extends, never replaces: the defaults are what the tests and
            // smoke drivers assume, and a caller asking for one more
            // capability should not silently lose them.
            "--caps" -> extraCaps =
                (operand(arg) ?: return).split(',').filter { it.isNotEmpty() }.toSet()
            else -> {
                System.err.println("EBP-HOST: unknown argument '$arg'\n$USAGE")
                return
            }
        }
        i++
    }
    val capabilities = if (extraCaps.isEmpty()) null else hostConfig().supportedCapabilities + extraCaps
    if (kat) System.err.println(
        "EBP-HOST: --kat pins the server nonce to the SPEC 9.3 public vector; " +
            "the handshake authenticates NOTHING. Test use only.")
    val config = capabilities?.let { hostConfig(kat, it, echo) }
        ?: hostConfig(kat, echo = echo)
    // The engine validates limits against the advertised capabilities in its
    // constructor — and it is constructed per CONNECTION, so without this a
    // bad --caps would bind, print a port, and then fail every dial while
    // the client saw only an immediate disconnect. Refuse to start instead.
    try {
        CompanionEngine(config) { }
    } catch (e: Exception) {
        System.err.println("EBP-HOST: refusing to start — $e")
        kotlin.system.exitProcess(2)
    }
    val hostServer = HostServer(config, port)
    // The launcher contract: the ERT suite reads this line to learn an
    // ephemeral port. Printed for fixed ports too — one contract, no modes.
    println("EBP-HOST PORT=${hostServer.port}")
    System.out.flush()
    hostServer.start().join()
}
