// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import androidx.compose.runtime.staticCompositionLocalOf

/** Renderer selection followed by every cached app-surface composition. */
internal val LocalCompanionRendererConfiguration =
    staticCompositionLocalOf {
        AndroidCompanionRenderer.composeConfiguration
    }
