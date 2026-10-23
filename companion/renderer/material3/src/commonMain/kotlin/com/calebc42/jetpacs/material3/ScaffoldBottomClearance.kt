// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much of the window's bottom edge the rendered scaffolds occupy.
 *
 * Receiver-owned overlays drawn over a surface, such as the offline banner,
 * have no view of the surface's chrome: a bottom navigation bar, the system
 * navigation inset under it, and a floating action button are all inside the
 * Emacs-authored scaffold. Each composed scaffold reports its own bottom
 * clearance here while it is in the composition, and an overlay reads the
 * largest one so it sits above whichever chrome is showing on any window
 * size. Presentation only: nothing here reaches the wire.
 */
@Stable
class ScaffoldBottomClearance {
    private val reports = mutableStateMapOf<Any, Dp>()

    /** The largest reported clearance, or 0 dp when no scaffold reports. */
    val bottom: Dp get() = reports.values.maxOrNull() ?: 0.dp

    /** True when at least one scaffold is reporting. */
    val reported: Boolean get() = reports.isNotEmpty()

    internal fun report(owner: Any, value: Dp) {
        if (reports[owner] != value) reports[owner] = value
    }

    internal fun clear(owner: Any) {
        reports.remove(owner)
    }
}

/** The clearance an enclosing host collects; null when no host listens. */
val LocalScaffoldBottomClearance = staticCompositionLocalOf<ScaffoldBottomClearance?> { null }
