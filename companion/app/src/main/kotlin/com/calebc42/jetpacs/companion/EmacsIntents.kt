// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.content.Context
import android.content.Intent

/**
 * Bring Android Emacs's launcher task to the foreground, starting it when it
 * is not already running. Package visibility for this lookup is declared in
 * AndroidManifest.xml and shared with the reconnect notification target.
 */
internal fun openAndroidEmacs(context: Context): Boolean {
    val launch = context.packageManager
        .getLaunchIntentForPackage(ANDROID_EMACS_PACKAGE)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        ?: return false
    return runCatching {
        context.startActivity(launch)
        true
    }.getOrDefault(false)
}

/**
 * What the reconnect notification offers after an authenticated session is
 * lost: the standalone flavor opens Android Emacs, the bundled flavor
 * restarts its engine (docs/PLAN-bundled-emacs.md).
 */
internal data class ReconnectPrompt(val intent: Intent, val title: String, val text: String)
