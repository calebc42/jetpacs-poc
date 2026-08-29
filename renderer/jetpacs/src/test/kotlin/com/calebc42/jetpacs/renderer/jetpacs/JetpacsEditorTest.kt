// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import com.calebc42.jetpacs.renderer.model.EditorSyncPhase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JetpacsEditorTest {
    @Test
    fun logicalLineStartsIncludeEmptyAndTrailingLinesWithoutCountingWraps() {
        assertArrayEquals(intArrayOf(0), logicalLineStarts(""))
        assertArrayEquals(intArrayOf(0), logicalLineStarts("a long visual line"))
        assertArrayEquals(intArrayOf(0, 2, 3), logicalLineStarts("a\n\n"))
        assertArrayEquals(intArrayOf(0, 3), logicalLineStarts("😀\nx"))
    }

    @Test
    fun everyUnsettledSynchronizedLifecycleHasVisibleStatusCopy() {
        EditorSyncPhase.entries.filter { it != EditorSyncPhase.READY }.forEach { phase ->
            assertEquals(true, editorSyncStatusText(phase)?.isNotBlank())
        }
        assertNull(editorSyncStatusText(EditorSyncPhase.READY))
    }

    @Test
    fun scopedRendererClaimsCanonicalSynchronizedEditors() {
        val node = Json.parseToJsonElement(
            """{"t":"editor","id":"body","document":"doc:one"}""",
        ) as JsonObject
        assertEquals(true, JetpacsEditorRenderer.appliesTo(node))
    }
}
