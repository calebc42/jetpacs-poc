// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

import com.calebc42.ebp.wire.ActivatePairingCommand
import com.calebc42.ebp.wire.ActivatePairingResult
import com.calebc42.ebp.wire.MemoryEbpDurableStore
import com.calebc42.ebp.wire.PairingId
import com.calebc42.ebp.wire.PersistedDraft
import com.calebc42.ebp.wire.PersistedRecord
import com.calebc42.ebp.wire.activatePairing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The surface backing writes ONE row per accepted mutation (SPEC 13.2, 13.3,
 * 14.2) and never reads, compares or rewrites the catalog's other rows. The
 * memory reference store stands in for Room here: the backing speaks only
 * the durable-store contract, so what it asks of a transaction is the same
 * against either.
 */
class RoomSurfaceBackingTest {
    private val pairing = PairingId("0123456789abcdef0123456789abcdef")
    private var now = 1_000L

    private fun fixture(): Pair<MemoryEbpDurableStore, RoomSurfaceBacking> {
        val store = MemoryEbpDurableStore()
        runBlocking {
            assertTrue(
                store.activatePairing(ActivatePairingCommand(pairing, 0))
                    is ActivatePairingResult.Activated,
            )
        }
        val backing = RoomSurfaceBacking(store, pairing) { now }
        backing.load()
        return store to backing
    }

    private fun text(text: String): JsonObject =
        buildJsonObject { put("t", "text"); put("text", text) }

    private fun record(
        surface: String,
        revision: Long,
        spec: JsonObject? = text(surface),
        currentView: String? = null,
    ) = PersistedRecord(surface, revision, spec != null, spec, currentView)

    private fun rows(store: MemoryEbpDurableStore) =
        runBlocking { store.restore(pairing).surfaces.associateBy { it.surfaceId } }

    @Test
    fun putRecordWritesOneRowAndLeavesTheOthersAsTheyWere() {
        val (store, backing) = fixture()
        backing.replaceRecords(listOf(record("app:a", 1), record("app:b", 1)))
        val before = rows(store)
        assertEquals(setOf("app:a", "app:b"), before.keys)
        assertEquals(1L, before.getValue("app:a").firstSeenOrdinal)
        assertEquals(2L, before.getValue("app:b").firstSeenOrdinal)
        assertEquals(1_000L, before.getValue("app:b").acceptedAtMs)

        // A rewritten row is stamped with the clock, so an unchanged stamp
        // witnesses a row that was not written.
        now = 2_000
        val specB2 = text("b2")
        backing.putRecord(record("app:b", 2, specB2))
        val after = rows(store)
        assertEquals(1_000L, after.getValue("app:a").acceptedAtMs)
        assertEquals(1L, after.getValue("app:a").revision)
        assertEquals(2L, after.getValue("app:b").revision)
        assertEquals(specB2, after.getValue("app:b").spec)
        assertEquals("the written row is accepted now", 2_000L, after.getValue("app:b").acceptedAtMs)
        assertEquals("the ordinal is immutable", 2L, after.getValue("app:b").firstSeenOrdinal)

        // A surface the catalog never held takes the next ordinal, allocated
        // in the same transaction as its row.
        now = 3_000
        backing.putRecord(record("app:c", 1))
        val withC = rows(store)
        assertEquals(3L, withC.getValue("app:c").firstSeenOrdinal)
        assertEquals(3_000L, withC.getValue("app:c").acceptedAtMs)
        assertEquals(1_000L, withC.getValue("app:a").acceptedAtMs)

        // SPEC 13.3: a tombstone is the same row with no spec, floor intact.
        backing.putRecord(record("app:a", 2, spec = null))
        val tombstone = rows(store).getValue("app:a")
        assertFalse(tombstone.present)
        assertNull(tombstone.spec)
        assertEquals(2L, tombstone.revision)
        assertEquals(1L, tombstone.firstSeenOrdinal)

        // SPEC 14.2: a view choice is one row too.
        backing.putRecord(record("app:c", 1, currentView = "detail"))
        assertEquals("detail", rows(store).getValue("app:c").currentView)

        // Reload sees exactly what was written.
        val reloaded = RoomSurfaceBacking(store, pairing) { now }.load()
        assertEquals(
            mapOf("app:a" to 2L, "app:b" to 2L, "app:c" to 1L),
            reloaded.records.associate { it.surface to it.revision },
        )
    }

    @Test
    fun putRecordWithDraftsCommitsTheRecordAndTheReconciledDraftsTogether() {
        val (store, backing) = fixture()
        backing.replaceRecords(listOf(record("app:a", 1)))
        backing.replaceDrafts(
            listOf(
                PersistedDraft("app:a", "title", JsonPrimitive("typed")),
                PersistedDraft("app:a", "note", JsonPrimitive("kept")),
            ),
        )
        assertEquals(2, runBlocking { store.restore(pairing).drafts }.size)

        // SPEC 13.6: the snapshot that acknowledges `title` arrives with the
        // drafts it leaves standing; the dropped draft goes in the same commit.
        backing.putRecordWithDrafts(
            record("app:a", 2),
            listOf(PersistedDraft("app:a", "note", JsonPrimitive("kept"))),
        )
        val snapshot = runBlocking { store.restore(pairing) }
        assertEquals(2L, snapshot.surfaces.single().revision)
        assertEquals(listOf("note"), snapshot.drafts.map { it.nodeId })

        val reloaded = RoomSurfaceBacking(store, pairing) { now }.load()
        assertEquals(2L, reloaded.records.single().revision)
        assertEquals(listOf("note"), reloaded.drafts.map { it.id })
    }

    @Test
    fun theWholeReplaceKeepsAnUnchangedRowAndItsStampAndDropsTheRest() {
        val (store, backing) = fixture()
        val a = record("app:a", 1)
        backing.replaceRecords(listOf(a, record("app:b", 1)))

        // The store hands back the same instance for a record it did not
        // touch (the engine shares untouched trees by reference) and a new
        // one for the change: only the change is written and stamped.
        now = 5_000
        backing.replaceRecords(listOf(a, record("app:b", 2)))
        val rows = rows(store)
        assertEquals(1_000L, rows.getValue("app:a").acceptedAtMs)
        assertEquals(5_000L, rows.getValue("app:b").acceptedAtMs)
        assertEquals(2L, rows.getValue("app:b").revision)

        // A record the replace leaves out is deleted.
        backing.replaceRecords(listOf(a))
        assertEquals(setOf("app:a"), rows(store).keys)
    }
}
