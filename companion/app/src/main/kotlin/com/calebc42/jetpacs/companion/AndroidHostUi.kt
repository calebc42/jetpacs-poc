// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
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
    override fun windowSizeDp(): DpSize {
        val configuration = LocalConfiguration.current
        return DpSize(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp)
    }

    override val openEmacs: (() -> Unit) = {
        if (!openAndroidEmacs(app)) {
            Toast.makeText(app, "Android Emacs is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    override val listeningEndpoint: String = "127.0.0.1:8765"
}
