// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.app.Application
import android.content.Context
import com.calebc42.jetpacs.companion.engine.EngineSupervisor
import com.calebc42.jetpacs.companion.engine.JetpacsEngineService
import org.gnu.emacs.EmacsNative

/**
 * The bundled flavor embeds GNU Emacs as an engine process
 * (docs/PLAN-bundled-emacs.md). The application class asks this object which
 * role a process plays before composing anything: the `:emacs` process must
 * never open Room, the KeyStore or the bridge, which belong to the Companion
 * process alone.
 */
object EngineFlavor {
    const val bundled: Boolean = true

    /**
     * Not SPEC 5.2's default 8765: a standalone install on the same device
     * keeps that port (and its `adb forward` recipes), and the engine's init
     * names this one, so neither Companion ever answers the other's Emacs.
     * E4 replaces the port with a private Unix socket.
     */
    const val loopbackPort: Int = 8766

    fun isEngineProcess(app: Application): Boolean =
        Application.getProcessName().endsWith(JetpacsEngineService.PROCESS_SUFFIX)

    /**
     * What upstream's `EmacsApplication.onCreate` does for the Emacs process:
     * block the signals the system thread must not handle, so they reach the
     * Emacs thread (`initEmacs` restores the mask there).
     */
    fun prepareEngineProcess(app: Application) {
        EmacsNative.setupSystemThread()
    }

    /** Opening the Companion starts the engine (decision 1 of the plan). */
    fun onCompanionLaunched(context: Context) {
        EngineSupervisor.start(context)
    }
}
