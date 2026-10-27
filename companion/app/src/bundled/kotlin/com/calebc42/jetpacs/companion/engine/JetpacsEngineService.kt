// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.calebc42.jetpacs.companion.MainActivity

/**
 * The engine's foreground service, the only component declared in the
 * `:emacs` process (src/bundled/AndroidManifest.xml). Its one job is to
 * keep the process that hosts GNU Emacs alive on the user's terms and to
 * start [EngineRuntime] once. The notification describes policy ("Jetpacs is
 * running"), never session liveness, which SPEC invariant 8 keeps out of
 * every correctness requirement.
 *
 * Binding is permitted and inert: the binder exposes no operation, so a
 * `bindService` with no extras is a SPEC 5.8 wake signal by construction.
 */
class JetpacsEngineService : Service() {
    override fun onCreate() {
        super.onCreate()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        EngineRuntime.start(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = Binder()

    override fun onDestroy() {
        Log.i(TAG, "engine service destroyed; Emacs keeps the process until it exits")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Jetpacs engine", NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        description = "Keeps the notes engine available in the background"
                        setShowBadge(false)
                    },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Jetpacs is running")
            .setContentText("The notes engine is available in the background.")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "JetpacsEngine"
        const val PROCESS_SUFFIX = ":emacs"
        private const val CHANNEL_ID = "jetpacs-engine"
        private const val NOTIFICATION_ID = 0x454d43
    }
}
