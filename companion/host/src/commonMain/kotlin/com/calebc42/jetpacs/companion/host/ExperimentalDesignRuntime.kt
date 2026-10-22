// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

/** The receiver setting that installs the experimental Elisp design runtime. */
object ExperimentalDesignRuntime {
    const val LABEL = "Experimental Elisp design runtime"
}

/** Ordered toggle transaction, split out so failure and reconnect are unit-testable. */
fun applyExperimentalDesignRuntimeChange(
    current: Boolean,
    requested: Boolean,
    persist: (Boolean) -> Boolean,
    publish: (Boolean) -> Unit,
    closeActiveSession: () -> Unit,
): Boolean {
    if (current == requested) return true
    if (!persist(requested)) return false
    publish(requested)
    closeActiveSession()
    return true
}
