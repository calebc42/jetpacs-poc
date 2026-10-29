// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.calebc42.jetpacs.companion.host.fonts.PickedFontFile
import com.calebc42.jetpacs.companion.host.fonts.UserFonts
import com.calebc42.jetpacs.companion.host.ui.HostUiPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Android half of the shared window composition: the Activity's back
 * dispatcher, its configuration for the window size, and the Android Emacs
 * launcher the offline chrome offers.
 */
internal class AndroidHostUi(context: Context) : HostUiPlatform {
    private val app: Context = context.applicationContext

    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
        androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
    }

    @Composable
    override fun DialogDim(amount: Float) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(amount.coerceIn(0f, 1f)) }
    }

    @Composable
    override fun windowSizeDp(): DpSize {
        val configuration = LocalConfiguration.current
        return DpSize(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp)
    }

    override val openEmacs: (() -> Unit) = {
        if (!EngineFlavor.openEmacs(app)) {
            Toast.makeText(app, "Android Emacs is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    // The flavor owns the address: standalone's loopback port, the bundled
    // flavor's Unix socket (docs/PLAN-bundled-emacs.md).
    override val listeningEndpoint: String = EngineFlavor.listeningEndpoint(app)

    override val listeningDescription: String
        get() = EngineFlavor.listeningDescription(listeningEndpoint)

    override val wording: com.calebc42.jetpacs.companion.host.HostWording = EngineFlavor.hostWording

    @Composable
    override fun LeadingSettings(bridge: com.calebc42.jetpacs.companion.host.DeviceBridge) {
        EngineFlavor.LeadingSettings(bridge)
    }

    @Composable
    override fun DiagnosticsSettings(bridge: com.calebc42.jetpacs.companion.host.DeviceBridge) {
        EngineFlavor.DiagnosticsSettings(bridge)
    }

    override val offersSetup: Boolean get() = EngineFlavor.offersSetup

    override val userFonts: UserFonts? get() = (app as? JetpacsApplication)?.userFonts

    @Composable
    override fun rememberFontFilePicker(onPicked: (PickedFontFile?) -> Unit): (() -> Unit)? {
        val current = rememberUpdatedState(onPicked)
        val scope = rememberCoroutineScope()
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                current.value(null)
            } else {
                scope.launch {
                    current.value(withContext(Dispatchers.IO) { AndroidUserFonts.read(app, uri) })
                }
            }
        }
        return { launcher.launch(AndroidUserFonts.pickerMimeTypes) }
    }
}
