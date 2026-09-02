// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineConnectionBannerInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cachedSurfaceKeepsReceiverSettingsAndEmacsReachable() {
        var settingsOpens = 0
        var emacsOpens = 0
        compose.setContent {
            MaterialTheme {
                OfflineConnectionBanner(
                    onSettings = { settingsOpens += 1 },
                    onOpenEmacs = { emacsOpens += 1 },
                )
            }
        }

        compose.onNodeWithText("Settings")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithText("Open Emacs")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        compose.runOnIdle {
            assertEquals(1, settingsOpens)
            assertEquals(1, emacsOpens)
        }
    }
}
