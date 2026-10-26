// SPDX-License-Identifier: GPL-3.0-or-later
// W4 host, RF-0.5a shape: a PURE OBSERVER of process-owned state.
// JetpacsApplication constructs the bridge and owns every presentation flow;
// the user-enabled best-effort FGS starts it. Rotation recreates the Activity
// freely without constructing another store, actor, or listener. The window
// composition itself is the shared one in :host; this Activity supplies the
// Android half of its two seams and the onboarding wizard.
package com.calebc42.jetpacs.companion

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.calebc42.ebp.renderer.compose.ComposeIconResolver
import com.calebc42.ebp.renderer.compose.LocalComposeIconResolver
import com.calebc42.jetpacs.companion.host.DeviceBridge
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

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as JetpacsApplication
        val bridge = app.bridge
        // MainActivity is exported only because it is the launcher target.
        // Refuse any foreign action before it can prompt for permission or
        // enable the durable background bridge. A valid platform shortcut is
        // resolved from app-private state at this same boundary.
        if (!handlePlatformIntent(intent, bridge)) {
            finish()
            return
        }
        // SPEC 18.5/18.6: request notification presentation permission —
        // the one duty that genuinely needs an Activity, so it stays.
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        // RF-0.5a: the bridge and every presentation flow are process-owned
        // (JetpacsApplication). SPEC 14.4's surface-ID-travels-with-spec pairing
        // and 18.1's one-outstanding-dialog rule live where the state does.
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        // First visible launch opts into the best-effort background bridge.
        // The service owns the listener and its durable enabled flag.
        JetpacsBridgeService.enable(this)
        val hostUi = AndroidHostUi(this)
        setContent {
            // SPEC 18.4: mirror the pushed palette (colors/dark), or the native
            // scheme following the system when no theme is set.
            val themePayload by app.theme.collectAsState()
            val keepScreenOn by app.keepScreenOn.collectAsState()
            val rendererInstallation by bridge.rendererInstallation.collectAsState()
            DisposableEffect(keepScreenOn) {
                if (keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            // SPEC 20.1.1: report the window geometry on first composition
            // and every configuration change (rotation, fold, resize).
            val config = LocalConfiguration.current
            LaunchedEffect(config.screenWidthDp, config.screenHeightDp) {
                bridge.windowChanged(config.screenWidthDp, config.screenHeightDp)
            }
            // SPEC 20.1.3 (#205): the appearance flipped, or the activity was
            // recreated for a new wallpaper; the engine drops an unchanged report.
            LaunchedEffect(config.uiMode) { bridge.colorsChanged() }
            CompositionLocalProvider(
                LocalHostUiPlatform provides hostUi,
                LocalCompanionRendererConfiguration provides
                    rendererInstallation.composeConfiguration,
                // One glyph set for both renderers: the Material-free design
                // renderer draws the same named icons Glasspane resolves.
                LocalComposeIconResolver provides ComposeIconResolver(IconMap::get),
                // Its authored touch ripples are the platform's, as Material draws them.
                LocalDesignRippleFactory provides PlatformDesignRipple,
            ) {
                EbpTheme(themePayload) {
                    ProvideJetpacsTheme(themePayload) {
                        val context = LocalContext.current
                        var onboardingRequired by remember {
                            mutableStateOf(!isCurrentOnboardingComplete(context))
                        }
                        Surface(Modifier.fillMaxSize()) {
                            Box {
                                // Nav3 owns receiver destinations; EBP view.switch remains
                                // inside the selected Surface entry. Each process-owned
                                // overlay still reads its own flow in a separate scope, so
                                // opening one cannot recompose a large surface document.
                                JetpacsNavHost(
                                    presentation = app.presentation,
                                    bridge = bridge,
                                    pairingId = app.container.pairingId.value,
                                    onboardingRequired = onboardingRequired,
                                    onOnboardingComplete = {
                                        markCurrentOnboardingComplete(this@MainActivity)
                                        onboardingRequired = false
                                    },
                                    onExit = { finish() },
                                    pairingContent = { onDone, onCancel ->
                                        OnboardingFlow(onDone = onDone, onCancel = onCancel)
                                    },
                                )
                                PieMenuHost(app.currentPieMenu, bridge)
                                DialogHost(app.dialogs, bridge)
                                ConfirmHost(bridge)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!handlePlatformIntent(
                intent,
                (application as JetpacsApplication).bridge,
            )) {
            setIntent(Intent())
        }
    }

    /** Validate exported launcher ingress before resolving private state. */
    private fun handlePlatformIntent(intent: Intent?, bridge: DeviceBridge): Boolean {
        if (intent?.action == null || intent.action == Intent.ACTION_MAIN) return true
        if (intent.action != PlatformShortcuts.ACTION_INVOKE) return false
        val pairingIdentity = (application as JetpacsApplication).container.pairingId.value
        val action = PlatformShortcuts.resolveLaunch(this, pairingIdentity, intent)
        // Rotation must not replay a shortcut occurrence, and the opaque token
        // must not linger in the Activity's retained Intent. A fresh launcher
        // tap delivers a fresh onNewIntent.
        setIntent(Intent())
        action ?: return false
        bridge.dispatchPlatformAction(action.descriptor, action.injected)
        return true
    }
}
