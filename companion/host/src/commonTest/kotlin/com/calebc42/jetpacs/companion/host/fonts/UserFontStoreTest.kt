// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host.fonts

import java.nio.file.Files
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFontStoreTest {
    private val directory: Path = Files.createTempDirectory("jetpacs-fonts")
    private val store = UserFontStore(directory)

    @After
    fun cleanUp() {
        Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }

    @Test
    fun facesOfOneFamilyGroupUnderItsSlugAndTheSameFaceIsReplaced() {
        val regular = store.add(SyntheticFont.build("Literata", 400), "Literata-Regular.ttf")
        assertEquals("literata", regular.slug)
        assertEquals(listOf(400), regular.faces.map { it.weight })

        val bold = store.add(SyntheticFont.build("Literata", 700), "Literata-Bold.otf")
        assertEquals(listOf(400, 700), bold.faces.map { it.weight })
        assertTrue(bold.faces[1].file.endsWith(".otf"))

        // The same weight and style again: one face, the newer file.
        val again = store.add(SyntheticFont.build("Literata", 700, typographicFamily = "Literata"), "other.ttf")
        assertEquals(listOf(400, 700), again.faces.map { it.weight })
        val files = Files.list(directory).map { it.fileName.toString() }.toList()
        assertEquals(3, files.size) // two faces and the manifest
        assertTrue(files.all { it in again.faces.map(UserFontStore.Face::file) || it == UserFontStore.MANIFEST })

        val catalog = store.catalog()
        assertEquals(listOf("literata"), catalog.families.map { it.slug })
        assertEquals("Literata", catalog.family("literata")?.name)
        assertEquals(catalog, UserFontStore(directory).catalog())
    }

    @Test
    fun rolesPointAtFamiliesAndRevertWhenTheFamilyGoes() {
        store.add(SyntheticFont.build("Literata"), null)
        store.add(SyntheticFont.build("Iosevka", 400), "iosevka.ttf")
        store.assign(UserFontStore.Role.SERIF, "literata")
        store.assign(UserFontStore.Role.MONO, "iosevka")
        assertEquals(
            mapOf(UserFontStore.Role.SERIF to "literata", UserFontStore.Role.MONO to "iosevka"),
            store.catalog().roles,
        )
        assertThrows(IllegalArgumentException::class.java) { store.assign(UserFontStore.Role.SANS, "missing") }

        assertTrue(store.remove("iosevka"))
        assertFalse(store.remove("iosevka"))
        val catalog = store.catalog()
        assertEquals(mapOf(UserFontStore.Role.SERIF to "literata"), catalog.roles)
        assertNull(catalog.family("iosevka"))
        assertEquals(2, Files.list(directory).count()) // one face and the manifest

        store.assign(UserFontStore.Role.SERIF, null)
        assertTrue(store.catalog().roles.isEmpty())
    }

    @Test
    fun aMissingFileDropsItsFaceAndJunkIsRefusedBeforeAnythingIsWritten() {
        val family = store.add(SyntheticFont.build("Literata"), null)
        Files.delete(directory.resolve(family.faces.single().file))
        assertTrue(store.catalog().families.isEmpty())

        assertThrows(IllegalArgumentException::class.java) { store.add("junk".toByteArray(), "junk.ttf") }
        assertThrows(IllegalArgumentException::class.java) {
            store.add(ByteArray(UserFontStore.MAX_FILE_BYTES + 1), "big.ttf")
        }
        assertEquals(listOf(UserFontStore.MANIFEST), Files.list(directory).map { it.fileName.toString() }.toList())
    }

    @Test
    fun theFamilyCountIsBounded() {
        repeat(UserFontStore.MAX_FAMILIES) { store.add(SyntheticFont.build("Family $it"), null) }
        assertThrows(IllegalArgumentException::class.java) { store.add(SyntheticFont.build("One More"), null) }
        // A face for an existing family still fits.
        store.add(SyntheticFont.build("Family 3", 700), null)
        assertEquals(UserFontStore.MAX_FAMILIES, store.catalog().families.size)
    }
}
