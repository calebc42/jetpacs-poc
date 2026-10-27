// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

import android.content.Context
import android.content.Intent

/**
 * The Companion-process side of the engine supervisor
 * (docs/PLAN-bundled-emacs.md): it starts and stops the engine's foreground
 * service. It is neither EBP endpoint; the Companion (`DeviceBridge`) still
 * only binds a listener and the engine's Emacs still dials and authenticates
 * like any other, so no protocol requirement rests on this object.
 */
object EngineSupervisor {
    /**
     * Start the engine as a foreground service. Callers are the launcher
     * activity and, later, the boot and update receivers; a start from a
     * background position may be refused by the platform, which the service
     * reports rather than this call.
     */
    fun start(context: Context) {
        context.startForegroundService(Intent(context, JetpacsEngineService::class.java))
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, JetpacsEngineService::class.java))
    }

    @Volatile private var wake: EngineWake? = null

    private fun wake(context: Context): EngineWake =
        wake ?: synchronized(this) { wake ?: EngineWake.forApplication(context).also { wake = it } }

    /** SPEC 5.8: the bound-service wake after a durable `wake` admission. */
    fun requestWake(context: Context): Boolean = wake(context).request()

    /** A session reached READY: the wake binding, if held, has done its job. */
    fun releaseWake(context: Context) {
        wake?.release("ready")
    }
}
