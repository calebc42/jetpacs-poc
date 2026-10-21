// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.calebc42.jetpacs.material3.EbpTheme
import com.calebc42.jetpacs.material3.RenderNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D3 evidence (docs/PLAN-desktop-companion.md): the Material renderer's
 * platform seams do real work on the desktop. Each case composes Material
 * `RenderNode` on the JVM through [MaterialTestHost].
 */
@OptIn(ExperimentalTestApi::class)
class MaterialRendererTest {
    private fun spec(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun DesktopComposeUiTest.render(host: MaterialTestHost, node: JsonObject) {
        setContent { EbpTheme(null) { RenderNode(node, "app:test", host) } }
    }

    private fun DesktopComposeUiTest.pixel(x: Int, y: Int): Color =
        onRoot().captureToImage().toPixelMap()[x, y]

    private fun Color.isRed() = red > 0.8f && green < 0.25f && blue < 0.25f

    @Test
    fun imageNodeDecodesAPngWithSkiaAndShowsIt() = runDesktopComposeUiTest(width = 200, height = 200) {
        // A 4x4 opaque red PNG, through the data: form, the guards, Skia and
        // the cache; the Image scales it into its 40 dp box.
        render(MaterialTestHost(), spec(
            """{"t":"image","url":"data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAEElEQVR4nGP4z8AARwzEcQCukw/x0F8jngAAAABJRU5ErkJggg==","width":40,"height":40}"""))
        waitUntil(timeoutMillis = 10_000) { pixel(20, 20).isRed() }
    }

    @Test
    fun anUndecodableImageKeepsThePlaceholder() = runDesktopComposeUiTest(width = 200, height = 200) {
        render(MaterialTestHost(), spec(
            """{"t":"image","url":"data:image/png;base64,AAAAAAAAAAAA","width":40,"height":40,"content_description":"broken"}"""))
        val turnedRed = runCatching {
            waitUntil(timeoutMillis = 1_500) { pixel(20, 20).isRed() }
        }.exceptionOrNull() !is ComposeTimeoutException
        assertTrue("an undecodable payload must never paint", !turnedRed)
    }

    @Test
    fun canvasTextOpDrawsOnTheDesktop() = runDesktopComposeUiTest(width = 200, height = 100) {
        render(MaterialTestHost(), spec(
            """{"t":"canvas","width":160,"height":48,"ops":[{"op":"text","text":"HELLO","x":4,"y":36,"size":28,"color":"#ff0000"}]}"""))
        waitForIdle()
        val map = onRoot().captureToImage().toPixelMap()
        val red = (0 until 160).sumOf { x -> (0 until 48).count { y -> map[x, y].isRed() } }
        assertTrue("expected red text pixels, found $red", red > 20)
    }

    @Test
    fun discreteSliderPublishesTheExactAuthoredValue() = runDesktopComposeUiTest(width = 400, height = 120) {
        val host = MaterialTestHost()
        render(host, spec(
            """{"t":"slider","id":"zoom","value":1,"values":[1,2,4,8],"on_change":{"action":"zoom.set"}}"""))
        // Index space 0..3: progress 1 is the second authored value, which
        // value-space snapping could never reach.
        onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        waitForIdle()
        assertEquals(listOf("zoom" to JsonPrimitive(2)), host.published)
        assertEquals(JsonPrimitive(2), host.dispatched.single().value)
    }

    @Test
    fun aConnectedToggleButtonComposesAndDispatches() = runDesktopComposeUiTest(width = 300, height = 120) {
        val host = MaterialTestHost()
        render(host, spec(
            """{"t":"button","label":"Pin","checked":true,"shape_role":"leading","shape":"square","on_tap":{"action":"demo.tap"}}"""))
        onNodeWithText("Pin").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals("demo.tap", host.dispatched.single().descriptor["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun aCheckableMenuItemAnnouncesItsStateAndSupportingText() = runDesktopComposeUiTest(width = 400, height = 400) {
        render(MaterialTestHost(), spec(MENU_WITH_A_CHECKED_ITEM))
        onAllNodes(hasClickAction()).onFirst().performClick()
        waitForIdle()
        onNodeWithText("Edit mode").assertIsDisplayed()
        onNode(hasText("Edit") and SemanticsMatcher.expectValue(
            SemanticsProperties.ToggleableState, ToggleableState.On)).assertExists()
    }

    private companion object {
        /** ebp goldens/widgets.golden line 93. */
        const val MENU_WITH_A_CHECKED_ITEM = """{"footer":{"t":"text","text":"footer"},"groups":[{"items":[{"checked":true,"checked_icon":"edit","icon":"edit","label":"Edit","on_tap":{"action":"demo.tap"},"supporting_text":"Edit mode"},{"icon":"settings","label":"Settings","on_tap":{"action":"demo.tap"}}],"label":"Modification"},{"items":[{"label":"Home","on_tap":{"action":"demo.tap"},"trailing_icon":"home"}],"label":"Navigation"}],"icon":"more_vert","t":"menu"}"""
    }
}
