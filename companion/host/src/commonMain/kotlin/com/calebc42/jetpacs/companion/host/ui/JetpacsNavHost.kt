// SPDX-License-Identifier: GPL-3.0-or-later
@file:Suppress("OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")
package com.calebc42.jetpacs.companion.host.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.ui.NavDisplay
import com.calebc42.jetpacs.companion.host.CompanionPresentation
import com.calebc42.jetpacs.companion.host.DeviceBridge
import com.calebc42.jetpacs.core.navigation.JetpacsNavKey
import com.calebc42.jetpacs.core.navigation.openPresentJetpacsSurface
import com.calebc42.jetpacs.core.navigation.pushJetpacsDestination
import com.calebc42.jetpacs.core.navigation.reconcileJetpacsBackStack

/**
 * Receiver-owned application navigation, shared by every host.
 *
 * A Surface key selects an EBP surface. The view inside a multi-view surface
 * remains wholly owned by SurfaceStore + `view.switch`; it is never expanded
 * into this Nav3 back stack. The Pairing destination's content is the host's
 * own ([pairingContent]): Android's onboarding wizard, the desktop's pairing
 * screen.
 */
@Suppress("OPT_IN_USAGE")
@Composable
fun JetpacsNavHost(
    presentation: CompanionPresentation,
    bridge: DeviceBridge,
    pairingId: String,
    onboardingRequired: Boolean,
    onOnboardingComplete: () -> Unit,
    onExit: () -> Unit,
    pairingContent: @Composable (onDone: () -> Unit, onCancel: (() -> Unit)?) -> Unit,
) {
    val surfaceCatalog by presentation.appSurfaceCatalog.collectAsState()
    val surfaceIds = surfaceCatalog.surfaceIds
    val settingsRequested by presentation.settingsOpen.collectAsState()
    val surfaceOpenRequest by presentation.surfaceOpenRequest.collectAsState()
    val initial = if (onboardingRequired) {
        JetpacsNavKey.Pairing
    } else {
        JetpacsNavKey.Surface(pairingId, "app:hub")
    }
    val backStack = rememberJetpacsNavBackStack(initial)

    fun replaceStack(keys: List<JetpacsNavKey>) {
        backStack.clear()
        backStack.addAll(keys)
    }

    fun push(destination: JetpacsNavKey) {
        val current = backStack.toList()
        val next = pushJetpacsDestination(current, destination)
        if (next != current) replaceStack(next)
    }

    fun popOrExit() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        else onExit()
    }

    // A removed surface can survive in platform saved state. Reconcile keys
    // against the durable EBP cache rather than rendering a ghost document.
    LaunchedEffect(onboardingRequired, pairingId, surfaceCatalog) {
        // The cache is loaded off the main thread. Until its completion
        // barrier arrives, an empty ID list says "not loaded", not "removed".
        if (!onboardingRequired && !surfaceCatalog.loaded) return@LaunchedEffect
        val current = backStack.toList()
        val next = reconcileJetpacsBackStack(
            current,
            pairingId,
            surfaceIds.toSet(),
            onboardingRequired,
        )
        if (next != current) replaceStack(next)
    }

    // SPEC 14.2: this builtin opens receiver-local settings. It does not
    // synthesize an EBP action or disturb a Surface's current_view.
    LaunchedEffect(settingsRequested, onboardingRequired) {
        if (settingsRequested) {
            if (!onboardingRequired) push(JetpacsNavKey.Settings)
            presentation.dismissSettings()
        }
    }

    // SPEC 14.2: unlike surface.update, surface.open is an explicit user
    // request to select a receiver-owned Surface destination. Wait for the
    // durable cache hydration barrier, then make an absent target a safe
    // no-op. No EBP current_view or view.switched state participates.
    LaunchedEffect(
        surfaceOpenRequest,
        onboardingRequired,
        pairingId,
        surfaceCatalog,
    ) {
        val request = surfaceOpenRequest ?: return@LaunchedEffect
        if (onboardingRequired) {
            presentation.dismissSurfaceOpen(request.epoch)
            return@LaunchedEffect
        }
        if (request.surfaceId == "companion:settings") {
            push(JetpacsNavKey.Settings)
            presentation.dismissSurfaceOpen(request.epoch)
            return@LaunchedEffect
        }
        if (!surfaceCatalog.loaded) return@LaunchedEffect
        val current = backStack.toList()
        val next = openPresentJetpacsSurface(
            current,
            pairingId,
            surfaceIds.toSet(),
            request.surfaceId,
        )
        if (next != current) replaceStack(next)
        presentation.dismissSurfaceOpen(request.epoch)
    }

    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = ::popOrExit,
        // Nav3's default 700 ms crossfade keeps both complete Surface trees
        // composed and measured.  Surface documents can contain hundreds of
        // rows, so switch atomically and preserve the same back-stack policy
        // without paying for two full screens on every navigation.
        transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
        popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
        predictivePopTransitionSpec = {
            EnterTransition.None togetherWith ExitTransition.None
        },
        entryProvider = entryProvider {
            entry<JetpacsNavKey.Pairing> {
                val requiredRoot = onboardingRequired && backStack.size == 1
                val finishOnboarding = dropUnlessResumed {
                    val wasRoot = backStack.size == 1
                    onOnboardingComplete()
                    if (wasRoot) {
                        replaceStack(listOf(JetpacsNavKey.Surface(pairingId, "app:hub")))
                    } else {
                        backStack.removeAt(backStack.lastIndex)
                    }
                }
                pairingContent(
                    finishOnboarding,
                    if (requiredRoot) {
                        null
                    } else {
                        dropUnlessResumed { popOrExit() }
                    },
                )
            }
            entry<JetpacsNavKey.Catalog> {
                SurfaceCatalogScreen(
                    surfaceIds = surfaceIds,
                    catalogLoaded = surfaceCatalog.loaded,
                    connectedFlow = bridge.connected,
                    onSurface = { surfaceId ->
                        push(JetpacsNavKey.Surface(pairingId, surfaceId))
                    },
                    onSettings = { push(JetpacsNavKey.Settings) },
                    onRepair = { push(JetpacsNavKey.Pairing) },
                )
            }
            entry<JetpacsNavKey.Surface> { key ->
                SurfaceDestination(
                    surfaceId = key.surfaceId,
                    surfaceFlow = presentation.appSurface(key.surfaceId),
                    connectedFlow = bridge.connected,
                    bridge = bridge,
                    onSettings = { push(JetpacsNavKey.Settings) },
                    onRepair = { push(JetpacsNavKey.Pairing) },
                )
            }
            entry<JetpacsNavKey.Settings> {
                JetpacsSettingsScreen(
                    bridge = bridge,
                    onBack = ::popOrExit,
                    onOpenOnboarding = { push(JetpacsNavKey.Pairing) },
                )
            }
        },
    )
}

/**
 * Navigation3's Android convenience overload intentionally returns
 * NavBackStack<NavKey>. Jetpacs has a closed serializable key hierarchy, so
 * retain that concrete type and its generated sealed serializer end-to-end.
 */
@Composable
fun rememberJetpacsNavBackStack(
    vararg elements: JetpacsNavKey,
): NavBackStack<JetpacsNavKey> = rememberSerializable(
    serializer = NavBackStackSerializer<JetpacsNavKey>(),
) {
    NavBackStack(*elements)
}
