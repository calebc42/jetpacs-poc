// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * Process-owned presentation state: the bridge writes, any window observes.
 *
 * A window or Activity is a pure renderer of these flows; no bridge callback
 * may close over one (RF-0.5a's gate condition). The `on*` members are the
 * generic half of `DeviceBridge`'s constructor callbacks; a platform adds
 * its own (toasts, widgets, the READY stamp) beside them.
 */
class CompanionPresentation {
    private val appSurfaces = AppSurfaceRegistry()

    /** The present `app:*` surfaces, once the durable cache has been projected. */
    val appSurfaceCatalog: StateFlow<AppSurfaceRegistry.Catalog> get() = appSurfaces.catalog

    /** The shown view of one app surface, or null while it has none. */
    fun appSurface(surfaceId: String): StateFlow<JsonObject?> = appSurfaces.surface(surfaceId)

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
    fun setKeepScreenOn(enabled: Boolean) { _keepScreenOn.value = enabled }

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

    fun requestSurfaceOpen(surfaceId: String) {
        _surfaceOpenRequest.value = SurfaceOpenRequest(
            surfaceId = surfaceId,
            epoch = surfaceOpenEpoch.incrementAndGet(),
        )
    }

    /** SPEC 14.4: the shown surface's ID travels with its spec. */
    val onSurfaceChanged: (String, JsonObject?) -> Unit = appSurfaces::publish

    /** The durable app-surface cache has finished its initial projection. */
    val onAppSurfaceCacheLoaded: () -> Unit = appSurfaces::markLoaded

    /** SPEC 18.1: one outstanding dialog presented at a time here. */
    val onDialogChanged: (String?, JsonObject?) -> Unit = { id, spec ->
        _currentDialog.value =
            if (spec != null && id != null) DialogShow(id, spec, ++dialogEpoch) else null
    }

    /** SPEC 18.4: the accepted theme payload, or null for the native scheme. */
    val onTheme: (JsonObject?) -> Unit = { payload -> _theme.value = payload }

    /** SPEC 18.3: (menu_id, spec) to present; (menu_id, null) to dismiss. */
    val onPieMenuChanged: (String, JsonObject?) -> Unit = { id, spec ->
        _currentPieMenu.value = if (spec != null) id to spec else null
    }

    /** SPEC 14.2 `surface.open`. */
    val onOpenSurface: (String) -> Unit = ::requestSurfaceOpen

    /** SPEC 14.2 `companion.settings.open`. */
    val onOpenSettings: () -> Unit = { _settingsOpen.value = true }
}
