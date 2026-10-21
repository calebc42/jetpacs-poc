// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D0 evidence that needs no display: the spike renders offscreen at
 * 1280 x 800, every proving region is present, Navigation 3 pushes a
 * shared `JetpacsNavKey`, and a screenshot lands in `build/spike/`.
 */
@OptIn(ExperimentalTestApi::class)
class SpikeRenderTest {
    @Test
    fun spikeRendersEveryProvingRegion() = runDesktopComposeUiTest(width = 1280, height = 800) {
        setContent { SpikeApp() }

        onNodeWithTag("window-size").assertTextEquals("window 1280 x 800 dp")
        onNodeWithTag("wire-proof")
            .assertTextEquals("wire: decoded 1 frame, method session.hello")
        onNodeWithTag("rail").assertIsDisplayed()
        onNodeWithTag("expressive").assertIsDisplayed()
        onNodeWithTag("list-pane").assertIsDisplayed()
        onNodeWithTag("detail-pane").assertIsDisplayed()

        val out = File(System.getProperty("jetpacs.spike.out") ?: "build/spike")
        out.mkdirs()
        val shot = File(out, "spike.png")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shot)
        assertTrue(shot.length() > 0)

        onNodeWithTag("open-surface").performClick()
        onNodeWithTag("surface-entry").assertTextEquals("Surface app:hub")
    }
}
