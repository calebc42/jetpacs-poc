// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calebc42.ebp.companion.render.EbpTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JetpacsComponentsSemanticsTest {
    @get:Rule
    val compose = createAndroidComposeRule<SemanticsHostActivity>()

    @Test
    fun catalogActionExposesButtonRoleAndOneClickTarget() {
        var taps = 0
        compose.setContent {
            EbpTheme(payload = null) {
                JetpacsCatalogAction(
                    headline = "Files",
                    supportingText = "app:files",
                    onClick = { taps += 1 },
                    leadingContent = {
                        Icon(Icons.Default.Apps, contentDescription = null)
                    },
                )
            }
        }

        val roleButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        compose.onNodeWithText("Files")
            .assert(roleButton)
            .assertHasClickAction()
            .performClick()
        compose.runOnIdle { assertEquals(1, taps) }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test
    fun choiceRowsExposeOneRadioTargetPerChoice() {
        var selected = "strict"
        compose.setContent {
            EbpTheme(payload = null) {
                Column(Modifier.selectableGroup()) {
                    JetpacsChoiceRow(
                        label = "Strict",
                        supportingText = "Candidates start with what you typed",
                        selected = selected == "strict",
                        onClick = { selected = "strict" },
                    )
                    JetpacsChoiceRow(
                        label = "Contains",
                        supportingText = "Candidates match anywhere",
                        selected = selected == "contains",
                        onClick = { selected = "contains" },
                    )
                }
            }
        }

        compose.onNodeWithText("Strict").assertIsSelected().assertHasClickAction()
        compose.onNodeWithText("Contains").assertIsNotSelected().assertHasClickAction()
        compose.onAllNodes(hasClickAction()).assertCountEquals(2)
    }

    @Test
    fun materialTextFieldMountsBesideCustomStyles() {
        compose.setContent {
            EbpTheme(payload = null) {
                Column {
                    JetpacsCatalogAction(
                        headline = "Components",
                        supportingText = "Custom Styles host",
                        onClick = {},
                        leadingContent = {
                            Icon(Icons.Default.Apps, contentDescription = null)
                        },
                    )
                    OutlinedTextField(
                        value = "",
                        onValueChange = {},
                        label = { Text("Catalog search") },
                    )
                }
            }
        }

        compose.onNodeWithText("Components").assertIsDisplayed()
        compose.onNodeWithText("Catalog search").assertIsDisplayed()
    }
}
