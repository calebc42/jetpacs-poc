// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import com.calebc42.jetpacs.companion.engine.BundledFirstRun
import com.calebc42.jetpacs.companion.engine.EngineHome
import com.calebc42.jetpacs.companion.engine.EngineSettingsSection
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

    /**
     * No prompt: in a bundled deployment the engine supervisor owns recovery.
     * A lost session is the sticky service restarting the engine (background
     * running on), the visible binding restarting it (off, window visible),
     * the policy itself (off, window hidden: the engine runs only while the
     * app is open and is woken by SPEC 5.8 for background events), the user's
     * Pause, or the crash-loop halt, which posts its own notice. Each one is
     * recovering by itself, intended or already explained, so a heads-up
     * "engine stopped" here would be noise or false (found on the Pixel
     * Tablet: it fired every time the app was left with background off).
     */
    fun reconnectPrompt(context: Context): ReconnectPrompt? = null

    fun listeningDescription(endpoint: String): String =
        "The first start after an install or update takes a few seconds."

    /**
     * The user of a bundled app has no Emacs of their own: the Companion's
     * own UI names the notes engine, the term the first-run screen and the
     * Engine settings use, and its open action starts that engine.
     */
    val hostWording = com.calebc42.jetpacs.companion.host.HostWording(
        offlineTitle = "The notes engine isn't running",
        offlineDetail = "Showing the last screen it sent.",
        openAction = "Start it",
        waitingTitle = "Starting the notes engine",
        offlineDropNotice = "The notes engine isn't running. Nothing was saved or sent.",
    )

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

    /** While the window is visible the engine runs: as the service, or bound (background off). */
    fun onCompanionVisible(activity: android.app.Activity) = EngineSupervisor.bindWhileVisible(activity)

    fun onCompanionHidden(activity: android.app.Activity) = EngineSupervisor.unbindVisible(activity)

    /** After a reboot or an update the engine comes back when background running is on. */
    fun onBootOrUpdate(context: Context) {
        EngineSupervisor.start(context)
    }

    @Composable
    fun SettingsSection(bridge: com.calebc42.jetpacs.companion.host.DeviceBridge) {
        EngineSettingsSection(bridge)
    }

    @Composable
    fun OnboardingContent(onDone: () -> Unit, onCancel: (() -> Unit)?) {
        BundledFirstRun(onDone = onDone)
    }
}
