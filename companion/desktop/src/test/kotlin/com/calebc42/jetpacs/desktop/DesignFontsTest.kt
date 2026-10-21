// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.ResourceFont
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsDesignRenderer
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsJvmFonts
import com.calebc42.jetpacs.renderer.jetpacs.ProvideJetpacsTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D2 evidence (docs/PLAN-desktop-companion.md): a `jetpacs.design` text that
 * names `plex-serif` resolves, on the desktop, to the IBM Plex Serif files
 * the Jetpacs renderer ships, loaded through the host's font loader. Without
 * the loader the same text falls back to the platform serif, which pins the
 * fallback and proves the first case measured the loader.
 */
@OptIn(ExperimentalTestApi::class)
class DesignFontsTest {
    @After
    fun clearLoader() {
        JetpacsJvmFonts.loader = null
    }

    private fun resolvedFamily(install: Boolean): FontFamily? {
        var family: FontFamily? = null
        runDesktopComposeUiTest(width = 600, height = 200) {
            if (install) DesktopFonts.install() else JetpacsJvmFonts.loader = null
            val context = InertDesignContext()
            setContent {
                ProvideJetpacsTheme(null) {
                    JetpacsDesignRenderer.render(plexSerifScope("Plex body"), context, Modifier)
                }
            }
            val results = mutableListOf<TextLayoutResult>()
            val node = onNodeWithText("Plex body").fetchSemanticsNode()
            assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
            family = results.single().layoutInput.style.fontFamily
        }
        return family
    }

    @Test
    fun designTextInPlexSerifLoadsTheBundledFilesThroughTheHostLoader() {
        val family = resolvedFamily(install = true)
        assertTrue("expected a font list, got $family", family is FontListFontFamily)
        val fonts = (family as FontListFontFamily).fonts
        assertEquals(
            listOf("font/ibm_plex_serif_regular.ttf", "font/ibm_plex_serif_medium.ttf",
                "font/ibm_plex_serif_semibold.ttf"),
            fonts.map { (it as ResourceFont).name })
        assertEquals(listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold),
            fonts.map { it.weight })
        for (font in fonts) {
            val name = (font as ResourceFont).name
            assertTrue("$name missing from the classpath",
                Thread.currentThread().contextClassLoader.getResource(name) != null)
        }
    }

    @Test
    fun withoutALoaderTheDesignFallsBackToThePlatformSerif() {
        assertEquals(FontFamily.Serif, resolvedFamily(install = false))
    }
}
