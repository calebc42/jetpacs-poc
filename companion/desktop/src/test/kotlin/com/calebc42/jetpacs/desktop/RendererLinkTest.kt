// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.calebc42.jetpacs.material3.EbpTheme
import com.calebc42.jetpacs.renderer.jetpacs.ProvideJetpacsTheme
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D1 evidence that the desktop links the multiplatform renderers
 * (docs/PLAN-desktop-d1-renderers.md, S8): the Material and Jetpacs theme
 * providers compose on the JVM, a pushed role reaches the Material scheme, and
 * the test classpath holds JetBrains' Compose UI, never Google's stubs.
 */
@OptIn(ExperimentalTestApi::class)
class RendererLinkTest {
    @Test
    fun pushedThemeReachesTheMaterialSchemeThroughBothRenderers() =
        runDesktopComposeUiTest(width = 400, height = 300) {
            val payload = buildJsonObject {
                put("dark", false)
                put("colors", buildJsonObject { put("primary", "#ff0000") })
            }
            var primary: Color? = null
            setContent {
                EbpTheme(payload) {
                    ProvideJetpacsTheme(payload) {
                        primary = MaterialTheme.colorScheme.primary
                        Text("themed", Modifier.testTag("themed"))
                    }
                }
            }
            onNodeWithTag("themed").assertIsDisplayed()
            assertEquals(Color(0xFFFF0000), primary)
        }

    @Test
    fun desktopClasspathLinksJetBrainsComposeNotGoogleStubs() {
        val entries = System.getProperty("java.class.path").split(File.pathSeparator)
        val googleUi = Regex("""[/\\]androidx\.compose\.(ui|foundation|animation|material|material3)(\.adaptive)?[/\\]""")
        val offenders = entries.filter { it.contains("jvmstubs", ignoreCase = true) || googleUi.containsMatchIn(it) }
        assertTrue("Google Compose UI or stub jars on the desktop classpath:\n${offenders.joinToString("\n")}",
            offenders.isEmpty())
        assertTrue("no JetBrains Compose UI jar on the classpath",
            entries.any { it.contains("org.jetbrains.compose.ui") })
    }
}
