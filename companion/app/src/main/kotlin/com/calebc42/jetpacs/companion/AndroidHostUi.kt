// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.calebc42.jetpacs.companion.host.ui.HostUiPlatform

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

    @Composable
    override fun ExtraSettings(bridge: com.calebc42.jetpacs.companion.host.DeviceBridge) {
        EngineFlavor.SettingsSection(bridge)
    }
}
