// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.content.Context

/** Device-global, receiver-owned persistence for the experimental runtime. */
internal object ExperimentalElispDesignRuntime {
    const val LABEL = "Experimental Elisp design runtime"
    private const val PREFERENCES = "jetpacs_experiments"
    private const val ENABLED = "elisp_design_runtime_enabled"

    fun isEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(ENABLED, false)

    /** Synchronous persistence is intentional: publication and reconnect follow it. */
    fun persist(context: Context, enabled: Boolean): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .commit()
}

/** Ordered toggle transaction, split out so failure and reconnect are unit-testable. */
internal fun applyExperimentalDesignRuntimeChange(
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
