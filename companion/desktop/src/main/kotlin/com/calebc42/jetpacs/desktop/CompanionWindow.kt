// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import com.calebc42.ebp.renderer.compose.ComposeIconResolver
import com.calebc42.ebp.renderer.compose.LocalComposeIconResolver
import com.calebc42.jetpacs.companion.host.ui.ConfirmHost
import com.calebc42.jetpacs.companion.host.ui.DialogHost
import com.calebc42.jetpacs.companion.host.ui.JetpacsNavHost
import com.calebc42.jetpacs.companion.host.ui.LocalCompanionRendererConfiguration
import com.calebc42.jetpacs.companion.host.ui.LocalHostUiPlatform
import com.calebc42.jetpacs.companion.host.ui.PieMenuHost
import com.calebc42.jetpacs.companion.host.ui.PlatformDesignRipple
import com.calebc42.jetpacs.material3.EbpTheme
import com.calebc42.jetpacs.material3.IconMap
import com.calebc42.jetpacs.renderer.jetpacs.LocalDesignRippleFactory
import com.calebc42.jetpacs.renderer.jetpacs.ProvideJetpacsTheme

/**
 * The desktop window: the same tree `MainActivity` composes on Android, with
 * the desktop halves of the two seams. [windowSize] is read in composition,
 * so the window state drives `bridge.windowChanged` (SPEC 20.1.1) and the
 * dialog height cap.
 */
@Composable
fun CompanionWindow(
    companion: DesktopCompanion,
    windowSize: () -> DpSize,
    onExit: () -> Unit,
) {
    val presentation = companion.process.presentation
    val bridge = companion.bridge
    // SPEC 18.4: mirror the pushed palette (colors/dark), or the native
    // scheme following the system when no theme is set.
    val themePayload by presentation.theme.collectAsState()
    val rendererInstallation by bridge.rendererInstallation.collectAsState()
    val size = windowSize()
    LaunchedEffect(size) {
        bridge.windowChanged(size.width.value.toInt(), size.height.value.toInt())
    }
    val hostUi = remember(companion) {
        DesktopHostUi(windowSize, companion.config.endpoint, companion.config.waitingDescription)
    }
    // A freshly generated token must be shown before anything else, whatever
    // an older settings file says.
    var onboardingRequired by remember {
        mutableStateOf(companion.pairing.freshTokenDisplay != null || !companion.settings.onboardingComplete)
    }
    CompositionLocalProvider(
        LocalHostUiPlatform provides hostUi,
        LocalCompanionRendererConfiguration provides rendererInstallation.composeConfiguration,
        // One glyph set for both renderers: the Material-free design
        // renderer draws the same named icons Glasspane resolves.
        LocalComposeIconResolver provides ComposeIconResolver(IconMap::get),
        // Its authored touch ripples are the platform's, as Material draws them.
        LocalDesignRippleFactory provides PlatformDesignRipple,
    ) {
        EbpTheme(themePayload) {
            ProvideJetpacsTheme(themePayload) {
                Surface(Modifier.fillMaxSize()) {
                    Box {
                        JetpacsNavHost(
                            presentation = presentation,
                            bridge = bridge,
                            pairingId = companion.pairing.pairingId.value,
                            onboardingRequired = onboardingRequired,
                            onOnboardingComplete = {
                                companion.settings.onboardingComplete = true
                                onboardingRequired = false
                            },
                            onExit = onExit,
                            pairingContent = { onDone, onCancel ->
                                DesktopPairingScreen(
                                    pairing = companion.pairing,
                                    config = companion.config,
                                    keychainReports = companion.keychainReports,
                                    onDone = onDone,
                                    onCancel = onCancel,
                                )
                            },
                        )
                        PieMenuHost(presentation.currentPieMenu, bridge)
                        DialogHost(presentation.dialogs, bridge)
                        ConfirmHost(bridge)
                        ToastHost(companion.toasts)
                        TransportStatusStrip(companion.transportStatus, companion.toasts)
                    }
                }
            }
        }
    }
}
