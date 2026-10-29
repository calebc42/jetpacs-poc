// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.calebc42.jetpacs.companion.host.fonts.PickedFontFile
import com.calebc42.jetpacs.companion.host.fonts.UserFontStore
import com.calebc42.jetpacs.companion.host.fonts.UserFonts
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsDesignRenderer
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsFontSet
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsFonts
import com.calebc42.jetpacs.renderer.jetpacs.ProvideJetpacsTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A user's font end to end on the desktop: a real font file added through
 * the store becomes a family under the identifier its name table declares,
 * a `jetpacs.design` text naming that identifier lays out in it, and the
 * sans role replaced by it re-fonts a text that named `plex-sans`. The file
 * is one of the bundled IBM Plex faces copied out of the classpath, so the
 * test needs nothing beyond the renderer jar.
 */
@OptIn(ExperimentalTestApi::class)
class UserFontsDesktopTest {
    private val directory: Path = Files.createTempDirectory("jetpacs-user-fonts")
    private val fonts = UserFonts(UserFontStore(directory), DesktopFonts.fileLoader)

    @After
    fun cleanUp() {
        JetpacsFonts.install(JetpacsFontSet.None)
        Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    private fun plexFile(name: String): PickedFontFile {
        val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("font/$name.ttf")!!.use { it.readBytes() }
        return PickedFontFile("$name.ttf", bytes)
    }

    private fun layoutFamily(scope: JsonObject, text: String): FontFamily? {
        var family: FontFamily? = null
        runDesktopComposeUiTest(width = 600, height = 200) {
            DesktopFonts.install()
            setContent {
                ProvideJetpacsTheme(null) {
                    JetpacsDesignRenderer.render(scope, InertDesignContext(), Modifier)
                }
            }
            val results = mutableListOf<TextLayoutResult>()
            val node = onNodeWithText(text).fetchSemanticsNode()
            assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
            family = results.single().layoutInput.style.fontFamily
        }
        return family
    }

    @Test
    fun anAddedFontFileIsNamedByItsFamilyAndRendersDesignTextNamingIt() {
        val added = fonts.add(plexFile("ibm_plex_serif_medium")).getOrThrow()
        assertEquals("ibm-plex-serif", added.slug)
        assertEquals("IBM Plex Serif", added.name)
        assertEquals(listOf(500), added.faces.map { it.weight })
        fonts.add(plexFile("ibm_plex_serif_semibold")).getOrThrow()

        val installed = JetpacsFonts.current.named.getValue("ibm-plex-serif")
        assertEquals(
            listOf(FontWeight.Medium, FontWeight.SemiBold),
            (installed as androidx.compose.ui.text.font.FontListFontFamily).fonts.map { it.weight },
        )
        assertSame(installed, layoutFamily(familyScope("ibm-plex-serif", "User serif"), "User serif"))
    }

    @Test
    fun aReplacedRoleReFontsTextThatNamedTheBundledFamilyAndRevertsOnRemoval() {
        fonts.add(plexFile("ibm_plex_mono_regular")).getOrThrow()
        fonts.assign(UserFontStore.Role.SANS, "ibm-plex-mono")
        val mono = JetpacsFonts.current.named.getValue("ibm-plex-mono")
        assertSame(mono, JetpacsFonts.current.sans)
        assertSame(mono, layoutFamily(familyScope("plex-sans", "Body"), "Body"))
        // A family this device lacks follows the replaced sans role too.
        assertSame(mono, layoutFamily(familyScope("literata", "Missing"), "Missing"))

        fonts.remove("ibm-plex-mono")
        assertTrue(JetpacsFonts.current.named.isEmpty())
        assertEquals(null, JetpacsFonts.current.sans)
        assertEquals(FontFamily.Default, layoutFamily(familyScope("literata", "Missing"), "Missing"))
    }

    private fun familyScope(family: String, text: String): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(
            """
            {"t":"jetpacs.design_scope","tokens":{},
             "styles":{"face":{"properties":{"font_family":{"kind":"font-family","value":"$family"}},"rules":[]}},
             "component_styles":[{"slot":"text.body","styles":["face"]}],
             "children":[{"t":"text","text":${JsonPrimitive(text)}}]}
            """,
        ) as JsonObject
}
