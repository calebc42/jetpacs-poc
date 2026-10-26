// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import com.calebc42.jetpacs.companion.host.DeviceColors
import com.calebc42.jetpacs.companion.host.HostColors
import com.calebc42.jetpacs.material3.ExtendedColors
import com.calebc42.jetpacs.material3.baselineSchemeRoles
import com.calebc42.jetpacs.material3.schemeRoles

/**
 * SPEC 20.1.3 (#205): the device's own colors on Android — the system
 * night mode as the polarity, and the wallpaper-derived Material You
 * schemes on S and later, the baseline Material 3 schemes below. The
 * schemes are what the device's other Material apps draw with, never a
 * palette `theme.set` pushed.
 */
internal fun deviceColors(context: Context): DeviceColors {
    val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    val dark = night == Configuration.UI_MODE_NIGHT_YES
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        DeviceColors(
            dark = dark,
            light = schemeRoles(dynamicLightColorScheme(context), ExtendedColors.defaults(false)),
            darkScheme = schemeRoles(dynamicDarkColorScheme(context), ExtendedColors.defaults(true)),
        )
    } else {
        DeviceColors(dark, baselineSchemeRoles(false), baselineSchemeRoles(true))
    }
}

/** The platform says when the appearance (a configuration change) or the wallpaper changed. */
internal class AndroidHostColors(private val app: Context) : HostColors {
    override fun current(): DeviceColors = deviceColors(app)

    override fun observe(onChanged: () -> Unit): AutoCloseable {
        val callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) = onChanged()
            override fun onLowMemory() {}
        }
        app.registerComponentCallbacks(callbacks)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = onChanged()
        }
        app.registerReceiver(
            receiver,
            IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        return AutoCloseable {
            runCatching { app.unregisterComponentCallbacks(callbacks) }
            runCatching { app.unregisterReceiver(receiver) }
        }
    }
}
