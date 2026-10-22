// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.calebc42.ebp.renderer.compose.ComposeRendererConfiguration

/**
 * Renderer selection followed by every cached app-surface composition. The
 * host's root provides the installation the bridge currently publishes; no
 * default exists, because the installation is a composition-root decision.
 */
val LocalCompanionRendererConfiguration = staticCompositionLocalOf<ComposeRendererConfiguration> {
    error("CompanionRendererInstallation is not provided above this composition")
}
