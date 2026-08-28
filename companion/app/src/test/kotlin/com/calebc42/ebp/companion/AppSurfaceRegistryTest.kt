package com.calebc42.ebp.companion

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSurfaceRegistryTest {
    private fun spec(text: String) = buildJsonObject {
        put("t", "text")
        put("text", text)
    }

    @Test
    fun hiddenSurfaceUpdateDoesNotReplaceAnotherSurfaceFlow() {
        val registry = AppSurfaceRegistry()
        val files = registry.surface("app:files")
        val glasspane = registry.surface("app:glasspane")

        registry.publish("app:files", spec("Files"))
        registry.publish("app:glasspane", spec("Glasspane"))
        registry.publish("app:glasspane", spec("Updated"))

        assertEquals("Files", files.value?.get("text")?.toString()?.trim('"'))
        assertEquals("Updated", glasspane.value?.get("text")?.toString()?.trim('"'))
        assertEquals(
            listOf("app:files", "app:glasspane"),
            registry.catalog.value.surfaceIds,
        )
    }

    @Test
    fun removalPublishesNullBeforeDroppingTheCatalogEntry() {
        val registry = AppSurfaceRegistry()
        val surface = registry.surface("app:files")
        registry.publish("app:files", spec("Files"))

        registry.publish("app:files", null)

        assertNull(surface.value)
        assertEquals(emptyList<String>(), registry.catalog.value.surfaceIds)
    }

    @Test
    fun hydrationBarrierAndIdsAreOneAtomicCatalogState() {
        val registry = AppSurfaceRegistry()
        assertFalse(registry.catalog.value.loaded)

        registry.publish("app:files", spec("Files"))
        registry.markLoaded()

        assertEquals(listOf("app:files"), registry.catalog.value.surfaceIds)
        assertTrue(registry.catalog.value.loaded)
    }
}
