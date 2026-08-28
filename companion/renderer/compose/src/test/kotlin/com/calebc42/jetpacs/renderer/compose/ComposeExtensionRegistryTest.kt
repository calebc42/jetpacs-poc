// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ComposeExtensionRegistryTest {
    private class Stub(
        override val id: String,
        override val nodeTypes: Set<String>,
    ) : ComposeNodeExtension {
        @Composable
        override fun render(
            node: JsonObject,
            context: ComposeExtensionRenderContext,
            modifier: Modifier,
        ) = Unit
    }

    @Test
    fun resolvesOneExactOwnerPerNodeType() {
        val first = Stub("first", setOf("example.one"))
        val second = Stub("second", setOf("example.two"))
        val registry = ComposeExtensionRegistry(listOf(first, second))

        assertSame(first, registry.rendererFor("example.one"))
        assertSame(second, registry.rendererFor("example.two"))
        assertEquals(setOf("example.one", "example.two"), registry.nodeTypes)
    }

    @Test
    fun rejectsDuplicateNodeOwnership() {
        assertThrows(IllegalArgumentException::class.java) {
            ComposeExtensionRegistry(
                listOf(
                    Stub("first", setOf("example.node")),
                    Stub("second", setOf("example.node")),
                ),
            )
        }
    }

    @Test
    fun configurationRequiresEveryInstalledNodeToBeAdvertised() {
        val registry = ComposeExtensionRegistry(
            listOf(Stub("first", setOf("example.node"))),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ComposeRendererConfiguration(emptySet(), emptySet(), registry)
        }
    }
}
