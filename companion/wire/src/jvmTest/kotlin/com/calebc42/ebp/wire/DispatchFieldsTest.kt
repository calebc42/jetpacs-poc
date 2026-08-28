// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DispatchFieldsTest {
    private class Ready(
        val engine: CompanionEngine,
        val output: MutableList<JsonObject>,
        private val frameRef: Array<ByteArray?>,
    ) {
        val lastFrame: ByteArray? get() = frameRef[0]
    }

    private fun readyEngine(
        queue: DurableQueue? = null,
        failWrites: BooleanArray = booleanArrayOf(false),
    ): Ready {
        val output = mutableListOf<JsonObject>()
        val frameRef = arrayOfNulls<ByteArray>(1)
        val engine = CompanionEngine(
            CompanionConfig(
                serverName = "kat",
                serverVersion = "1",
                pairings = mapOf(katPid to katToken),
                supportedCapabilities = setOf("theme"),
                surfaceProfiles = buildJsonObject {
                    putJsonObject("app") {
                        put(
                            "node_types",
                            JsonArray(listOf("text", "text_input").map(::JsonPrimitive)),
                        )
                        put("builtins", JsonArray(emptyList()))
                        put("features", JsonArray(emptyList()))
                        put("extensions", JsonArray(emptyList()))
                    }
                },
                limits = testLimits(),
                nonceSource = { katSn },
            ),
            queue = queue ?: DurableQueue(MemoryQueueStore(), 256, 8_388_608),
        ) { bytes ->
            frameRef[0] = bytes
            if (failWrites[0]) throw java.io.IOException("partial write")
            FrameDecoder().let { decoder ->
                decoder.feed(bytes).forEach(output::add)
                decoder.finish()
            }
        }
        engine.feed(
            frame(
                request(
                    "h1",
                    "session.hello",
                    EbpAuth.helloParams("t", "1", katPid, katCn, emptyList()),
                ),
            ),
        )
        engine.feed(
            frame(
                request(
                    "h2",
                    "auth.response",
                    EbpAuth.authParams(katPid, katCn, katSn, katToken),
                ),
            ),
        )
        engine.feed(frame(request("r1", "session.ready", JsonObject(emptyMap()))))
        assertEquals(SessionState.READY, engine.state)
        engine.feed(
            frame(
                request("s1", "surface.update", buildJsonObject {
                    put("surface", "app:main")
                    put("revision", 1)
                    putJsonObject("spec") {
                        put("t", "text_input")
                        put("id", "pw")
                        put("password", true)
                    }
                }),
            ),
        )
        return Ready(engine, output, frameRef)
    }

    private fun passwordDescriptor(policy: String = "drop") = buildJsonObject {
        put("action", "auth.login")
        put("when_offline", policy)
        put("capture_fields", buildJsonArray { add(JsonPrimitive("pw")) })
        if (policy != "drop") put("ttl_s", 600)
    }

    @Test
    fun typedPasswordSubmitCarriesSecretOnlyInFieldsAndZerosEncodedFrame() {
        val ready = readyEngine()
        var outcome: ActionAdmissionOutcome? = null

        ready.engine.dispatchSecretAction(
            "app:main",
            passwordDescriptor(),
            buildJsonObject { put("pw", "s3cret") },
            setOf("pw"),
            "pw",
        ) { outcome = it }

        val event = ready.output.last { it.stringOrNull("method") == "event.action" }
            .reqObj("params")
        assertEquals("auth.login", event.reqString("action"))
        assertEquals("s3cret", event.reqObj("fields").reqString("pw"))
        assertFalse(event.objOrNull("args")?.containsKey("value") == true)
        assertTrue(ready.lastFrame!!.all { it == 0.toByte() })
        assertEquals(1, ready.engine.outstandingRequestCount())

        // The session deadline closes this same engine in production. Its
        // close path must release the full-write/no-response correlation.
        ready.engine.close("deadline fixture")
        assertEquals(0, ready.engine.outstandingRequestCount())
        assertEquals(
            UnsafeAdmissionReason.TransportClosed,
            (outcome as ActionAdmissionOutcome.NotAdmitted).reason,
        )
    }

    @Test
    fun ordinaryDispatchCannotCaptureAnAcceptedPassword() {
        val ready = readyEngine()
        var outcome: ActionAdmissionOutcome? = null

        ready.engine.dispatchAction(
            "app:main",
            passwordDescriptor(),
            null,
            sourceId = "pw",
        ) { outcome = it }

        assertEquals(
            UnsafeAdmissionReason.ContentInvalid,
            (outcome as ActionAdmissionOutcome.NotAdmitted).reason,
        )
        assertFalse(ready.output.any { it.stringOrNull("method") == "event.action" })
    }

    @Test
    fun failedSecretWriteZerosFrameClosesTransportAndReleasesCorrelation() {
        val failWrites = booleanArrayOf(false)
        val ready = readyEngine(failWrites = failWrites)
        var outcome: ActionAdmissionOutcome? = null
        failWrites[0] = true

        ready.engine.dispatchSecretAction(
            "app:main",
            passwordDescriptor(),
            buildJsonObject { put("pw", "partial-secret") },
            setOf("pw"),
            "pw",
        ) { outcome = it }

        assertEquals(SessionState.CLOSED, ready.engine.state)
        assertEquals(0, ready.engine.outstandingRequestCount())
        assertTrue(ready.lastFrame!!.all { it == 0.toByte() })
        assertEquals(
            UnsafeAdmissionReason.TransportClosed,
            (outcome as ActionAdmissionOutcome.NotAdmitted).reason,
        )
    }

    @Test
    fun passwordNeverReachesFileQueueUnderAnySessionOrAuthoredPolicy() {
        val file = File.createTempFile("ebp-secret-queue", ".json").also(File::delete)
        val queue = DurableQueue(FileQueueStore(file), 256, 8_388_608)
        val ready = readyEngine(queue)
        val canary = "AUDIT-SECRET-${System.nanoTime()}"
        val outcomes = mutableListOf<ActionAdmissionOutcome>()

        ready.engine.dispatchSecretAction(
            "app:main",
            passwordDescriptor("queue"),
            buildJsonObject { put("pw", canary) },
            setOf("pw"),
            "pw",
            outcomes::add,
        )
        ready.engine.close("test offline")
        ready.engine.dispatchSecretAction(
            "app:main",
            passwordDescriptor(),
            buildJsonObject { put("pw", canary) },
            setOf("pw"),
            "pw",
            outcomes::add,
        )

        assertEquals(0, queue.count())
        assertEquals(0, FileQueueStore(file).load().records.size)
        assertFalse(file.takeIf(File::exists)?.readText()?.contains(canary) == true)
        assertEquals(
            listOf(
                UnsafeAdmissionReason.ContentInvalid,
                UnsafeAdmissionReason.NotReady,
            ),
            outcomes.map { (it as ActionAdmissionOutcome.NotAdmitted).reason },
        )
        file.delete()
    }
}
