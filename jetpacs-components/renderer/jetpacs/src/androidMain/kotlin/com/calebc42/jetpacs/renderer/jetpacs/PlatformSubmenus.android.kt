// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** AppCompat's rule: cascade when the DISPLAY's smallest side is >= 720dp. */
@Composable
internal actual fun platformCascadesSubmenus(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val metrics = context.resources.displayMetrics
        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = context.getSystemService(WindowManager::class.java)
                .maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            metrics.widthPixels to metrics.heightPixels
        }
        minOf(w, h) / metrics.density >= CascadingMenusMinSmallestWidthDp
    }
}

/** AppCompat's `abc_cascading_menus_min_smallest_width`. */
private const val CascadingMenusMinSmallestWidthDp = 720f
