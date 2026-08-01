// SPDX-License-Identifier: GPL-3.0-or-later
// RF-4a M1 (PLAN-rf4a-data-spec.md): the day-1 stub pins for the drafted
// #154 data.* registry rows. The apply lands with RF-4b/4c; until then the
// stubs must keep the grant discipline — ungranted answers the FULL §7.3
// error object (wire-equal to an unknown method, the D6 fixture
// discipline), granted answers -32603 not-implemented, and class/state
// disclosure stays core-shaped (SYNCING → 1204, like edit.apply). The
// test config's supported set carries "ebp.data" only here — no live
// endpoint advertises it until RF-4b/4c.
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
import org.junit.Test

class EbpDataRungTest {

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

    private fun engine(sink: MutableList<JsonObject>,
                       supported: Set<String> = setOf("theme")): CompanionEngine =
        CompanionEngine(
            CompanionConfig(
                serverName = "kat-companion", serverVersion = "1.0.0",
                pairings = mapOf(katPid to katToken),
                supportedCapabilities = supported,
                surfaceProfiles = profiles(),
                limits = testLimits(),
                nonceSource = { katSn },
            )
        ) { bytes ->
            FrameDecoder().let { d -> d.feed(bytes).forEach(sink::add); d.finish() }
        }

    private fun toReady(engine: CompanionEngine, wants: List<String>) {
        engine.feed(frame(request("h1", "session.hello", EbpAuth.helloParams(
            "test-client", "0.0.1", katPid, katCn, wants))))
        engine.feed(frame(request("h2", "auth.response",
            EbpAuth.authParams(katPid, katCn, katSn, katToken))))
        engine.feed(frame(request("q1", "queue.replay", JsonObject(emptyMap()))))
        engine.feed(frame(request("r1", "session.ready", JsonObject(emptyMap()))))
        check(engine.state == SessionState.READY)
    }

    /** The §7.3 reply for id "g1" — the same fixture ExtensionSeamTest
     * pins; ungranted data.* must be wire-equal to it. */
    private val sevenThreeReply: JsonObject = Json.parseToJsonElement(
        """{"jsonrpc":"2.0","id":"g1","error":{"code":-32601,""" +
            """"message":"Method not found","data":{"kind":"method-not-found"}}}"""
    ) as JsonObject

    @Test
    fun ungrantedDataRequestIsWireEqualToUnknown() {
        val out = mutableListOf<JsonObject>()
        val e = engine(out, supported = setOf("theme", "ebp.data"))
        toReady(e, wants = listOf("theme")) // never wants ebp.data
        e.feed(frame(request("g1", "data.schema",
            buildJsonObject { put("schema", JsonObject(emptyMap())) })))
        assertTrue("ungranted data.schema is not the §7.3 answer",
            jsonValueEquals(sevenThreeReply, out.last()))
        // ...and equal to a genuinely unknown method on the same session.
        e.feed(frame(request("g1", "no.such", JsonObject(emptyMap()))))
        assertTrue("ungranted stub and unknown method disagree",
            jsonValueEquals(sevenThreeReply, out.last()))
        assertEquals(SessionState.READY, e.state)
    }

    @Test
    fun grantedDataRequestAnswersNotImplementedAtThisRung() {
        val out = mutableListOf<JsonObject>()
        val e = engine(out, supported = setOf("theme", "ebp.data"))
        toReady(e, wants = listOf("theme", "ebp.data"))
        for (method in listOf("data.schema", "data.changeset")) {
            e.feed(frame(request("d1", method, JsonObject(emptyMap()))))
            val err = out.errorOf("d1")
            assertEquals("$method code", -32603L, err.reqLong("code"))
            assertEquals("$method kind", "internal-error",
                err.reqObj("data").reqString("kind"))
        }
        assertEquals(SessionState.READY, e.state)
    }

    @Test
    fun dataRowsKeepCoreClassAndStateDisclosure() {
        // A ratified §11 row is published surface (PLAN-rf4a D3): SYNCING
        // answers 1204 regardless of grant, exactly like edit.apply — data
        // rows must not become a distinguishable subclass of core methods.
        val out = mutableListOf<JsonObject>()
        val e = engine(out, supported = setOf("theme", "ebp.data"))
        e.feed(frame(request("h1", "session.hello", EbpAuth.helloParams(
            "test-client", "0.0.1", katPid, katCn, listOf("theme", "ebp.data")))))
        e.feed(frame(request("h2", "auth.response",
            EbpAuth.authParams(katPid, katCn, katSn, katToken))))
        assertEquals(SessionState.SYNCING, e.state)
        e.feed(frame(request("s1", "data.schema", JsonObject(emptyMap()))))
        assertEquals(1204L, out.errorOf("s1").reqLong("code"))
        // Request-class method arriving as a notification: silent drop.
        val before = out.size
        e.feed(frame(notification("data.changeset", JsonObject(emptyMap()))))
        assertEquals(before, out.size)
    }
}
