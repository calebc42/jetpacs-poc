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
    private class ExtensionStub(
        override val id: String,
        override val extensionId: String,
        override val nodeTypes: Set<String>,
    ) : ComposeNodeExtension {
        @Composable
        override fun render(
            node: JsonObject,
            context: ComposeExtensionRenderContext,
            modifier: Modifier,
        ) = Unit
    }

    private class CanonicalStub(
        override val id: String,
        override val designScope: String,
        override val nodeTypes: Set<String>,
    ) : ComposeCanonicalNodeOverride {
        @Composable
        override fun render(
            node: JsonObject,
            context: ComposeNodeRenderContext,
            modifier: Modifier,
        ) = Unit
    }

    @Test
    fun resolvesOneExactOwnerPerExtensionNodeType() {
        val first = ExtensionStub("first", "example.design", setOf("example.one"))
        val second = ExtensionStub("second", "example.design", setOf("example.two"))
        val registry = ComposeExtensionRegistry(listOf(first, second))

        assertSame(first, registry.rendererFor("example.one"))
        assertSame(second, registry.rendererFor("example.two"))
        assertEquals(setOf("example.one", "example.two"), registry.nodeTypes)
        assertEquals(setOf("example.design"), registry.extensionIds)
    }

    @Test
    fun rejectsDuplicateExtensionNodeOwnership() {
        assertThrows(IllegalArgumentException::class.java) {
            ComposeExtensionRegistry(
                listOf(
                    ExtensionStub("first", "example.first", setOf("example.node")),
                    ExtensionStub("second", "example.second", setOf("example.node")),
                ),
            )
        }
    }

    @Test
    fun configurationRequiresExtensionNodeAndOwnerAdmissionTogether() {
        val registry = ComposeExtensionRegistry(
            listOf(ExtensionStub("first", "example.design", setOf("example.node"))),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ComposeRendererConfiguration(
                appNodeTypes = setOf("example.node"),
                dialogNodeTypes = emptySet(),
                extensions = registry,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ComposeRendererConfiguration(
                appNodeTypes = emptySet(),
                dialogNodeTypes = emptySet(),
                appExtensions = setOf("example.design"),
                extensions = registry,
            )
        }
    }

    @Test
    fun canonicalOverridesResolveByScopeAndNodeWithoutChangingProfileSets() {
        val first = CanonicalStub("first", "example.first", setOf("text", "button"))
        val second = CanonicalStub("second", "example.second", setOf("text"))
        val registry = ComposeCanonicalOverrideRegistry(listOf(first, second))
        val appNodes = setOf("text", "button")
        val appExtensions = setOf("example.first", "example.second")
        val configuration = ComposeRendererConfiguration(
            appNodeTypes = appNodes,
            dialogNodeTypes = emptySet(),
            appExtensions = appExtensions,
            canonicalOverrides = registry,
        )

        assertSame(first, registry.rendererFor("example.first", "text"))
        assertSame(first, registry.rendererFor("example.first", "button"))
        assertSame(second, registry.rendererFor("example.second", "text"))
        assertEquals(appNodes, configuration.appNodeTypes)
        assertEquals(appExtensions, configuration.appExtensions)
    }

    @Test
    fun rejectsDuplicateCanonicalPairAndImplementationIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            ComposeCanonicalOverrideRegistry(
                listOf(
                    CanonicalStub("first", "example.design", setOf("text")),
                    CanonicalStub("second", "example.design", setOf("text")),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ComposeCanonicalOverrideRegistry(
                listOf(
                    CanonicalStub("same", "example.first", setOf("text")),
                    CanonicalStub("same", "example.second", setOf("button")),
                ),
            )
        }
    }

    @Test
    fun acceptsOptionalCanonicalNodesAndRejectsDownstreamOrInventedNodes() {
        val editor = CanonicalStub("editor", "example.design", setOf("editor"))
        val registry = ComposeCanonicalOverrideRegistry(listOf(editor))
        assertSame(editor, registry.rendererFor("example.design", "editor"))

        listOf("jetpacs.scope", "invented").forEach { nodeType ->
            assertThrows(IllegalArgumentException::class.java) {
                ComposeCanonicalOverrideRegistry(
                    listOf(
                        CanonicalStub("bad-$nodeType", "example.design", setOf(nodeType)),
                    ),
                )
            }
        }
    }

    @Test
    fun configurationRejectsUnadmittedScopeAndIncompleteTargetProfile() {
        val registry = ComposeCanonicalOverrideRegistry(
            listOf(CanonicalStub("first", "example.design", setOf("text"))),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ComposeRendererConfiguration(
                appNodeTypes = setOf("text"),
                dialogNodeTypes = emptySet(),
                canonicalOverrides = registry,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ComposeRendererConfiguration(
                appNodeTypes = emptySet(),
                dialogNodeTypes = emptySet(),
                appExtensions = setOf("example.design"),
                canonicalOverrides = registry,
            )
        }
    }
}
