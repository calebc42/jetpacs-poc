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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicLong

class JetpacsApplication : Application() {

    // Process-owned presentation state: the bridge writes, any Activity
    // observes. An Activity is a pure renderer of these flows — no bridge
    // callback may close over one (RF-0.5a's gate condition).
    private val appSurfaces = AppSurfaceRegistry()
    internal val appSurfaceCatalog: StateFlow<AppSurfaceRegistry.Catalog>
        get() = appSurfaces.catalog
    fun appSurface(surfaceId: String): StateFlow<JsonObject?> =
        appSurfaces.surface(surfaceId)
    // D-3(d): `epoch` advances on EVERY show, so a same-id dialog is a
    // DISTINCT value twice over — the renderer keys its per-dialog field
    // state on it (a same-id successor must never inherit its
    // predecessor's typed fields, SPEC 18.1), and the StateFlow's
    // equality-conflation can never swallow a re-show whose id and spec
    // are structurally identical to what is already on screen.
    data class DialogShow(val id: String, val spec: JsonObject, val epoch: Long)
    private var dialogEpoch = 0L
    private val _currentDialog = MutableStateFlow<DialogShow?>(null)
    val currentDialog: StateFlow<DialogShow?> get() = _currentDialog
    // SPEC 18.4: the accepted theme payload ({dark, colors, syntax}) to mirror,
    // or null for the native scheme (dark = follow-system, amendment #36).
    private val _theme = MutableStateFlow<JsonObject?>(null)
    val theme: StateFlow<JsonObject?> get() = _theme
    private val _currentPieMenu = MutableStateFlow<Pair<String, JsonObject>?>(null)
    val currentPieMenu: StateFlow<Pair<String, JsonObject>?> get() = _currentPieMenu
    // SPEC 14.2 companion.settings.open (R4): the Companion's OWN settings
    // sheet — receiver-local presentation state, no wire member anywhere.
    private val _settingsOpen = MutableStateFlow(false)
    val settingsOpen: StateFlow<Boolean> get() = _settingsOpen
    fun dismissSettings() { _settingsOpen.value = false }
    private val _keepScreenOn = MutableStateFlow(false)
    val keepScreenOn: StateFlow<Boolean> get() = _keepScreenOn
    internal fun setKeepScreenOn(enabled: Boolean) { _keepScreenOn.value = enabled }

    // SPEC 14.2 `surface.open`: an occurrence is receiver-local host-shell
    // navigation, not a surface refresh. The epoch prevents StateFlow's
    // equality conflation from swallowing two taps on the same destination.
    data class SurfaceOpenRequest(
        val surfaceId: String,
        val epoch: Long,
    )
    private val surfaceOpenEpoch = AtomicLong()
    private val _surfaceOpenRequest = MutableStateFlow<SurfaceOpenRequest?>(null)
    val surfaceOpenRequest: StateFlow<SurfaceOpenRequest?> get() = _surfaceOpenRequest
    fun dismissSurfaceOpen(epoch: Long) {
        if (_surfaceOpenRequest.value?.epoch == epoch) {
            _surfaceOpenRequest.value = null
        }
    }

    private fun requestSurfaceOpen(surfaceId: String) {
        _surfaceOpenRequest.value = SurfaceOpenRequest(
            surfaceId = surfaceId,
            epoch = surfaceOpenEpoch.incrementAndGet(),
        )
    }

    lateinit var bridge: DeviceBridge
        private set
    lateinit var container: JetpacsProcessContainer
        private set
    val stores: CompanionStores get() = container.stores

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        // LD-11: size the image cache to this device's per-app memory class.
        // An eighth of the app heap is a conservative retention budget — the
        // Semaphore(3) already bounds concurrent decodes on top of it.
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        com.calebc42.glasspane.material3.ImageCache.configure(
            am.memoryClass.toLong() * 1024 * 1024 / 8)
        container = JetpacsProcessContainer.create(this)
        // The best-effort FGS owns listener lifetime. This process root constructs the
        // bridge once, but does not claim that process existence means the
        // listener or an Emacs session is alive.
        bridge = DeviceBridge(
            this,
            stores = container.stores,
            proofProvider = container.proofProvider,
            onSurfaceChanged = { surface, spec ->
                appSurfaces.publish(surface, spec)
            },
            onAppSurfaceCacheLoaded = appSurfaces::markLoaded,
            // SPEC 15.1: storage failure and queue exhaustion MUST reach the
            // user as a visible diagnostic.
            onQueueProblem = { message ->
                mainHandler.post {
                    Toast.makeText(this, "EBP queue: $message",
                                   Toast.LENGTH_LONG).show()
                }
            },
            onDialogChanged = { id, spec ->
                // SPEC 18.1: one outstanding dialog presented at a time here.
                _currentDialog.value =
                    if (spec != null && id != null)
                        DialogShow(id, spec, ++dialogEpoch)
                    else null
            },
            onToast = { text ->
                mainHandler.post {
                    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
                }
            },
            onTheme = { payload -> _theme.value = payload },
            onPieMenuChanged = { id, spec ->
                _currentPieMenu.value = if (spec != null) id to spec else null
            },
            onOpenSurface = ::requestSurfaceOpen,
            onOpenSettings = { _settingsOpen.value = true })
    }
}
