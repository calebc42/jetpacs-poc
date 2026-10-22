// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import com.calebc42.ebp.renderer.model.RendererProfile
import com.calebc42.jetpacs.companion.host.CompanionRenderer
import com.calebc42.jetpacs.companion.host.HOST_BUILTINS
import com.calebc42.jetpacs.companion.host.PlatformRendererTargets
import com.calebc42.jetpacs.material3.NodeSupport
import com.calebc42.jetpacs.renderer.glance.GlanceRendererContribution

/**
 * The renderer installation packaged by the Android Companion: the shared
 * app and dialog profiles plus Android's three platform targets, the
 * notification, the Glance widget and the Quick Settings tile. Every host
 * builtin is executable here, so none is hidden from the profiles.
 */
internal object AndroidCompanionRenderer : CompanionRenderer(
    PlatformRendererTargets(
        notification = NodeSupport.NOTIFICATION_CONTRIBUTION,
        widget = GlanceRendererContribution,
        // A Quick Settings tile has no Node renderer. Its only executable field
        // is a context-less ActionDescriptor, so the profile advertises exactly
        // the builtins DeviceBridge can execute without a surface or dialog.
        tile = RendererProfile(
            nodeTypes = emptySet(),
            builtins = setOf(
                "surface.open",
                "clipboard.copy",
                "companion.settings.open",
                "trigger.fire",
            ),
            features = emptySet(),
            extensions = emptySet(),
        ),
        hostBuiltins = HOST_BUILTINS,
    ),
)
