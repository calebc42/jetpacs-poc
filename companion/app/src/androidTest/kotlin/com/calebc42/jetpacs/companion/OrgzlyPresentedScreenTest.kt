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

/** Actual Elisp-generated Orgzly screens through the Companion installation. */
@RunWith(AndroidJUnit4::class)
class OrgzlyPresentedScreenTest {
    @get:Rule val compose = createAndroidComposeRule<CompanionTestHostActivity>()
    private val host = InertHost()

    private fun present(name: String) {
        val source = InstrumentationRegistry.getInstrumentation().context.assets
            .open("orgzly/$name.json").bufferedReader().use { it.readText() }
        val node = Json.parseToJsonElement(source) as JsonObject
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalComposeIconResolver provides ComposeIconResolver(IconMap::get)) {
                Box(Modifier.width(400.dp)) {
                    RenderNode(node, "app:orgzly", host,
                        configuration = CompanionRenderer.installation(true).composeConfiguration)
                }
                }
            }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        File(compose.activity.cacheDir, "orgzly-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun notebookRowsOpenOneOpaqueBookAction() {
        present("notebooks-light")
        compose.onNodeWithText("gtd").assertIsDisplayed().performClick()
        assertEquals("orgzly.book.open", host.actions.single().descriptor["action"]?.jsonPrimitive?.content)
        compose.onNodeWithContentDescription("Refresh notebooks").assertWidthIsAtLeast(48.dp)
        screenshot("notebooks-light")
    }

    @Test fun outlineRichTextKeepsOneNoteActionAndSeparateFoldTarget() {
        present("outline-light")
        compose.onNodeWithText("Call mom", substring = true).assertIsDisplayed().assertHasClickAction().performClick()
        assertEquals("orgzly.note.open", host.actions.single().descriptor["action"]?.jsonPrimitive?.content)
        compose.onNodeWithContentDescription("Fold or unfold note").assertWidthIsAtLeast(48.dp).performClick()
        assertEquals("orgzly.note.fold", host.actions.last().descriptor["action"]?.jsonPrimitive?.content)
        screenshot("outline-light")
    }

    @Test fun darkOutlineAndControlledNoteFieldsRender() {
        present("outline-dark")
        compose.onNodeWithText("Water plants", substring = true).assertIsDisplayed()
        screenshot("outline-dark")
    }

    @Test fun editorDisplaysAuthoritativeNoteFields() {
        present("note-light")
        screenshot("note-light")
        // The visual label is intentionally hidden from duplicate semantics;
        // assert the single real editable target carrying the note value.
        compose.onNodeWithText("Call mom").assertIsDisplayed().assert(hasSetTextAction())
        compose.onNodeWithText("TODO").assertIsDisplayed()
        screenshot("note-light")
        compose.onNodeWithText("Call mom").performTextReplacement("Changed on tablet")
        compose.onNodeWithText("Save note").performScrollTo().performClick()
        val save = host.actions.single().descriptor
        assertEquals("orgzly.note.save", save["action"]?.jsonPrimitive?.content)
        val fields = (save["capture_fields"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(7, fields.size)
        assertTrue(host.publishedFields.any { (id, value) ->
            id in fields && value?.jsonPrimitive?.content == "Changed on tablet"
        })
    }
}
