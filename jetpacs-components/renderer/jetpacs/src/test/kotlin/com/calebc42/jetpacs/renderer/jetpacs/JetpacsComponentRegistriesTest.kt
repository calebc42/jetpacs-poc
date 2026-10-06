// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import com.calebc42.ebp.renderer.compose.ComposeComponentRegistry
import com.calebc42.ebp.renderer.compose.ComposeNodeRenderContext
import com.calebc42.ebp.renderer.compose.asComponent
import com.calebc42.ebp.renderer.model.vocabulary.generated.CORE_NODE_MEMBERS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The two extension renderers dispatch through component registries; their
 * manifests stay the authority for what each owns, and every component reads
 * through the member set generated from that manifest.
 */
class JetpacsComponentRegistriesTest {
    @Test
    fun componentsRegistryOwnsTheManifestNodeTypesThroughGeneratedMembers() {
        val registry = JetpacsComponentsRenderer.registry
        assertEquals(JETPACS_COMPONENTS_NODE_SCHEMA.keys, registry.nodeTypes)
        assertEquals(JetpacsComponentsRenderer.nodeTypes, registry.nodeTypes)
        for (component in registry.components) {
            assertSame(JETPACS_COMPONENTS_NODE_MEMBERS.getValue(component.nodeType), component.members)
        }
    }

    @Test
    fun designRegistryOwnsTheManifestNodeTypesThroughGeneratedMembers() {
        val registry = JetpacsDesignRenderer.registry
        assertEquals(JETPACS_DESIGN_NODE_SCHEMA.keys, registry.nodeTypes)
        assertEquals(JetpacsDesignRenderer.nodeTypes, registry.nodeTypes)
        for (component in registry.components) {
            assertSame(JETPACS_DESIGN_NODE_MEMBERS.getValue(component.nodeType), component.members)
        }
    }

    @Test
    fun everyCanonicalOverridePresentsAContractNodeWithOneOwnerPerScope() {
        val overrides = JetpacsDesignCanonicalOverrides +
            listOf(JetpacsTextInputRenderer, JetpacsEditorRenderer)
        for ((scope, scoped) in overrides.groupBy { it.designScope }) {
            val components = scoped.flatMap { override ->
                override.nodeTypes.map { type ->
                    val members = checkNotNull(CORE_NODE_MEMBERS[type]) {
                        "$scope override ${override.id} presents $type, which the contract does not define"
                    }
                    override.asComponent(type, members)
                }
            }
            // Building the registry is the proof: it rejects a second owner.
            val registry = ComposeComponentRegistry<ComposeNodeRenderContext>(components)
            assertEquals(scoped.flatMap { it.nodeTypes }.toSet(), registry.nodeTypes)
        }
    }
}
