// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A node showing another's value follows its local edits until a reset. */
class LiveEditsTest {
    @Test
    fun anEditReadsBackInItsOwnGeneration() {
        val edits = LiveEdits()
        assertNull(edits.valueOf("tags", 0L))
        edits.record("tags", JsonPrimitive("w"), 0L)
        assertEquals(JsonPrimitive("w"), edits.valueOf("tags", 0L))
        assertNull(edits.valueOf("other", 0L))
    }

    @Test
    fun aSnapshotThatResetsTheInputWins() {
        val edits = LiveEdits()
        edits.record("tags", JsonPrimitive("w"), 3L)
        // The store moved the input to generation 4: the old edit is stale.
        assertNull(edits.valueOf("tags", 4L))
    }
}
