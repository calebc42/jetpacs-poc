// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import com.calebc42.ebp.renderer.compose.LocalComposeIconResolver
import com.calebc42.ebp.renderer.compose.ComposeIconResolver
import com.calebc42.jetpacs.material3.IconMap
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calebc42.jetpacs.material3.RenderNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual Elisp-generated Harp screens through the Companion installation. */
@RunWith(AndroidJUnit4::class)
class HarpPresentedScreenTest {
    @get:Rule val compose = createAndroidComposeRule<CompanionTestHostActivity>()
    private val host = InertHost()

    private fun present(name: String) {
        val source = InstrumentationRegistry.getInstrumentation().context.assets
            .open("harp/$name.json").bufferedReader().use { it.readText() }
        val node = Json.parseToJsonElement(source) as JsonObject
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalComposeIconResolver provides ComposeIconResolver(IconMap::get)) {
                Box(Modifier.width(400.dp)) {
                    RenderNode(node, "app:harp", host,
                        configuration = CompanionRenderer.installation(true).composeConfiguration)
                }
                }
            }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        File(compose.activity.cacheDir, "harp-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun profileOpensUsingAnOpaqueIdentity() {
        present("profiles-light")
        compose.onNodeWithText("Example Profile").assertIsDisplayed().performClick()
        assertEquals("harp.profile", host.actions.single().descriptor["action"]?.jsonPrimitive?.content)
        compose.onNodeWithContentDescription("Refresh profiles").assertWidthIsAtLeast(48.dp)
        screenshot("profiles-light")
    }

    @Test fun editorPublishesCapturedFieldsForSave() {
        present("entry-form-light")
        compose.onNodeWithText("Synthetic journal #sample(3).").performScrollTo()
            .assert(hasSetTextAction()).performTextReplacement("Changed on tablet #sample(4)")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        val save = host.actions.single().descriptor
        assertEquals("harp.save", save["action"]?.jsonPrimitive?.content)
        val fields = (save["capture_fields"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(6, fields.size)
        assertTrue(host.publishedFields.any { (id, value) ->
            id in fields && value?.jsonPrimitive?.content == "Changed on tablet #sample(4)"
        })
        screenshot("entry-form-light")
    }

    @Test fun metricChartHasAnAccessibleSummary() {
        present("metrics-light")
        compose.onNodeWithContentDescription("Example reading: 3 units").performScrollTo().assertIsDisplayed()
        screenshot("metrics-light")
    }

    @Test fun darkMetricChartUsesTheSameSyntheticReadings() {
        present("metrics-dark")
        compose.onNodeWithContentDescription("Example reading: 3 units").performScrollTo().assertIsDisplayed()
        screenshot("metrics-dark")
    }

    @Test fun doseButtonsDispatchTheSelectedOccurrence() {
        present("medications-light")
        compose.onAllNodesWithText("Taken")[0].performScrollTo().performClick()
        val action = host.actions.single().descriptor
        assertEquals("harp.dose", action["action"]?.jsonPrimitive?.content)
        val args = action["args"] as JsonObject
        assertEquals("taken", args["state"]?.jsonPrimitive?.content)
        assertTrue(args["id"]!!.jsonPrimitive.content.startsWith("hd-"))
        assertTrue("amount" !in args)
        screenshot("medications-light")
    }
}
