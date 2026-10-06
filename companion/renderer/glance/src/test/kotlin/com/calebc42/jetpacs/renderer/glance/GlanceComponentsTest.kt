// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.glance

import com.calebc42.ebp.renderer.model.vocabulary.generated.CORE_NODE_MEMBERS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget profile is the registry: the contribution advertises exactly
 * the component node types, every component reads through the contract's
 * generated member set for its node, and the members the profile says it
 * honours are members that node has.
 */
class GlanceComponentsTest {
    private val widgetTypes = setOf(
        "text", "icon", "row", "column", "box", "surface", "lazy_column", "spacer",
        "divider", "card", "button", "icon_button", "badge", "section_header", "empty_state",
    )

    @Test
    fun theContributionAdvertisesExactlyTheRegistry() {
        assertEquals(widgetTypes, GlanceComponents.nodeTypes)
        assertEquals(GlanceComponents.nodeTypes, GlanceRendererContribution.nodeTypes)
    }

    @Test
    fun everyComponentReadsThroughItsContractMembersAndHonoursOnlyThem() {
        val honoured = checkNotNull(GlanceRendererContribution.memberSupport).nodes
        for (component in GlanceComponents.components) {
            assertSame(CORE_NODE_MEMBERS.getValue(component.nodeType), component.members)
            val outside = honoured.getValue(component.nodeType) - component.members.keys
            assertTrue("${component.nodeType} honours members its contract row lacks: $outside", outside.isEmpty())
        }
    }
}
