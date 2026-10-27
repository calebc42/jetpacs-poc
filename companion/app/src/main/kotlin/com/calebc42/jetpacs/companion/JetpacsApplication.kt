// SPDX-License-Identifier: GPL-3.0-or-later
// Process-lifetime composition root (SPEC 21, RF-0.5a). It constructs exactly
// one Room store, command actor, and bridge. The user-enabled foreground
// service owns bridge/listener lifetime; alarms and receivers can still use
// the same actor and durable projections without claiming a live connection.
package com.calebc42.jetpacs.companion

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.calebc42.jetpacs.companion.host.AppSurfaceRegistry
import com.calebc42.jetpacs.companion.host.CompanionPresentation
import com.calebc42.jetpacs.companion.host.CompanionProcess
import com.calebc42.jetpacs.companion.host.CompanionStores
import com.calebc42.jetpacs.companion.host.DeviceBridge
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

class JetpacsApplication : Application() {

    // Process-owned presentation state lives in the shared CompanionPresentation:
    // the bridge writes, any Activity observes. These members keep the Activity,
    // the receivers and the widgets reading it under their existing names. An
    // Activity is a pure renderer of these flows — no bridge callback may close
    // over one (RF-0.5a's gate condition).
    val presentation: CompanionPresentation get() = container.presentation
    internal val appSurfaceCatalog: StateFlow<AppSurfaceRegistry.Catalog>
        get() = presentation.appSurfaceCatalog
    fun appSurface(surfaceId: String): StateFlow<JsonObject?> = presentation.appSurface(surfaceId)
    val dialogs: StateFlow<List<CompanionPresentation.DialogShow>> get() = presentation.dialogs
    val theme: StateFlow<JsonObject?> get() = presentation.theme
    val currentPieMenu: StateFlow<Pair<String, JsonObject>?> get() = presentation.currentPieMenu
    val settingsOpen: StateFlow<Boolean> get() = presentation.settingsOpen
    fun dismissSettings() = presentation.dismissSettings()
    val keepScreenOn: StateFlow<Boolean> get() = presentation.keepScreenOn
    internal fun setKeepScreenOn(enabled: Boolean) = presentation.setKeepScreenOn(enabled)
    val surfaceOpenRequest: StateFlow<CompanionPresentation.SurfaceOpenRequest?>
        get() = presentation.surfaceOpenRequest
    fun dismissSurfaceOpen(epoch: Long) = presentation.dismissSurfaceOpen(epoch)
    internal fun requestSurfaceOpenFromPlatform(surfaceId: String) =
        presentation.requestSurfaceOpen(surfaceId)

    lateinit var bridge: DeviceBridge
        private set
    lateinit var container: CompanionProcess
        private set
    val stores: CompanionStores get() = container.stores

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        // docs/PLAN-bundled-emacs.md: the bundled flavor's engine process hosts
        // GNU Emacs and nothing of the Companion. Room, the KeyStore, the
        // bridge and the widget coordinator belong to the Companion process;
        // `bridge` and `container` stay uninitialized there by design.
        if (EngineFlavor.isEngineProcess(this)) {
            EngineFlavor.prepareEngineProcess(this)
            return
        }
        val processStartedAtEpochMs = System.currentTimeMillis().coerceAtLeast(0)
        // LD-11: size the image cache to this device's per-app memory class.
        // An eighth of the app heap is a conservative retention budget — the
        // Semaphore(3) already bounds concurrent decodes on top of it.
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        com.calebc42.jetpacs.material3.ImageCache.configure(
            am.memoryClass.toLong() * 1024 * 1024 / 8)
        container = JetpacsProcessContainer.create(this)
        val presentation = container.presentation
        // The best-effort FGS owns listener lifetime. This process root constructs the
        // bridge once, but does not claim that process existence means the
        // listener or an Emacs session is alive.
        bridge = DeviceBridge(
            platform = container.platform,
            renderer = AndroidCompanionRenderer,
            stores = container.stores,
            proofProvider = container.credentials.proofProvider,
            onSurfaceChanged = presentation.onSurfaceChanged,
            onWidgetSurfaceChanged = { surface ->
                WidgetUpdateCoordinator.enqueueSurface(this, surface)
            },
            onReadyConnectionChanged = { disconnectedAtEpochMs ->
                container.database.pairingDao().setReadyDisconnectedAt(
                    container.pairingId.value,
                    disconnectedAtEpochMs,
                )
                // A session reached READY: a held SPEC 5.8 wake binding is released.
                if (disconnectedAtEpochMs == null) EngineFlavor.onSessionReady(this)
                WidgetUpdateCoordinator.enqueueAll(this)
            },
            onAppSurfaceCacheLoaded = presentation.onAppSurfaceCacheLoaded,
            // SPEC 15.1: storage failure and queue exhaustion MUST reach the
            // user as a visible diagnostic.
            onQueueProblem = { message ->
                mainHandler.post {
                    Toast.makeText(this, "EBP queue: $message",
                                   Toast.LENGTH_LONG).show()
                }
            },
            onDialogChanged = presentation.onDialogChanged,
            onToast = { text ->
                mainHandler.post {
                    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
                }
            },
            onTheme = presentation.onTheme,
            onPieMenuChanged = presentation.onPieMenuChanged,
            onOpenSurface = presentation.onOpenSurface,
            onOpenSettings = presentation.onOpenSettings,
            // SPEC 5: the flavor's profile (loopback-tcp, or the bundled
            // flavor's loopback-unix socket) and its wake, if it has one.
            transport = EngineFlavor.transport(this, container.platform),
            onWake = EngineFlavor.wakeRequester(this),
        )
        container.stores.wakeRequester = EngineFlavor.wakeRequester(this)
        WidgetUpdateCoordinator.start(this, processStartedAtEpochMs)
    }
}
