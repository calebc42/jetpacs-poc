// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import com.calebc42.ebp.wire.PersistedDraft
import com.calebc42.ebp.wire.PersistedRecord
import com.calebc42.ebp.wire.SessionState
import com.calebc42.ebp.wire.SurfaceBacking
import com.calebc42.ebp.wire.SurfaceState
import com.calebc42.ebp.wire.SurfaceStore
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class OfflineVariantSwitchTest {

    private fun host() = buildJsonObject {
        put("t", "variant_host")
        put("id", "visibility")
        put("value", "overview")
        putJsonArray("variants") {
            for (value in listOf("overview", "contents")) {
                add(buildJsonObject {
                    put("value", value)
                    put("content", buildJsonObject {
                        put("t", "text")
                        put("text", value)
                    })
                })
            }
        }
    }

    private fun switch(value: String? = null) = buildJsonObject {
        put("builtin", "variant.switch")
        put("id", "visibility")
        value?.let { put("value", it) }
    }

    @Test
    fun onlyAnAbsentOrClosedEngineUsesTheOfflineCommitPath() {
        assertTrue(shouldCommitVariantOffline(null))
        assertTrue(shouldCommitVariantOffline(SessionState.CLOSED))
        for (state in SessionState.entries - SessionState.CLOSED) {
            assertFalse(state.name, shouldCommitVariantOffline(state))
        }
    }

    @Test
    fun successorInstallationCannotPassAnInFlightOfflineCommit() {
        val route = VariantEngineRoute<String>()
        assertTrue(route.advanceTo(1))
        val successor = route.prepare(1) { "successor" }!!
        val store = SurfaceStore(16, 1024)
        store.update("app:cached", 4, host(), null, null, null)
        val routeEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val installStarted = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        var successorWelcomeInput = ""

        val committing = thread(name = "variant-offline-commit") {
            route.withCurrent { current ->
                assertEquals(null, current)
                routeEntered.countDown()
                assertTrue(releaseCommit.await(2, TimeUnit.SECONDS))
                assertEquals(
                    OfflineVariantSwitchResult.Applied("visibility", "contents"),
                    commitOfflineVariantSwitch(
                        store, "app:cached", switch(), 262_144),
                )
                order += "commit"
            }
        }
        assertTrue(routeEntered.await(2, TimeUnit.SECONDS))
        val installing = thread(name = "variant-engine-install") {
            installStarted.countDown()
            assertTrue(route.activate(1, successor))
            // Models the successor's first welcome projection, which occurs
            // only after DeviceBridge installs the engine.
            successorWelcomeInput = store.inputState().toString()
            order += "install"
        }
        assertTrue(installStarted.await(2, TimeUnit.SECONDS))

        // Wait until the successor is observably queued on the same monitor;
        // this removes scheduler timing from the ordering assertion.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (installing.state != Thread.State.BLOCKED &&
            System.nanoTime() < deadline) Thread.yield()
        assertEquals(Thread.State.BLOCKED, installing.state)

        releaseCommit.countDown()
        committing.join(2_000)
        installing.join(2_000)
        assertEquals(listOf("commit", "install"), order)
        assertEquals("successor", route.current())
        assertTrue(successorWelcomeInput.contains("contents"))
    }

    @Test
    fun halfBuiltCandidateIsInvisibleAndAStaleServeCannotActivate() {
        val route = VariantEngineRoute<String>()
        val retired = mutableListOf<String>()

        assertTrue(route.advanceTo(1))
        val stale = route.prepare(1) { "stale" }!!
        assertEquals(null, route.current())
        route.withCurrent { assertEquals(null, it) }

        assertTrue(route.advanceTo(2) { retired += it })
        assertEquals(listOf("stale"), retired)
        assertFalse(route.activate(1, stale))
        assertEquals(null, route.current())

        val newest = route.prepare(2) { "newest" }!!
        var callbackSawPublishedEngine = true
        assertTrue(route.activate(2, newest) {
            // Listener/live-session setup occurs before receiver publication.
            callbackSawPublishedEngine = route.current() != null
        })
        assertFalse(callbackSawPublishedEngine)
        assertEquals("newest", route.current())
        assertFalse(route.advanceTo(2) { retired += it })
        assertEquals("newest", route.current())
    }

    @Test
    fun cachedSwitchCommitsStrictDraftAndExplicitCurrentIsNoOp() {
        val store = SurfaceStore(16, 1024)
        store.update("app:cached", 4, host(), null, null, null)

        assertEquals(
            OfflineVariantSwitchResult.Applied("visibility", "contents"),
            commitOfflineVariantSwitch(store, "app:cached", switch(), 262_144),
        )
        assertTrue(store.hasDraft("app:cached", "visibility"))
        assertEquals("contents",
            store.variantSelections()["app:cached" to "visibility"])
        assertEquals(
            OfflineVariantSwitchResult.NoChange,
            commitOfflineVariantSwitch(
                store, "app:cached", switch("contents"), 262_144),
        )
    }

    @Test
    fun failedOfflineAdmissionLeavesTheAuthoredBranchUnchanged() {
        val backing = object : SurfaceBacking {
            private var records = emptyList<PersistedRecord>()
            override fun load() = SurfaceState(records, emptyList())
            override fun replaceRecords(records: List<PersistedRecord>) {
                this.records = records
            }
            override fun replaceDrafts(drafts: List<PersistedDraft>) {
                throw java.io.IOException("synthetic draft failure")
            }
        }
        val store = SurfaceStore(16, 1024, backing = backing)
        store.update("app:cached", 4, host(), null, null, null)

        assertEquals(
            OfflineVariantSwitchResult.StorageFailed,
            commitOfflineVariantSwitch(store, "app:cached", switch(), 262_144),
        )
        assertFalse(store.hasDraft("app:cached", "visibility"))
        assertEquals("overview",
            store.variantSelections()["app:cached" to "visibility"])
    }
}
