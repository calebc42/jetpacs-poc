// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calebc42.ebp.companion.ui.SemanticsHostActivity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class EbpSemanticsTest {
    @get:Rule
    val compose = createAndroidComposeRule<SemanticsHostActivity>()

    @Test
    fun authoredEnvelopeProjectsWithoutClearingDescendants() {
        var dispatches = 0
        val collection = buildJsonObject {
            put("t", "column")
            put("semantics", buildJsonObject {
                put("pane_title", "Inbox")
                put("heading_level", 1)
                put("live_region", "assertive")
                put("traversal_group", true)
                put("collection", buildJsonObject {
                    put("row_count", 3)
                    put("column_count", 2)
                })
            })
        }
        val item = buildJsonObject {
            put("t", "button")
            put("label", "Visible label")
            put("semantics", buildJsonObject {
                put("name", "Archive")
                put("description", "Moves this message out of the inbox")
                put("state_description", "Ready")
                put("error", "Network unavailable")
                put("traversal_index", 2.5)
                put("collection_item", buildJsonObject {
                    put("row_index", 1)
                    put("row_span", 1)
                    put("column_index", 0)
                    put("column_span", 2)
                })
                put("actions", buildJsonArray {
                    add(buildJsonObject {
                        put("label", "Archive")
                        put("on_action", buildJsonObject {
                            put("action", "mail.archive")
                        })
                    })
                })
            })
        }

        compose.setContent {
            Column(
                Modifier
                    .testTag("collection")
                    .ebpSemantics(collection) { dispatches += 1 },
            ) {
                Box(
                    Modifier
                        .testTag("item")
                        .clickable { }
                        .ebpSemantics(item) { dispatches += 1 },
                )
            }
        }

        compose.onNodeWithTag("collection")
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.PaneTitle,
                "Inbox",
            ))
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Assertive,
            ))
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.CollectionInfo,
                CollectionInfo(3, 2),
            ))
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.IsTraversalGroup,
                true,
            ))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))

        val expectedItem = SemanticsMatcher("authored collection item") { node ->
            node.config.getOrNull(SemanticsProperties.CollectionItemInfo)?.let {
                it.rowIndex == 1 && it.rowSpan == 1 &&
                    it.columnIndex == 0 && it.columnSpan == 2
            } == true
        }
        compose.onNodeWithTag("item")
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("Archive. Moves this message out of the inbox"),
            ))
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Ready",
            ))
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.Error,
                "Network unavailable",
            ))
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.TraversalIndex,
                2.5f,
            ))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(expectedItem)
            .performCustomAccessibilityActionWithLabel("Archive")

        compose.runOnIdle { assertEquals(1, dispatches) }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun generatedDefaultsProjectHeadingProgressAndExpansion() {
        val heading = buildJsonObject {
            put("t", "section_header")
            put("title", "Today")
        }
        val progress = buildJsonObject {
            put("t", "progress")
            put("value", 0.25)
        }
        val indeterminate = buildJsonObject { put("t", "progress") }
        val collapsible = buildJsonObject {
            put("t", "collapsible")
            put("collapsed", false)
        }
        val textInput = buildJsonObject {
            put("t", "text_input")
            put("id", "code")
            put("is_error", true)
            put("supporting_text", "Use six digits")
            put("max_length", 6)
        }

        compose.setContent {
            Column {
                Box(Modifier.testTag("heading").ebpSemantics(heading) { })
                Box(Modifier.testTag("progress").ebpSemantics(progress) { })
                Box(Modifier.testTag("indeterminate").ebpSemantics(indeterminate) { })
                Box(Modifier.testTag("expanded").ebpSemantics(collapsible) { })
                Box(Modifier.testTag("text-input").ebpSemantics(textInput) { })
            }
        }

        compose.onNodeWithTag("heading")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithTag("progress")
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.25f, 0f..1f))
        compose.onNodeWithTag("indeterminate")
            .assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
        compose.onNodeWithTag("expanded")
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Expanded",
            ))
        compose.onNodeWithTag("text-input")
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.Error,
                "Use six digits",
            ))
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.MaxTextLength,
                6,
            ))
    }

    @Test
    fun foundationCustomActionUsesOrdinarySinkExactlyOnce() {
        val visible = buildJsonObject { put("action", "mail.open") }
        val custom = buildJsonObject { put("action", "mail.archive") }
        val node = buildJsonObject {
            put("t", "button")
            put("label", "Open")
            put("on_tap", visible)
            put("semantics", buildJsonObject {
                put("actions", buildJsonArray {
                    add(buildJsonObject {
                        put("label", "Archive")
                        put("on_action", custom)
                    })
                })
            })
        }
        val dispatched = mutableListOf<JsonObject>()

        compose.setContent {
            RenderCoreComposeNode(
                node = node,
                actions = CoreComposeActionSink(dispatched::add),
                modifier = Modifier.testTag("core-button"),
            )
        }

        compose.onNodeWithTag("core-button")
            .performCustomAccessibilityActionWithLabel("Archive")
        compose.runOnIdle { assertEquals(listOf(custom), dispatched) }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)

        compose.onNodeWithTag("core-button").performClick()
        compose.runOnIdle { assertEquals(listOf(custom, visible), dispatched) }
    }
}
