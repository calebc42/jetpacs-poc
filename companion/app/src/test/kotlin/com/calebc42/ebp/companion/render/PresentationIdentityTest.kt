// SPDX-License-Identifier: GPL-3.0-or-later
// RF-1b: presentation identity under snapshot replacement (SPEC 16.1/17.3).
//
// "A node's presentation identity is its `key` when present, otherwise its
// `id` when present, otherwise its structural tree path... Presentation
// identity controls focus, expansion, selection, scroll anchors, and
// similar rendering state." (SPEC 16.1)
//
// The load-bearing consequence is that a node carrying a stable `id` MUST
// keep its expansion when Emacs pushes a new snapshot that inserts a
// sibling ABOVE it — the node did not move in identity terms, only in
// tree position. That is the case docs/AUDIT-full-spec-RAW.md:308 flags,
// and it went unverified until this suite existed: neither the APK smoke
// nor the string-literal fixture rule can see it.
//
// Each test pairs the moving case with a control that does not move, so a
// failure says WHICH property broke rather than merely that something did.
package com.calebc42.ebp.companion.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.calebc42.ebp.companion.DeviceBridge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PresentationIdentityTest {

    @get:Rule val compose = createComposeRule()

    private fun obj(text: String): JsonObject =
        Json.parseToJsonElement(text) as JsonObject

    /** A column whose collapsible carries a stable id, optionally preceded
     * by a sibling. The collapsible starts collapsed so that "expanded"
     * is only ever a state the test itself produced. */
    private fun column(withLeadingSibling: Boolean): JsonObject = obj(
        """
        {"t":"column","children":[
          ${if (withLeadingSibling) """{"t":"text","text":"PREPENDED"},""" else ""}
          {"t":"collapsible","id":"section-a","collapsed":true,
           "header":{"t":"text","text":"HEADER-A"},
           "children":[{"t":"text","text":"BODY-A"}]}
        ]}
        """.trimIndent())

    /** A column with the sibling APPENDED instead — the control. The
     * collapsible keeps slot 0, so nothing about identity is exercised. */
    private fun columnAppended(): JsonObject = obj(
        """
        {"t":"column","children":[
          {"t":"collapsible","id":"section-a","collapsed":true,
           "header":{"t":"text","text":"HEADER-A"},
           "children":[{"t":"text","text":"BODY-A"}]},
          {"t":"text","text":"APPENDED"}
        ]}
        """.trimIndent())

    /** Renders `spec()` and re-renders whatever it returns after the state
     * flips — the Compose-side stand-in for Emacs replacing a snapshot. */
    @Composable
    private fun Surface(spec: (Boolean) -> JsonObject, replaced: Boolean) {
        val bridge = DeviceBridge(
            RuntimeEnvironment.getApplication(), onSurfaceChanged = { _, _ -> })
        RenderNode(spec(replaced), RenderCtx("app:test", bridge))
    }

    private fun runSnapshotReplacement(spec: (Boolean) -> JsonObject) {
        var replaced by mutableStateOf(false)
        compose.setContent { Surface(spec, replaced) }
        // Expand it: the chevron's description is the expansion state.
        compose.onNodeWithContentDescription("Expand").performClick()
        compose.onNodeWithText("BODY-A").assertIsDisplayed()
        // Emacs pushes a replacement snapshot.
        replaced = true
        compose.waitForIdle()
    }

    @Test
    fun expansionSurvivesASiblingPrependedAboveTheSameId() {
        runSnapshotReplacement { replaced -> column(withLeadingSibling = replaced) }
        compose.onNodeWithText("PREPENDED").assertIsDisplayed()
        // SPEC 16.1: identity is the id, which did not change. The node
        // moved in the tree, not in identity, so expansion is retained.
        compose.onNodeWithText("BODY-A").assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse").assertIsDisplayed()
    }

    @Test
    fun controlExpansionSurvivesASiblingAppendedBelow() {
        runSnapshotReplacement { replaced ->
            if (replaced) columnAppended() else column(withLeadingSibling = false)
        }
        compose.onNodeWithText("APPENDED").assertIsDisplayed()
        // The collapsible never left slot 0. If THIS fails, the cause is
        // not positional identity — it is that any re-render loses
        // expansion, which is a different defect.
        compose.onNodeWithText("BODY-A").assertIsDisplayed()
    }
}
