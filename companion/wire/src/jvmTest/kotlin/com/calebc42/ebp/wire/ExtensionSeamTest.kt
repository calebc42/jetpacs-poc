// SPDX-License-Identifier: GPL-3.0-or-later
// RF-3: the extension-dispatch seam (PLAN-rf3-seam.md). Gate (3)'s golden
// lives here as a checked-in expectation — ebp/ is frozen (I1) and
// validate.py rejects frames.golden methods absent from the contract — and
// is compared semantically per SPEC 24.5 via jsonValueEquals. Capture
// discipline: the SEVEN_THREE_REPLY fixture and its module-free test ran
// GREEN against the pre-seam tree (rf-3 @ f63ab14, base rf-1 @ 71d90ab)
// before any seam code landed; the fixture is evidence, not aspiration.
// Probes use OBJECT params on purpose: non-object params answer -32602
// before the registry is consulted (CompanionEngine.kt:593-597), which is
// not the shape this gate pins. Neutral x.test namespaces exercise seam
// mechanics; jetpacs.echo appears only where fidelity to the live tenant
// matters (the tenant itself lives in :host behind --echo and in test code
// — never in :wire main sources, per I2).
package com.calebc42.ebp.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExtensionSeamTest {

    private fun profiles(): JsonObject = buildJsonObject {
        putJsonObject("app") {
            put("node_types", JsonArray(listOf(
                "text", "row", "column", "box", "spacer", "divider",
                "button", "text_input").map(::JsonPrimitive)))
            put("builtins", JsonArray(listOf(
                "view.switch", "companion.settings.open").map(::JsonPrimitive)))
            put("features", JsonArray(emptyList()))
        }
    }

    private fun config(supported: Set<String> = setOf("theme"),
                       limits: JsonObject = testLimits(),
                       modules: List<EbpModule> = emptyList()): CompanionConfig =
        CompanionConfig(
            serverName = "kat-companion", serverVersion = "1.0.0",
            pairings = mapOf(katPid to katToken),
            supportedCapabilities = supported,
            surfaceProfiles = profiles(),
            limits = limits,
            nonceSource = { katSn },
            modules = modules,
        )

    private fun engine(sink: MutableList<JsonObject>,
                       modules: List<EbpModule> = emptyList()): CompanionEngine =
        CompanionEngine(config(modules = modules)) { bytes ->
            FrameDecoder().let { d -> d.feed(bytes).forEach(sink::add); d.finish() }
        }

    private fun handshake(engine: CompanionEngine, wants: List<String>) {
        engine.feed(frame(request("h1", "session.hello", EbpAuth.helloParams(
            "test-client", "0.0.1", katPid, katCn, wants))))
        engine.feed(frame(request("h2", "auth.response",
            EbpAuth.authParams(katPid, katCn, katSn, katToken))))
    }

    private fun ready(engine: CompanionEngine, wants: List<String> = listOf("theme")) {
        handshake(engine, wants)
        engine.feed(frame(request("q1", "queue.replay", JsonObject(emptyMap()))))
        engine.feed(frame(request("r1", "session.ready", JsonObject(emptyMap()))))
        check(engine.state == SessionState.READY) { "handshake did not reach READY" }
    }

    /** The live tenant's exact shape (K2 mirrors this in Host.kt). */
    private fun echoModule(): EbpModule = EbpModule(
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

    // ------------------------------------------------- gate (3): the golden

    /** The exact §7.3 unknown-method reply, captured from the pre-seam tree
     * (READY, object params, id "g1"). SPEC 24.5: compared semantically —
     * member order and number spelling are not part of the pin. */
    private val sevenThreeReply: JsonObject = Json.parseToJsonElement(
        """{"jsonrpc":"2.0","id":"g1","error":{"code":-32601,""" +
            """"message":"Method not found","data":{"kind":"method-not-found"}}}"""
    ) as JsonObject

    private fun pingFrame(): ByteArray =
        frame(request("g1", "jetpacs.echo.ping", buildJsonObject { put("payload", "x") }))

    @Test
    fun unnegotiatedTenantRequestIsTheSevenThreeAnswer() {
        // Module-free engine: the reply must equal the pre-seam capture.
        val out = mutableListOf<JsonObject>()
        val plain = engine(out)
        ready(plain)
        plain.feed(pingFrame())
        assertTrue("module-free reply drifted from the pre-seam capture",
            jsonValueEquals(sevenThreeReply, out.last()))
        assertEquals(SessionState.READY, plain.state)

        // The frozen-dispatch proof: an engine CARRYING the module but never
        // wanting its capability answers identically — registration alone
        // changes nothing on the wire (I5).
        val out2 = mutableListOf<JsonObject>()
        val carrying = engine(out2, modules = listOf(echoModule()))
        ready(carrying) // wants = ["theme"], never "jetpacs.echo"
        carrying.feed(pingFrame())
        assertTrue("registered-but-unwanted reply drifted from the capture",
            jsonValueEquals(sevenThreeReply, out2.last()))
        assertTrue("the two replies disagree",
            jsonValueEquals(out.last(), out2.last()))
        assertEquals(SessionState.READY, carrying.state)
    }

    @Test
    fun unnegotiatedTenantNotificationIsSilent() {
        // §7.3's notification arm: dropped, nothing emitted — with and
        // without the module registered.
        for (modules in listOf(emptyList(), listOf(echoModule()))) {
            val out = mutableListOf<JsonObject>()
            val e = engine(out, modules = modules)
            ready(e)
            val before = out.size
            e.feed(frame(notification("jetpacs.echo.pulse",
                buildJsonObject { put("payload", "x") })))
            assertEquals("notification emitted output (modules=${modules.size})",
                before, out.size)
            assertEquals(SessionState.READY, e.state)
        }
    }

    // --------------------------------------------- registration validation

    private fun assertRejected(fragment: String, vararg modules: EbpModule) {
        try {
            CompanionEngine(config(modules = modules.toList())) { }
            fail("accepted a module config that should fail: $fragment")
        } catch (e: IllegalArgumentException) {
            assertTrue("message '${e.message}' lacks '$fragment'",
                e.message!!.contains(fragment))
        }
    }

    private fun module(namespace: String = "x.test",
                       capability: String = "x.test",
                       methods: Map<String, MethodSpec> = mapOf(
                           "$namespace.m" to
                               MethodSpec(Sender.EMACS, true, setOf(SessionState.READY)))
    ): EbpModule = EbpModule(namespace, capability, methods,
        ModuleHandler { _, p -> ModuleOutcome.Ok(p) })

    @Test
    fun registrationValidationRejectsEachMalformation() {
        assertRejected("lowercase", module(namespace = "X.test", capability = "x.test",
            methods = mapOf("X.test.m" to
                MethodSpec(Sender.EMACS, true, setOf(SessionState.READY)))))
        assertRejected("lowercase", module(namespace = ".bad"))
        assertRejected("reserved", module(namespace = "ebp"))
        assertRejected("reserved", module(namespace = "ebp.data"))
        assertRejected("duplicates or nests", module(), module(capability = "x.other"))
        assertRejected("duplicates or nests",
            module(), module(namespace = "x.test.sub", capability = "x.sub",
                methods = mapOf("x.test.sub.m" to
                    MethodSpec(Sender.EMACS, true, setOf(SessionState.READY)))))
        assertRejected("supported core capability", module(capability = "theme"))
        assertRejected("claims the spec's namespace", module(capability = "ebp.cap"))
        assertRejected("another module's",
            module(), module(namespace = "y.test", capability = "x.test",
                methods = mapOf("y.test.m" to
                    MethodSpec(Sender.EMACS, true, setOf(SessionState.READY)))))
        assertRejected("method table is empty", module(methods = emptyMap()))
        assertRejected("outside the module's namespace",
            module(methods = mapOf("y.other.m" to
                MethodSpec(Sender.EMACS, true, setOf(SessionState.READY)))))
        assertRejected("SPEC 11 registry",
            module(namespace = "theme", capability = "x.theme",
                methods = mapOf("theme.set" to
                    MethodSpec(Sender.EMACS, false, setOf(SessionState.READY)))))
        assertRejected("no legal states",
            module(methods = mapOf("x.test.m" to
                MethodSpec(Sender.EMACS, true, emptySet()))))
    }

    // ------------------------------------------------------- negotiation

    @Test
    fun moduleCapabilityNegotiatesLikeACoreCapability() {
        // Wanted + registered -> granted, alongside the core grant.
        val out = mutableListOf<JsonObject>()
        val e = engine(out, modules = listOf(echoModule()))
        handshake(e, wants = listOf("theme", "jetpacs.echo"))
        val welcome = out.last().reqObj("result")
        assertEquals(listOf("theme", "jetpacs.echo"),
            welcome.reqArr("granted").map { it.asStringOrNull() })

        // Not wanted -> not granted; unknown wants stay silently omitted.
        val out2 = mutableListOf<JsonObject>()
        val e2 = engine(out2, modules = listOf(echoModule()))
        handshake(e2, wants = listOf("theme", "no.such.cap"))
        assertEquals(listOf("theme"),
            out2.last().reqObj("result").reqArr("granted").map { it.asStringOrNull() })
    }

    // ------------------------------------------------- the negotiated route

    @Test
    fun grantedTenantRequestEchoesThenPulses() {
        val out = mutableListOf<JsonObject>()
        val e = engine(out, modules = listOf(echoModule()))
        ready(e, wants = listOf("jetpacs.echo"))
        val payload = buildJsonObject { put("payload", "x"); put("n", 7) }
        e.feed(frame(request("p1", "jetpacs.echo.ping", payload)))
        // Sink order pinned: the reply concludes the request BEFORE the
        // handler's notification rides out.
        val reply = out.first { it["id"] == JsonPrimitive("p1") }
        assertTrue("result does not echo params",
            jsonValueEquals(payload, reply.reqObj("result")))
        val pulse = out.first { it.stringOrNull("method") == "jetpacs.echo.pulse" }
        assertTrue("pulse does not carry the payload",
            jsonValueEquals(payload, pulse.reqObj("params")))
        assertTrue("pulse emitted before the reply",
            out.indexOf(reply) < out.indexOf(pulse))
    }

    @Test
    fun grantedTenantMethodKeepsTheCoreTaxonomyInsideTheRoute() {
        // Notification-class method sent as a request -> -32600 (the core's
        // wrong-class answer), only INSIDE the negotiated route.
        val out = mutableListOf<JsonObject>()
        val e = engine(out, modules = listOf(echoModule()))
        ready(e, wants = listOf("jetpacs.echo"))
        e.feed(frame(request("c1", "jetpacs.echo.pulse", JsonObject(emptyMap()))))
        assertEquals(-32600L, out.errorOf("c1").reqLong("code"))
        // Request-class method arriving as a notification -> silent.
        val before = out.size
        e.feed(frame(notification("jetpacs.echo.ping", JsonObject(emptyMap()))))
        assertEquals(before, out.size)

        // READY-only tenant request during SYNCING -> 1204, exactly the
        // core's wrong-state answer — but only when granted.
        val out2 = mutableListOf<JsonObject>()
        val e2 = engine(out2, modules = listOf(echoModule()))
        handshake(e2, wants = listOf("jetpacs.echo"))
        assertEquals(SessionState.SYNCING, e2.state)
        e2.feed(frame(request("s1", "jetpacs.echo.ping",
            buildJsonObject { put("payload", "x") })))
        assertEquals(1204L, out2.errorOf("s1").reqLong("code"))
    }

    @Test
    fun handlerFaultsAreContained() {
        val throwing = EbpModule("x.test", "x.test", mapOf(
            "x.test.invalid" to MethodSpec(Sender.EMACS, true, setOf(SessionState.READY)),
            "x.test.crash" to MethodSpec(Sender.EMACS, true, setOf(SessionState.READY)),
            "x.test.fail" to MethodSpec(Sender.EMACS, true, setOf(SessionState.READY))),
            ModuleHandler { m, _ ->
                when (m) {
                    "x.test.invalid" -> throw ContentInvalid("payload", "not a thing")
                    "x.test.crash" -> throw IllegalStateException("tenant bug")
                    else -> ModuleOutcome.Fail(1201, "Invalid content",
                        "content-invalid", buildJsonObject { put("reason", "refused") })
                }
            })
        val out = mutableListOf<JsonObject>()
        val e = engine(out, modules = listOf(throwing))
        ready(e, wants = listOf("x.test"))

        // ContentInvalid -> 1201 with path/reason, the core's own mapping.
        e.feed(frame(request("f1", "x.test.invalid", JsonObject(emptyMap()))))
        val err1 = out.errorOf("f1")
        assertEquals(1201L, err1.reqLong("code"))
        assertEquals("payload", err1.reqObj("data").reqString("path"))
        // Any other throw -> -32603; the request concludes, the session lives.
        e.feed(frame(request("f2", "x.test.crash", JsonObject(emptyMap()))))
        assertEquals(-32603L, out.errorOf("f2").reqLong("code"))
        // A typed Fail outcome passes through §8-shaped.
        e.feed(frame(request("f3", "x.test.fail", JsonObject(emptyMap()))))
        val err3 = out.errorOf("f3")
        assertEquals(1201L, err3.reqLong("code"))
        assertEquals("refused", err3.reqObj("data").reqString("reason"))
        assertEquals("content-invalid", err3.reqObj("data").reqString("kind"))
        assertEquals(SessionState.READY, e.state)
    }

    @Test
    fun inboundTenantNotificationDispatchesOnlyWhenLegal() {
        val seen = mutableListOf<JsonObject>()
        val notes = EbpModule("x.test", "x.test", mapOf(
            "x.test.note" to MethodSpec(Sender.EMACS, false, setOf(SessionState.READY))),
            ModuleHandler { _, p -> seen.add(p); ModuleOutcome.Ok(JsonObject(emptyMap())) })
        val payload = buildJsonObject { put("k", "v") }

        // Granted + READY -> the handler sees it.
        val out = mutableListOf<JsonObject>()
        val e = engine(out, modules = listOf(notes))
        ready(e, wants = listOf("x.test"))
        e.feed(frame(notification("x.test.note", payload)))
        assertEquals(1, seen.size)
        assertTrue(jsonValueEquals(payload, seen.last()))

        // Wrong state (SYNCING) -> dropped silently.
        seen.clear()
        val e2 = engine(mutableListOf(), modules = listOf(notes))
        handshake(e2, wants = listOf("x.test"))
        e2.feed(frame(notification("x.test.note", payload)))
        assertTrue("dispatched in SYNCING", seen.isEmpty())

        // Ungranted -> dropped silently (covered again by the golden test).
        val e3 = engine(mutableListOf(), modules = listOf(notes))
        ready(e3) // wants = ["theme"]
        e3.feed(frame(notification("x.test.note", payload)))
        assertTrue("dispatched ungranted", seen.isEmpty())

        // Non-object params -> dropped, params checked LAST (the
        // handleNotification order).
        val e4 = engine(mutableListOf(), modules = listOf(notes))
        ready(e4, wants = listOf("x.test"))
        e4.feed(frame(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "x.test.note")
            put("params", JsonArray(listOf(JsonPrimitive(1))))
        }))
        assertTrue("dispatched non-object params", seen.isEmpty())
    }

    @Test
    fun outOfTableNotifyEntriesAreDroppedNeverEmitted() {
        // A handler asking the engine to emit a method outside its table, an
        // Emacs-sender method, or a request-class method gets nothing on the
        // wire — the module-bug arm of emitModuleNotifications.
        val rogue = EbpModule("x.test", "x.test", mapOf(
            "x.test.go" to MethodSpec(Sender.EMACS, true, setOf(SessionState.READY)),
            "x.test.in" to MethodSpec(Sender.EMACS, false, setOf(SessionState.READY))),
            ModuleHandler { _, p ->
                ModuleOutcome.Ok(p, listOf(
                    ModuleNotification("x.test.unknown", p),   // not in the table
                    ModuleNotification("x.test.in", p),        // Emacs-sender
                    ModuleNotification("x.test.go", p)))       // request-class
            })
        val out = mutableListOf<JsonObject>()
        val e = engine(out, modules = listOf(rogue))
        ready(e, wants = listOf("x.test"))
        e.feed(frame(request("r2", "x.test.go", JsonObject(emptyMap()))))
        assertTrue("the reply arrived", out.any { it["id"] == JsonPrimitive("r2") })
        assertTrue("a rogue notify entry reached the wire",
            out.none { it.stringOrNull("method")?.startsWith("x.test.") == true })
    }

    // ------------------------------------------------- limits reservation

    private fun constructs(limits: JsonObject, modules: List<EbpModule>): Boolean =
        try {
            CompanionEngine(config(limits = limits, modules = modules)) { }
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    @Test
    fun moduleCapabilitiesAreCountedByTheWelcomeReservation() {
        // Binary-search the largest max_input_state_bytes the module-free
        // config accepts, then require the echo-module config to refuse the
        // same limits: the capability's bytes in the worst-case granted
        // array must be counted (PLAN-rf3-seam.md D8).
        var lo = 262_144L
        var hi = 4_194_304L
        check(constructs(testLimits("max_input_state_bytes" to lo), emptyList()))
        check(!constructs(testLimits("max_input_state_bytes" to hi), emptyList()))
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (constructs(testLimits("max_input_state_bytes" to mid), emptyList()))
                lo = mid else hi = mid
        }
        assertTrue(constructs(testLimits("max_input_state_bytes" to lo), emptyList()))
        assertTrue("module capability bytes are not counted by the reservation",
            !constructs(testLimits("max_input_state_bytes" to lo),
                listOf(echoModule())))
    }
}
