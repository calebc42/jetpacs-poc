// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * The Companion-process end of the bundled app's one foreground service.
 * While [JetpacsEngineService] runs in the foreground (background running
 * on), it binds this service, so Android keeps the Companion's process, its
 * listener, actor and receivers, at the engine's importance under the
 * engine's single "Jetpacs is running" notification. Without it the process
 * needed a foreground service of its own, the bridge service, and the user
 * saw two notifications. Binding is inert: the binder exposes no operation
 * and no extra is read (src/bundled/AndroidManifest.xml keeps it private).
 */
class CompanionKeepaliveService : Service() {
    override fun onBind(intent: Intent?): IBinder = Binder()
}
