// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host.fonts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenTypeMetadataTest {
    @Test
    fun readsFamilyWeightAndStyleFromTheTables() {
        val face = OpenTypeMetadata.parse(SyntheticFont.build("Literata", weight = 600, italic = true))
        assertEquals("Literata", face.familyName)
        assertEquals(600, face.weight)
        assertTrue(face.italic)
        assertFalse(face.variable)
        assertEquals("literata", face.slug)
    }

    @Test
    fun typographicFamilyWinsAndWeightsRoundToTheDesignSteps() {
        val face = OpenTypeMetadata.parse(
            SyntheticFont.build("Source Serif 4 Display", weight = 250, typographicFamily = "Source Serif 4"),
        )
        assertEquals("Source Serif 4", face.familyName)
        assertEquals("source-serif-4", face.slug)
        assertEquals(300, face.weight)
        assertEquals(400, OpenTypeMetadata.parse(SyntheticFont.build("X", weight = 0)).weight)
        assertEquals(700, OpenTypeMetadata.parse(SyntheticFont.build("X", weight = 7)).weight)
        assertEquals(900, OpenTypeMetadata.parse(SyntheticFont.build("X", weight = 1000)).weight)
    }

    @Test
    fun macintoshNamesAndVariableFontsAreRead() {
        val mac = OpenTypeMetadata.parse(SyntheticFont.build("Café Mono", macFamilyOnly = true, variable = true))
        assertEquals("Café Mono", mac.familyName)
        assertEquals("caf-mono", mac.slug)
        assertTrue(mac.variable)
        assertEquals("font", FontFaceMetadata.slugOf("日本語"))
        assertEquals("a".repeat(64), FontFaceMetadata.slugOf("a".repeat(80)))
    }

    @Test
    fun collectionsWebFontsAndJunkAreRefusedWithAReason() {
        val collection = assertThrows(IllegalArgumentException::class.java) {
            OpenTypeMetadata.parse(SyntheticFont.build("X", version = 0x74746366))
        }
        assertTrue(collection.message!!.contains("collections"))
        val woff = assertThrows(IllegalArgumentException::class.java) {
            OpenTypeMetadata.parse(SyntheticFont.build("X", version = 0x774F4632))
        }
        assertTrue(woff.message!!.contains("WOFF"))
        assertThrows(IllegalArgumentException::class.java) { OpenTypeMetadata.parse(ByteArray(3)) }
        assertThrows(IllegalArgumentException::class.java) { OpenTypeMetadata.parse("not a font at all".toByteArray()) }
        // A table record pointing past the file is refused, not read.
        val truncated = SyntheticFont.build("X").copyOf(40)
        assertThrows(IllegalArgumentException::class.java) { OpenTypeMetadata.parse(truncated) }
    }
}
