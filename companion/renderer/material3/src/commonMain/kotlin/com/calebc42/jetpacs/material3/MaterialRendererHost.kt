// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import com.calebc42.ebp.renderer.model.RendererActionStateHost
import com.calebc42.ebp.renderer.model.RendererEditorHost
import kotlinx.coroutines.flow.StateFlow

/** Material-only facilities left after neutral action and editor extraction. */
interface MaterialRendererHost : RendererActionStateHost, RendererEditorHost {
    /** Material renderer's latest retained-variant selection projection. */
    val variantSelections: StateFlow<Map<Pair<String, String>, String>>

    /** Saveable-owner incarnations used by Material's retained presentation. */
    val retainedPresentationIncarnations: StateFlow<Map<Pair<String, String>, Long>>

    /** Select one optional item from Material's radial menu presentation. */
    fun pieMenuSelect(menuId: String, categoryIndex: Int, itemIndex: Int?)

    /** Dismiss one ephemeral Material radial menu. */
    fun pieMenuDismiss(menuId: String)
}
