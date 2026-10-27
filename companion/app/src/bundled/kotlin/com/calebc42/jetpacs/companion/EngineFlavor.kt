// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import com.calebc42.jetpacs.companion.engine.BundledFirstRun
import com.calebc42.jetpacs.companion.engine.EngineHome
import com.calebc42.jetpacs.companion.engine.BundledPairing
import com.calebc42.jetpacs.companion.engine.EngineSupervisor
import com.calebc42.jetpacs.companion.engine.JetpacsEngineService
import com.calebc42.jetpacs.companion.host.CompanionHostPlatform
import com.calebc42.jetpacs.companion.host.CompanionTransport
import com.calebc42.jetpacs.core.database.JetpacsDatabase
import com.calebc42.jetpacs.core.ebpstore.PairingCredentials
import org.gnu.emacs.EmacsNative

/**
 * The bundled flavor embeds GNU Emacs as an engine process
 * (docs/PLAN-bundled-emacs.md). The application class asks this object which
 * role a process plays before composing anything: the `:emacs` process must
 * never open Room, the KeyStore or the bridge, which belong to the Companion
 * process alone.
 */
internal object EngineFlavor {
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

    /**
     * SPEC 9.1 under amendment #209: the Companion generates the pairing once
     * and the engine supervisor delivers the Emacs endpoint's copy through
     * application-private storage; nothing is shown to the user.
     */
    suspend fun resolvePairing(
        app: Context,
        database: JetpacsDatabase,
        credentials: PairingCredentials,
    ): ResolvedPairing = BundledPairing.resolve(app, database, credentials)

    /** There is no separate Emacs to open: make sure the engine runs. */
    fun openEmacs(context: Context): Boolean {
        EngineSupervisor.start(context)
        return true
    }

    /** Recovery is the launcher activity, which starts the engine again. */
    fun reconnectPrompt(context: Context): ReconnectPrompt = ReconnectPrompt(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        "Jetpacs engine stopped",
        "Tap to restart the notes engine.",
    )

    fun listeningDescription(endpoint: String): String =
        "Starting the notes engine…"

    fun listeningEndpoint(context: Context): String = EngineHome.of(context).socketFile.path

    /** SPEC 5.3: the Companion binds a private Unix socket; the engine's init names it. */
    fun transport(context: Context, platform: CompanionHostPlatform): CompanionTransport =
        LocalSocketTransport(EngineHome.of(context).socketFile, platform.log)

    /** Amendment #209: the wake target is the engine's own service, fixed by the application. */
    val wakeCapabilities: Set<String> = setOf("offline.wake")

    fun wakeRequester(context: Context): (() -> Unit)? = {
        EngineSupervisor.requestWake(context.applicationContext)
    }

    fun onSessionReady(context: Context) = EngineSupervisor.releaseWake(context)

    @Composable
    fun OnboardingContent(onDone: () -> Unit, onCancel: (() -> Unit)?) {
        BundledFirstRun(onDone = onDone)
    }
}
