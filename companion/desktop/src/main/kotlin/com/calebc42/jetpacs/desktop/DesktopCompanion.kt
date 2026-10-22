// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import com.calebc42.jetpacs.companion.host.CompanionProcess
import com.calebc42.jetpacs.companion.host.DeviceBridge
import com.calebc42.jetpacs.companion.host.TransportStatus
import com.calebc42.jetpacs.core.database.buildJetpacsDatabase
import com.calebc42.jetpacs.core.ebpstore.KeychainPairingCredentials
import com.calebc42.jetpacs.core.ebpstore.SecretStore
import com.calebc42.jetpacs.core.ebpstore.openSecretStore
import com.calebc42.jetpacs.material3.ImageCache
import java.awt.EventQueue
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The desktop composition root (D5 of docs/PLAN-desktop-companion.md): the
 * Room database in the private data directory, the credentials in the OS
 * keychain or SPEC §9.1's floor, the one pairing, and the shared
 * `CompanionProcess` and `DeviceBridge` over the desktop platform adapters.
 * `main` builds one and hands it to the window; a test builds one on a
 * temporary directory with a direct UI executor and an ephemeral port.
 */
class DesktopCompanion private constructor(
    val config: DesktopConfig,
    val pairing: DesktopPairing,
    val process: CompanionProcess,
    val bridge: DeviceBridge,
    val toasts: DesktopToasts,
    val settings: DesktopSettings,
    /** Why the OS keychain was passed over, when it was; shown with the floor notice. */
    val keychainReports: List<String>,
) {
    /** What the transport is doing; `Listening(port)` carries an ephemeral port. */
    val transportStatus: StateFlow<TransportStatus> get() = bridge.transportStatus

    fun start(): Boolean = process.startBridge(bridge)

    fun stop(): Boolean = process.stopBridge(bridge)

    /** Stop the bridge, wait for the transport to go idle, then close Room. */
    suspend fun shutdown() {
        stop()
        withTimeoutOrNull(SHUTDOWN_MILLIS) { transportStatus.first { !it.isActive } }
        process.database.close()
    }

    companion object {
        const val SETTINGS_FILE = "settings.properties"
        private const val SHUTDOWN_MILLIS = 5_000L

        suspend fun create(
            config: DesktopConfig,
            uiExecutor: Executor = Executor(EventQueue::invokeLater),
            secretStore: SecretStore? = null,
        ): DesktopCompanion {
            val reports = mutableListOf<String>()
            val database = buildJetpacsDatabase(config.dataDirectory)
            val secrets = secretStore ?: openSecretStore(config.dataDirectory, report = reports::add)
            val credentials = KeychainPairingCredentials(secrets)
            val pairing = DesktopPairing.loadOrCreate(database, credentials, config.devKnownAnswerPairing)
            val toasts = DesktopToasts()
            val settings = DesktopSettings(config.dataDirectory.resolve(SETTINGS_FILE))
            val platform = DesktopHostPlatform(config, credentials.protection, toasts, uiExecutor, settings)
            // LD-11: an eighth of the heap is the image retention budget, as on Android.
            ImageCache.configure(Runtime.getRuntime().maxMemory() / 8)
            val process = CompanionProcess.create(
                database = database,
                pairingId = pairing.pairingId,
                aliases = pairing.aliases,
                credentials = credentials,
                platform = platform,
                renderer = DesktopCompanionRenderer,
            )
            val presentation = process.presentation
            val bridge = DeviceBridge(
                platform = platform,
                renderer = DesktopCompanionRenderer,
                stores = process.stores,
                proofProvider = credentials.proofProvider,
                onSurfaceChanged = presentation.onSurfaceChanged,
                onReadyConnectionChanged = { disconnectedAtEpochMs ->
                    database.pairingDao().setReadyDisconnectedAt(pairing.pairingId.value, disconnectedAtEpochMs)
                },
                onAppSurfaceCacheLoaded = presentation.onAppSurfaceCacheLoaded,
                // SPEC 15.1: storage failure and queue exhaustion MUST reach the user.
                onQueueProblem = { message -> toasts.show("EBP queue: $message") },
                onDialogChanged = presentation.onDialogChanged,
                onToast = toasts::show,
                onTheme = presentation.onTheme,
                onPieMenuChanged = presentation.onPieMenuChanged,
                onOpenSurface = presentation.onOpenSurface,
                onOpenSettings = presentation.onOpenSettings,
            )
            return DesktopCompanion(config, pairing, process, bridge, toasts, settings, reports)
        }
    }
}
