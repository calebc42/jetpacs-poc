// SPDX-License-Identifier: GPL-3.0-or-later
// W4 host, RF-0.5a shape: a PURE OBSERVER of process-owned state.
// JetpacsApplication constructs the bridge and owns every presentation flow;
// the user-enabled best-effort FGS starts it. Rotation recreates the Activity
// freely without constructing another store, actor, or listener.
package com.calebc42.jetpacs.companion

import android.os.Bundle
import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calebc42.glasspane.material3.EbpTheme
import com.calebc42.glasspane.material3.RenderDialogRoot
import com.calebc42.glasspane.material3.RenderPieMenu
import com.calebc42.ebp.renderer.compose.ComposeIconResolver
import com.calebc42.ebp.renderer.compose.LocalComposeIconResolver
import com.calebc42.glasspane.material3.IconMap
import com.calebc42.jetpacs.renderer.jetpacs.ProvideJetpacsTheme
import kotlinx.serialization.json.JsonObject

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
        setContent {
            // SPEC 18.4: mirror the pushed palette (colors/dark), or the native
            // scheme following the system when no theme is set.
            val themePayload by app.theme.collectAsState()
            val keepScreenOn by app.keepScreenOn.collectAsState()
            val rendererInstallation by bridge.rendererInstallation.collectAsState()
            androidx.compose.runtime.DisposableEffect(keepScreenOn) {
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
            val config = androidx.compose.ui.platform.LocalConfiguration.current
            androidx.compose.runtime.LaunchedEffect(
                config.screenWidthDp, config.screenHeightDp) {
                bridge.windowChanged(config.screenWidthDp, config.screenHeightDp)
            }
            androidx.compose.runtime.CompositionLocalProvider(
                LocalCompanionRendererConfiguration provides
                    rendererInstallation.composeConfiguration,
                // One glyph set for both renderers: the Material-free design
                // renderer draws the same named icons Glasspane resolves.
                LocalComposeIconResolver provides ComposeIconResolver(IconMap::get),
            ) {
                EbpTheme(themePayload) {
                    ProvideJetpacsTheme(themePayload) {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        var onboardingRequired by androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf(
                                !isCurrentOnboardingComplete(context),
                            )
                        }
                        Surface(Modifier.fillMaxSize()) {
                            androidx.compose.foundation.layout.Box {
                                // Nav3 owns receiver destinations; EBP view.switch remains
                                // inside the selected Surface entry. Each process-owned
                                // overlay still reads its own flow in a separate scope, so
                                // opening one cannot recompose a large surface document.
                                JetpacsNavHost(
                                    app,
                                    bridge,
                                    onboardingRequired = onboardingRequired,
                                    onOnboardingComplete = {
                                        markCurrentOnboardingComplete(this@MainActivity)
                                        onboardingRequired = false
                                    },
                                    onExit = { finish() },
                                )
                                PieMenuHost(app.currentPieMenu, bridge)
                                DialogHost(app.currentDialog, bridge)
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

/**
 * SPEC 14.1 `confirm`: the user confirms BEFORE the event is created.
 * Its own host for the same reason the others have theirs — a parked
 * confirmation must not recompose the surface tree.  A dismissal (scrim
 * or back) is a REFUSAL: the event is never created, which is the whole
 * point of a guarded destructive verb.
 */
@androidx.compose.runtime.Composable
private fun ConfirmHost(bridge: DeviceBridge) {
    val pending by bridge.pendingConfirm.collectAsState()
    pending?.let { p ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { bridge.resolveConfirm(false) },
            // §14.1 object form: the face is authored; a bare string keeps
            // exactly the dialog this host always drew.
            icon = p.icon?.let {
                {
                    androidx.compose.material3.Icon(
                        com.calebc42.glasspane.material3.IconMap.get(it),
                        contentDescription = null)
                }
            },
            title = p.title?.let { { Text(it) } },
            text = { Text(p.prompt) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { bridge.resolveConfirm(true) }) {
                    Text(p.confirmLabel ?: "OK")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { bridge.resolveConfirm(false) }) {
                    Text(p.dismissLabel ?: "Cancel")
                }
            })
    }
}

@androidx.compose.runtime.Composable
private fun PieMenuHost(
    flow: kotlinx.coroutines.flow.StateFlow<Pair<String, JsonObject>?>,
    bridge: DeviceBridge,
) {
    val pie by flow.collectAsState()
    pie?.let { (id, spec) -> RenderPieMenu(id, spec, bridge) }
}

@androidx.compose.runtime.Composable
private fun DialogHost(
    flow: kotlinx.coroutines.flow.StateFlow<JetpacsApplication.DialogShow?>,
    bridge: DeviceBridge,
) {
    val dialog by flow.collectAsState()
    dialog?.let { (id, dspec, epoch) ->
        androidx.compose.ui.window.Dialog(
            // SPEC 18.1: a platform dismissal is a dismiss.
            onDismissRequest = { bridge.dismissDialog(id) }) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                // The HOST container scrolls. SPEC 18.1 forbids lazy_column
                // NODES in a dialog spec, not the window scrolling — and
                // without this, any dialog taller than the window (a long
                // enum_list picker, stacked context cards) has UNREACHABLE
                // content below the fold. JC-4 prerequisite.
                //
                // heightIn is what makes verticalScroll work AT ALL here:
                // a Dialog measures its content with UNBOUNDED height, so a
                // scrolling column believes it has infinite room, never
                // scrolls, and the window is simply clipped by the screen.
                // Capping the height gives the scroll something to overflow.
                // A cap rather than fillMaxHeight so a short dialog still
                // wraps its content instead of always filling the screen.
                val maxDialogHeight =
                    (LocalConfiguration.current.screenHeightDp * 0.8f).dp
                androidx.compose.foundation.layout.Column(
                    Modifier
                        .heightIn(max = maxDialogHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp)) {
                    RenderDialogRoot(
                        id,
                        dspec,
                        bridge,
                        epoch,
                        LocalCompanionRendererConfiguration.current,
                    )
                }
            }
        }
    }
}
