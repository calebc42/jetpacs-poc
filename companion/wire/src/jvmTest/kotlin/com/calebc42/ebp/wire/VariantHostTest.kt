// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VariantHostTest {

    private fun text(label: String) = buildJsonObject {
        put("t", "text")
        put("text", label)
    }

    private fun host(first: JsonObject = text("Overview"),
                     second: JsonObject = text("Contents"),
                     selected: String = "overview") = buildJsonObject {
        put("t", "variant_host")
        put("id", "org-visibility")
        put("value", selected)
        putJsonArray("variants") {
            add(buildJsonObject {
                put("value", "overview")
                put("content", first)
            })
            add(buildJsonObject {
                put("value", "contents")
                put("content", second)
            })
        }
    }

    private fun hostValues(values: List<String>, selected: String = values.first()) =
        buildJsonObject {
            put("t", "variant_host")
            put("id", "bounded-host")
            put("value", selected)
            putJsonArray("variants") {
                values.forEach { value ->
                    add(buildJsonObject {
                        put("value", value)
                        put("content", text(value))
                    })
                }
            }
        }

    private fun expectInvalid(spec: JsonObject, pathPart: String) {
        try {
            SpecValidator.validateSurfaceSpec(spec)
            fail("expected ContentInvalid")
        } catch (e: ContentInvalid) {
            assertTrue("${e.path}: ${e.reason}",
                e.path.contains(pathPart) || e.reason.contains(pathPart))
        }
    }

    @Test
    fun validatesClosedAlternativesAndForwardSwitchReference() {
        val spec = buildJsonObject {
            put("t", "column")
            putJsonArray("children") {
                // A toolbar commonly precedes the body it switches.
                add(buildJsonObject {
                    put("t", "button")
                    put("label", "Cycle")
                    put("on_tap", buildJsonObject {
                        put("builtin", "variant.switch")
                        put("id", "org-visibility")
                        put("value", "contents")
                    })
                })
                add(host())
            }
        }
        val statefuls = SpecValidator.validateSurfaceSpec(spec)
        assertEquals("variant_host", statefuls["org-visibility"]?.stringOr("t"))

        val badTarget = buildJsonObject {
            put("t", "column")
            putJsonArray("children") {
                add(host())
                add(buildJsonObject {
                    put("t", "button")
                    put("label", "Bad")
                    put("on_tap", buildJsonObject {
                        put("builtin", "variant.switch")
                        put("id", "org-visibility")
                        put("value", "missing")
                    })
                })
            }
        }
        expectInvalid(badTarget, ".value")
    }

    @Test
    fun retainedContentRejectsMutableStateEditorsAndNestedHosts() {
        expectInvalid(host(first = buildJsonObject {
            put("t", "text_input")
            put("id", "draft")
        }), "stateful nodes")
        expectInvalid(host(first = buildJsonObject {
            put("t", "editor")
            put("id", "editor")
            put("document", "doc")
        }), "editor")
        expectInvalid(host(first = host()), "nested variant_host")

        // A non-toggle button has no input state and is safe to retain.
        SpecValidator.validateSurfaceSpec(host(first = buildJsonObject {
            put("t", "button")
            put("label", "Remote")
            put("on_tap", buildJsonObject {
                put("action", "org.open")
            })
        }))
    }

    @Test
    fun retainedWalkerDoesNotInterpretOpaqueApplicationDataAsNodes() {
        SpecValidator.validateSurfaceSpec(host(first = buildJsonObject {
            put("t", "button")
            put("label", "Remote")
            putJsonObject("on_tap") {
                put("action", "org.open")
                putJsonObject("args") {
                    put("t", "editor")
                    put("id", "application-data-not-a-node")
                }
            }
        }))

        // Chart-point meta is equally opaque, including during the generic
        // node-position walk that follows retained-host validation.
        SpecValidator.validateSurfaceSpec(host(first = buildJsonObject {
            put("t", "chart")
            putJsonArray("series") {
                add(buildJsonObject {
                    putJsonArray("points") {
                        add(buildJsonObject {
                            put("x", 0)
                            put("y", 1)
                            putJsonObject("meta") {
                                put("t", "editor")
                                put("id", "also-application-data")
                            }
                        })
                    }
                })
            }
        }))
    }

    @Test
    fun retainedBranchesEnforceUniversalIdentityAndSiblingKeys() {
        // Universal key/id remain identifier-typed inside an unselected
        // branch; selection never defers complete validation.
        expectInvalid(host(second = buildJsonObject {
            put("t", "text")
            put("text", "Bad key")
            put("key", 7)
        }), ".key")
        expectInvalid(host(second = buildJsonObject {
            put("t", "text")
            put("text", "Bad id")
            put("id", buildJsonObject { put("not", "a string") })
        }), ".id")
        expectInvalid(host(second = buildJsonObject {
            put("t", "text")
            put("text", "Bad spelling")
            put("key", "contains space")
        }), ".key")
        expectInvalid(host(second = buildJsonObject {
            put("t", "collapsible")
            put("id", "bad-header")
            putJsonObject("header") { put("t", 7) }
            putJsonArray("children") { add(text("Body")) }
        }), ".header")

        // Named Node slots and Node arrays below the same nearest Node parent
        // are one sibling set. The duplicate is in the hidden branch.
        expectInvalid(host(second = buildJsonObject {
            put("t", "collapsible")
            put("id", "details")
            putJsonObject("header") {
                put("t", "text")
                put("text", "Header")
                put("key", "same")
            }
            putJsonArray("children") {
                add(buildJsonObject {
                    put("t", "text")
                    put("text", "Body")
                    put("key", "same")
                })
            }
        }), "duplicate sibling key")

        // A Variant value is a presentation branch boundary. Descendant keys
        // intentionally may repeat in separately retained alternatives.
        SpecValidator.validateSurfaceSpec(host(
            first = buildJsonObject {
                put("t", "column")
                putJsonArray("children") {
                    add(buildJsonObject {
                        put("t", "text")
                        put("text", "A")
                        put("key", "row")
                    })
                }
            },
            second = buildJsonObject {
                put("t", "column")
                putJsonArray("children") {
                    add(buildJsonObject {
                        put("t", "text")
                        put("text", "B")
                        put("key", "row")
                    })
                }
            },
        ))
    }

    @Test
    fun variantCountUniquenessAndAuthoredSelectionAreBounded() {
        SpecValidator.validateSurfaceSpec(
            hostValues((1..WireLimits.MAX_VARIANTS_PER_HOST).map { "v$it" }))
        expectInvalid(hostValues(listOf("only")), "2..")
        expectInvalid(hostValues((1..(WireLimits.MAX_VARIANTS_PER_HOST + 1))
            .map { "v$it" }), "2..")
        expectInvalid(hostValues(listOf("same", "same")), "duplicate variant")
        expectInvalid(hostValues(listOf("one", "two"), selected = "missing"), ".value")
    }

    @Test
    fun storeCyclesAndDurablyRetainsACompatibleSelection() {
        val backing = MemorySurfaceBacking()
        val store = SurfaceStore(16, 1024, backing = backing)
        assertEquals("applied", store.update(
            "app:org", 1, host(), null, null, null).status)
        assertEquals("overview",
            store.variantSelections()["app:org" to "org-visibility"])
        val next = store.resolveVariantSwitch(
            "app:org", "org-visibility", null)
        assertEquals("contents", next)
        assertTrue(store.tryPutDraft(
            "app:org", "org-visibility", JsonPrimitive(next!!), 262_144))
        assertEquals("contents",
            store.variantSelections()["app:org" to "org-visibility"])

        // A refreshed authored default does not erase a still-compatible
        // local selection that Emacs has not acknowledged.
        store.update("app:org", 2, host(), null, null, null)
        assertEquals("contents",
            store.variantSelections()["app:org" to "org-visibility"])

        // Process recreation reads the same accepted branch from durable
        // input_state before any new surface.update arrives.
        val restored = SurfaceStore(16, 1024, backing = backing)
        assertEquals("contents",
            restored.variantSelections()["app:org" to "org-visibility"])
    }

    @Test
    fun reloadDropsASelectionWhoseVariantWasRemoved() {
        val seeded = SurfaceState(
            records = listOf(PersistedRecord(
                surface = "app:org",
                revision = 7,
                present = true,
                spec = host(),
                currentView = null,
            )),
            drafts = listOf(PersistedDraft(
                surface = "app:org",
                id = "org-visibility",
                value = JsonPrimitive("removed-variant"),
            )),
        )
        val backing = object : SurfaceBacking {
            override fun load() = seeded
            override fun replaceRecords(records: List<PersistedRecord>) = Unit
            override fun replaceDrafts(drafts: List<PersistedDraft>) = Unit
        }

        val restored = SurfaceStore(16, 1024, backing = backing)

        assertFalse(restored.hasDraft("app:org", "org-visibility"))
        assertEquals("overview",
            restored.variantSelections()["app:org" to "org-visibility"])
        assertTrue("org-visibility" !in restored.inputState().toString())
    }

    @Test
    fun equalAuthoredLatestLocalChoiceSurvivesRestartAndWelcome() {
        val backing = MemorySurfaceBacking()
        val store = SurfaceStore(16, 1024, backing = backing)
        store.update("app:org", 1, host(), null, null, null)
        // The user changes away from authored overview, then changes back
        // without any later snapshot acknowledging either occurrence.
        assertTrue(store.tryPutDraft("app:org", "org-visibility",
            JsonPrimitive("contents"), 262_144))
        assertTrue(store.tryPutDraft("app:org", "org-visibility",
            JsonPrimitive("overview"), 262_144))

        val restored = SurfaceStore(16, 1024, backing = backing)
        assertTrue(restored.hasDraft("app:org", "org-visibility"))
        assertEquals("overview", restored.inputState().reqObj("app:org")
            .reqString("org-visibility"))

        // Reconnection reports the retained selection before session.ready;
        // equality with the cached authored value must not erase it at load.
        val out = mutableListOf<JsonObject>()
        readyEngine(out, restored)
        val welcome = out.replyTo("h2").reqObj("result")
        assertEquals("overview", welcome.reqObj("input_state")
            .reqObj("app:org").reqString("org-visibility"))
    }

    @Test
    fun strictDraftAdmissionRollsBackAnOversizedInputState() {
        val store = SurfaceStore(16, 1024)
        store.update("app:org", 1, host(), null, null, null)
        assertFalse(store.tryPutDraft(
            "app:org", "org-visibility", JsonPrimitive("contents"), 2))
        assertFalse(store.hasDraft("app:org", "org-visibility"))
        assertEquals("overview",
            store.variantSelections()["app:org" to "org-visibility"])
    }

    @Test
    fun acknowledgementResetAndRemovalClearTheVariantDraft() {
        val store = SurfaceStore(16, 1024)
        store.update("app:org", 1, host(), null, null, null)
        assertTrue(store.tryPutDraft("app:org", "org-visibility",
            JsonPrimitive("contents"), 262_144))

        // Authored equality acknowledges the selection.
        store.update("app:org", 2, host(selected = "contents"), null, null, null)
        assertFalse(store.hasDraft("app:org", "org-visibility"))
        assertEquals("contents",
            store.variantSelections()["app:org" to "org-visibility"])

        assertTrue(store.tryPutDraft("app:org", "org-visibility",
            JsonPrimitive("overview"), 262_144))
        store.update("app:org", 3, host(selected = "contents"), null, null,
            JsonArray(listOf(JsonPrimitive("org-visibility"))))
        assertFalse(store.hasDraft("app:org", "org-visibility"))
        assertEquals("contents",
            store.variantSelections()["app:org" to "org-visibility"])

        assertTrue(store.tryPutDraft("app:org", "org-visibility",
            JsonPrimitive("overview"), 262_144))
        store.remove("app:org", 4)
        assertFalse(store.hasDraft("app:org", "org-visibility"))
        assertFalse(("app:org" to "org-visibility") in store.variantSelections())
    }

    private class FailingDraftBacking : SurfaceBacking {
        private var records = emptyList<PersistedRecord>()
        override fun load() = SurfaceState(records, emptyList())
        override fun replaceRecords(records: List<PersistedRecord>) {
            this.records = records
        }
        override fun replaceDrafts(drafts: List<PersistedDraft>) {
            throw java.io.IOException("synthetic draft failure")
        }
    }

    private class FailingRecordBacking : SurfaceBacking {
        private var drafts = emptyList<PersistedDraft>()
        override fun load() = SurfaceState(emptyList(), drafts)
        override fun replaceRecords(records: List<PersistedRecord>) {
            throw java.io.IOException("synthetic record failure")
        }
        override fun replaceDrafts(drafts: List<PersistedDraft>) {
            this.drafts = drafts
        }
    }

    private class CountingBacking : SurfaceBacking {
        private var state = SurfaceState(emptyList(), emptyList())
        var recordWrites = 0
            private set
        var draftWrites = 0
            private set

        override fun load() = state
        override fun replaceRecords(records: List<PersistedRecord>) {
            recordWrites++
            state = state.copy(records = records)
        }
        override fun replaceDrafts(drafts: List<PersistedDraft>) {
            draftWrites++
            state = state.copy(drafts = drafts)
        }
    }

    /** Stateful fault injection: successful calls really replace the durable
     * half, while each flag fails exactly one next replace. */
    private class FaultBacking : SurfaceBacking {
        private var state = SurfaceState(emptyList(), emptyList())
        var failNextRecord = false
        var failNextDraft = false
        var draftWrites = 0
            private set

        override fun load() = state

        override fun replaceRecords(records: List<PersistedRecord>) {
            if (failNextRecord) {
                failNextRecord = false
                throw java.io.IOException("synthetic record failure")
            }
            state = state.copy(records = records)
        }

        override fun replaceDrafts(drafts: List<PersistedDraft>) {
            draftWrites++
            if (failNextDraft) {
                failNextDraft = false
                throw java.io.IOException("synthetic draft failure")
            }
            state = state.copy(drafts = drafts)
        }
    }

    private fun readyEngine(out: MutableList<JsonObject>, store: SurfaceStore): CompanionEngine {
        val engine = CompanionEngine(CompanionConfig(
            serverName = "kat", serverVersion = "1",
            pairings = mapOf(katPid to katToken),
            supportedCapabilities = emptySet(),
            surfaceProfiles = buildJsonObject {
                put("app", buildJsonObject {
                    put("node_types", JsonArray(listOf(
                        "text", "variant_host").map(::JsonPrimitive)))
                    put("builtins", JsonArray(listOf(
                        "view.switch", "companion.settings.open", "variant.switch")
                        .map(::JsonPrimitive)))
                    put("features", JsonArray(emptyList()))
                    put("extensions", JsonArray(emptyList()))
                })
            },
            limits = testLimits("max_variants_per_host" to 8),
            nonceSource = { katSn }), surfaces = store) { bytes ->
            FrameDecoder().let { decoder ->
                decoder.feed(bytes).forEach(out::add)
                decoder.finish()
            }
        }
        engine.feed(frame(request("h1", "session.hello",
            EbpAuth.helloParams("t", "1", katPid, katCn, emptyList()))))
        engine.feed(frame(request("h2", "auth.response",
            EbpAuth.authParams(katPid, katCn, katSn, katToken))))
        engine.feed(frame(request("r1", "session.ready", JsonObject(emptyMap()))))
        return engine
    }

    @Test
    fun appProfileCouplesRetainedHostToItsBuiltin() {
        val badProfiles = buildJsonObject {
            put("app", buildJsonObject {
                put("node_types", JsonArray(listOf(
                    "text", "variant_host").map(::JsonPrimitive)))
                put("builtins", JsonArray(listOf(
                    "view.switch", "companion.settings.open").map(::JsonPrimitive)))
                put("features", JsonArray(emptyList()))
                put("extensions", JsonArray(emptyList()))
            })
        }
        try {
            CompanionEngine(CompanionConfig(
                serverName = "kat", serverVersion = "1",
                pairings = emptyMap(), supportedCapabilities = emptySet(),
                surfaceProfiles = badProfiles,
                limits = testLimits("max_variants_per_host" to 8))) { }
            fail("variant_host profile without variant.switch constructed")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("requires variant.switch"))
        }

        val missingLimitProfiles = buildJsonObject {
            put("app", buildJsonObject {
                put("node_types", JsonArray(listOf(
                    "text", "variant_host").map(::JsonPrimitive)))
                put("builtins", JsonArray(listOf(
                    "view.switch", "companion.settings.open", "variant.switch")
                    .map(::JsonPrimitive)))
                put("features", JsonArray(emptyList()))
                put("extensions", JsonArray(emptyList()))
            })
        }
        try {
            CompanionEngine(CompanionConfig(
                serverName = "kat", serverVersion = "1",
                pairings = emptyMap(), supportedCapabilities = emptySet(),
                surfaceProfiles = missingLimitProfiles,
                limits = testLimits())) { }
            fail("variant_host profile without max_variants_per_host constructed")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("max_variants_per_host"))
        }
    }

    private fun push(engine: CompanionEngine) {
        engine.feed(frame(request("s1", "surface.update", buildJsonObject {
            put("surface", "app:org")
            put("revision", 1)
            put("spec", host())
        })))
    }

    private fun switchDescriptor() = buildJsonObject {
        put("builtin", "variant.switch")
        put("id", "org-visibility")
    }

    @Test
    fun builtinPublishesOnlyTheNarrowListenerAndStateChanged() {
        val out = mutableListOf<JsonObject>()
        val store = SurfaceStore(16, 1024)
        val engine = readyEngine(out, store)
        push(engine)
        val surfaces = mutableListOf<String>()
        val variants = mutableListOf<Triple<String, String, String>>()
        engine.surfaceListener = surfaces::add
        engine.variantListener = { surface, id, value ->
            variants += Triple(surface, id, value)
        }
        out.clear()

        engine.dispatchAction("app:org", switchDescriptor(), null)

        assertTrue(surfaces.isEmpty())
        assertEquals(listOf(Triple("app:org", "org-visibility", "contents")), variants)
        assertEquals("contents",
            store.variantSelections()["app:org" to "org-visibility"])
        val changed = out.single { it.stringOrNull("method") == "state.changed" }
            .reqObj("params")
        assertEquals("contents", changed.reqString("value"))

        // Explicitly selecting the live branch is a clean no-op.
        out.clear()
        variants.clear()
        engine.dispatchAction("app:org", buildJsonObject {
            put("builtin", "variant.switch")
            put("id", "org-visibility")
            put("value", "contents")
        }, null)
        assertTrue(variants.isEmpty())
        assertTrue(out.none { it.stringOrNull("method") == "state.changed" })

        // Omitted value advances and wraps in authored order.
        engine.dispatchAction("app:org", switchDescriptor(), null)
        assertEquals(listOf(Triple("app:org", "org-visibility", "overview")), variants)
        assertEquals("overview",
            store.variantSelections()["app:org" to "org-visibility"])
    }

    @Test
    fun failedDurableCommitDoesNotSwitchOrEmit() {
        val out = mutableListOf<JsonObject>()
        val store = SurfaceStore(16, 1024, backing = FailingDraftBacking())
        val engine = readyEngine(out, store)
        push(engine)
        val variants = mutableListOf<String>()
        val problems = mutableListOf<String>()
        engine.variantListener = { _, _, value -> variants += value }
        engine.localStateProblemListener = problems::add
        out.clear()

        engine.dispatchAction("app:org", switchDescriptor(), null)

        assertTrue(variants.isEmpty())
        assertEquals(listOf(VARIANT_SAVE_FAILURE_MESSAGE), problems)
        assertTrue(out.none { it.stringOrNull("method") == "state.changed" })
        assertFalse(store.hasDraft("app:org", "org-visibility"))
        assertEquals("overview",
            store.variantSelections()["app:org" to "org-visibility"])
    }

    @Test
    fun strictCommitAlsoConfirmsTheAcceptedHostRecordIsDurable() {
        val store = SurfaceStore(16, 1024, backing = FailingRecordBacking())
        // surface.update may not claim an accepted host whose record did not
        // commit. The candidate is rolled back before strict local state can
        // find or switch it.
        try {
            store.update("app:org", 1, host(), null, null, null)
            fail("record persistence failure was swallowed")
        } catch (_: SurfacePersistenceFailed) { }
        assertFalse(store.tryPutDraft(
            "app:org", "org-visibility", JsonPrimitive("contents"), 262_144))
        assertFalse(store.hasDraft("app:org", "org-visibility"))
        assertFalse(("app:org" to "org-visibility") in store.variantSelections())
    }

    @Test
    fun failedResetCannotAdvanceTheFloorOrResurrectAfterRestart() {
        val backing = FaultBacking()
        val store = SurfaceStore(16, 1024, backing = backing)
        store.update("app:org", 1, host(), null, null, null)
        assertTrue(store.tryPutDraft("app:org", "org-visibility",
            JsonPrimitive("contents"), 262_144))
        val epoch = store.inputEpoch("app:org", "org-visibility")

        val out = mutableListOf<JsonObject>()
        val engine = readyEngine(out, store)
        val presented = mutableListOf<String>()
        engine.surfaceListener = presented::add
        out.clear()
        backing.failNextDraft = true
        engine.feed(frame(request("reset", "surface.update", buildJsonObject {
            put("surface", "app:org")
            put("revision", 2)
            put("spec", host())
            put("reset_input_ids",
                JsonArray(listOf(JsonPrimitive("org-visibility"))))
        })))

        val error = out.replyTo("reset").reqObj("error")
        assertEquals(-32603L, error.reqLong("code"))
        assertEquals("internal-error", error.reqObj("data").reqString("kind"))
        assertTrue("failed update must not publish a surface", presented.isEmpty())
        assertEquals(1L, store.revisionOf("app:org"))
        assertEquals(epoch, store.inputEpoch("app:org", "org-visibility"))
        assertEquals("contents",
            store.variantSelections()["app:org" to "org-visibility"])

        // The two durable halves are still the complete revision-1 state. A
        // fresh process therefore cannot pair revision 2 with the divergent
        // draft that reset_input_ids superseded.
        val restored = SurfaceStore(16, 1024, backing = backing)
        assertEquals(1L, restored.revisionOf("app:org"))
        assertEquals("contents",
            restored.variantSelections()["app:org" to "org-visibility"])
    }

    @Test
    fun recordFailureAfterDraftResetCompensatesTheOldDraft() {
        val backing = FaultBacking()
        val store = SurfaceStore(16, 1024, backing = backing)
        store.update("app:org", 1, host(), null, null, null)
        assertTrue(store.tryPutDraft("app:org", "org-visibility",
            JsonPrimitive("contents"), 262_144))
        assertEquals(1, backing.draftWrites)
        backing.failNextRecord = true

        try {
            store.update("app:org", 2, host(), null, null,
                JsonArray(listOf(JsonPrimitive("org-visibility"))))
            fail("record persistence failure was swallowed")
        } catch (_: SurfacePersistenceFailed) { }

        // Candidate draft write + compensating old-draft write.
        assertEquals(3, backing.draftWrites)
        assertEquals(1L, store.revisionOf("app:org"))
        assertEquals("contents",
            store.variantSelections()["app:org" to "org-visibility"])
        val restored = SurfaceStore(16, 1024, backing = backing)
        assertEquals(1L, restored.revisionOf("app:org"))
        assertEquals("contents",
            restored.variantSelections()["app:org" to "org-visibility"])
    }

    @Test
    fun repeatedStrictSwitchesStayOnTheDraftOnlyHotPath() {
        val backing = CountingBacking()
        val store = SurfaceStore(16, 1024, backing = backing)
        store.update("app:org", 1, host(), null, null, null)
        assertEquals(1, backing.recordWrites)

        assertTrue(store.tryPutDraft(
            "app:org", "org-visibility", JsonPrimitive("contents"), 262_144))
        assertTrue(store.tryPutDraft(
            "app:org", "org-visibility", JsonPrimitive("overview"), 262_144))

        assertEquals("a durable accepted surface is not reserialized", 1,
            backing.recordWrites)
        assertEquals(2, backing.draftWrites)
    }
}
