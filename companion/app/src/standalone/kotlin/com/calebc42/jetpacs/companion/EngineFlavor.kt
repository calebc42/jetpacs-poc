// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.app.Application
import android.content.Context
import com.calebc42.jetpacs.companion.host.DEFAULT_LOOPBACK_PORT

/**
 * The standalone flavor pairs with an Emacs the user installs; it carries no
 * engine, so every process of this app is a Companion process and launching
 * the Companion starts nothing (docs/PLAN-bundled-emacs.md).
 */
object EngineFlavor {
    const val bundled: Boolean = false

    /** SPEC 5.2's documented default; `adb forward` recipes rely on it. */
    const val loopbackPort: Int = DEFAULT_LOOPBACK_PORT

    fun isEngineProcess(app: Application): Boolean = false

    fun prepareEngineProcess(app: Application) = Unit

    fun onCompanionLaunched(context: Context) = Unit
}
