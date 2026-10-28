// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calebc42.jetpacs.companion.host.ui.HostUiPlatform
import com.calebc42.jetpacs.companion.host.ui.LocalHostUiPlatform
import com.calebc42.jetpacs.companion.host.ui.OfflineConnectionBanner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineConnectionBannerInstrumentedTest {
    @get:Rule
    val compose = createAndroidComposeRule<CompanionTestHostActivity>()

    /** The banner takes its wording from the host platform; this one keeps the default. */
    private val platform = object : HostUiPlatform {
        @Composable
        override fun BackHandler(enabled: Boolean, onBack: () -> Unit) {}

        @Composable
        override fun windowSizeDp(): DpSize = DpSize(411.dp, 731.dp)

        override val openEmacs: (() -> Unit)? = null
        override val listeningEndpoint: String = "127.0.0.1:8765"
    }

    @Test
    fun cachedSurfaceKeepsReceiverSettingsAndEmacsReachable() {
        var settingsOpens = 0
        var emacsOpens = 0
        compose.setContent {
            CompositionLocalProvider(LocalHostUiPlatform provides platform) {
                MaterialTheme {
                    OfflineConnectionBanner(
                        onSettings = { settingsOpens += 1 },
                        onOpenEmacs = { emacsOpens += 1 },
                    )
                }
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
