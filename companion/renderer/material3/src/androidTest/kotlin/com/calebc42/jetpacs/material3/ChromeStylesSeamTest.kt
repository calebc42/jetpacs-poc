// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calebc42.ebp.renderer.compose.ComposeChromeStyles
import com.calebc42.ebp.renderer.compose.LocalComposeChromeStyles
import com.calebc42.jetpacs.material3.ui.SemanticsHostActivity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The chrome Material draws itself takes a design scope's resolved chrome
 * styles through the shared seam, and keeps its own defaults without one.
 */
@RunWith(AndroidJUnit4::class)
class ChromeStylesSeamTest {
    @get:Rule
    val compose = createAndroidComposeRule<SemanticsHostActivity>()

    private val bridge = InertMaterialHost()

    private fun spec(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun fontSizeOf(text: String): Float {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single().layoutInput.style.fontSize.value
    }

    @Test
    fun tabAndRailLabelsWearTheSeamAndKeepDefaultsWithoutIt() {
        val tabs = spec(
            """{"t":"tabs","id":"t","items":[{"label":"Alpha"},{"label":"Beta"}],
                "children":[{"t":"text","text":"one"},{"t":"text","text":"two"}]}""",
        )
        val rail = spec(
            """{"t":"navigation_rail","items":[
                {"label":"Rail one","icon":"home","selected":true},
                {"label":"Rail two","icon":"star"}]}""",
        )
        val chrome = ComposeChromeStyles(
            tabLabel = TextStyle(fontSize = 23.sp),
            railLabel = TextStyle(fontSize = 19.sp),
        )
        compose.setContent {
            MaterialTheme {
                val root = RenderCtx("app:test", bridge)
                CompositionLocalProvider(LocalComposeChromeStyles provides chrome) {
                    RenderNode(tabs, root.child(tabs, 0))
                    RenderNode(rail, root.child(rail, 1))
                }
            }
        }
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        assertEquals(23f, fontSizeOf("Alpha"))
        assertEquals(19f, fontSizeOf("Rail one"))
    }

    @Test
    fun withoutTheSeamTheLabelsKeepMaterialTypography() {
        val tabs = spec(
            """{"t":"tabs","id":"t","items":[{"label":"Gamma"}],
                "children":[{"t":"text","text":"one"}]}""",
        )
        var expected = 0f
        compose.setContent {
            MaterialTheme {
                expected = MaterialTheme.typography.titleSmall.fontSize.value
                RenderNode(tabs, RenderCtx("app:test", bridge).child(tabs, 0))
            }
        }
        compose.onNodeWithText("Gamma").assertIsDisplayed()
        assertEquals(expected, fontSizeOf("Gamma"))
    }
}
