// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * User-visible best-effort process importance for the local loopback bridge.
 * The notification describes enabled policy only; it never claims that the
 * process, listener, or an Emacs connection is currently alive.
 */
class JetpacsBridgeService : Service() {
    private val app: JetpacsApplication get() = application as JetpacsApplication

    override fun onCreate() {
        super.onCreate()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (!app.container.disableBridge(app.bridge) {
                mainExecutor.execute {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
            }) Log.w(TAG, "The command actor was full; bridge stop was not admitted")
            return START_NOT_STICKY
        }
        val admitted = if (intent?.action == ACTION_ENABLE) {
            app.container.enableBridge(app.bridge)
        } else {
            app.container.startBridge(app.bridge)
        }
        if (!admitted) {
            Log.w(TAG, "The command actor was full; bridge start was not admitted")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        app.container.stopBridge(app.bridge)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Jetpacs background bridge",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Best-effort background availability for the local Emacs bridge"
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
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, JetpacsBridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Jetpacs background bridge enabled")
            .setContentText("Android may still stop the process; this is not a connection indicator.")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Stop",
                    stop,
                ).build(),
            )
            .build()
    }

    companion object {
        private const val TAG = "JetpacsBridgeFgs"
        private const val CHANNEL_ID = "jetpacs-background-bridge"
        private const val NOTIFICATION_ID = 0x4a4554
        private const val ACTION_ENABLE =
            "com.calebc42.jetpacs.companion.action.ENABLE_BACKGROUND_BRIDGE"
        private const val ACTION_START =
            "com.calebc42.jetpacs.companion.action.START_BACKGROUND_BRIDGE"
        private const val ACTION_STOP =
            "com.calebc42.jetpacs.companion.action.STOP_BACKGROUND_BRIDGE"

        fun enable(context: Context) {
            context.startForegroundService(
                Intent(context, JetpacsBridgeService::class.java).setAction(ACTION_ENABLE),
            )
        }

        fun startPersisted(context: Context) {
            context.startForegroundService(
                Intent(context, JetpacsBridgeService::class.java).setAction(ACTION_START),
            )
        }
    }
}
