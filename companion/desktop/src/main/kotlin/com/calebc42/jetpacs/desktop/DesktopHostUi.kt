// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import com.calebc42.jetpacs.companion.host.CompanionRenderer
import com.calebc42.jetpacs.companion.host.HOST_BUILTINS
import com.calebc42.jetpacs.companion.host.PlatformRendererTargets
import com.calebc42.jetpacs.companion.host.ui.HostUiPlatform

/**
 * The desktop half of the shared window composition: the window's own size,
 * no system back gesture, and no Emacs launch (SPEC §5.8 forbids a desktop
 * Companion from launching or signalling Emacs, so the offline chrome has
 * no such button here).
 */
class DesktopHostUi(
    private val windowSize: () -> DpSize,
    override val listeningEndpoint: String,
) : HostUiPlatform {
    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

    @Composable
    override fun windowSizeDp(): DpSize = windowSize()

    override val openEmacs: (() -> Unit)? = null
}

/**
 * The desktop's renderer installation: the shared app and dialog profiles,
 * no notification, widget or tile target, and every host builtin except
 * `share.send`, which has no desktop share sheet behind it.
 */
object DesktopCompanionRenderer : CompanionRenderer(
    PlatformRendererTargets(
        notification = null,
        widget = null,
        tile = null,
        hostBuiltins = HOST_BUILTINS - "share.send",
    ),
)
