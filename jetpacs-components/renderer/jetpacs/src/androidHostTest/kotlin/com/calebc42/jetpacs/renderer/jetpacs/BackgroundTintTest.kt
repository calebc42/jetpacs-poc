// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `background_tint`: a container stands off the page under any theme. */
class BackgroundTintTest {
    private fun luminance(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    @Test
    fun darkPagesLightenAndLightPagesDarken() {
        val onDark = mixTint(Color.Black, Color.White, 0.07)
        val onLight = mixTint(Color.White, Color.Black, 0.07)
        assertTrue(luminance(onDark) > luminance(Color.Black))
        assertTrue(luminance(onLight) < luminance(Color.White))
        // A dark page takes half again the fraction; a light one the authored one.
        assertEquals(0.105f, onDark.red, 0.005f)
        assertEquals(0.93f, onLight.red, 0.005f)
    }

    @Test
    fun tintIsClamped() {
        assertEquals(Color.White, mixTint(Color.Black, Color.White, 3.0))
        assertEquals(Color.Black, mixTint(Color.Black, Color.White, -1.0))
    }
}
