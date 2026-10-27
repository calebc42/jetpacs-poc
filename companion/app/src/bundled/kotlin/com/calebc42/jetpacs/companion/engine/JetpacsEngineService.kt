// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.calebc42.jetpacs.companion.MainActivity
import org.gnu.emacs.EmacsNative

/**
 * The engine's service, the only component declared in the `:emacs`
 * process (src/bundled/AndroidManifest.xml). Started, it is a foreground
 * service with one "Jetpacs is running" notification (background running
 * on); merely bound, by the visible Companion or by a SPEC 5.8 wake, it
 * keeps the process without a notification. Either way it starts
 * [EngineRuntime] once. The notification describes policy, never session
 * liveness, which SPEC invariant 8 keeps out of every correctness
 * requirement. Binding is inert: the binder exposes no operation and no
 * extra is read, which is what SPEC 5.8's same-application wake target
 * requires. "Pause" is the one action the notification offers; a restart
 * is the supervisor's stop followed by its start.
 *
 * That notification is the app's only one: in the foreground the service
 * also binds [CompanionKeepaliveService], which keeps the Companion's
 * process, whose socket Emacs dials, alive at the engine's importance
 * without a foreground service of its own.
 */
class JetpacsEngineService : Service() {
    private var foreground = false
    private var holdingCompanion = false
    private val companionHold = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "engine service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                // The user asked for a stop from the notification; it comes
                // back on the next launch or wake, the policy unchanged.
                Log.i(TAG, "engine paused by the user")
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
        }
        if (!foreground) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            foreground = true
            holdCompanion()
        }
        if (!EngineRuntime.start(this)) {
            // The crash-loop guard refused: no Emacs this time, no retries
            // until the user opens the Companion again.
            postHaltedNotice()
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        EngineRuntime.start(this)
        return Binder()
    }

    override fun onDestroy() {
        Log.i(TAG, "engine service destroyed; ending the process")
        // A clean stop (the policy, Pause, a wake's release, a restart) is not
        // a crash: forget the start history so the crash-loop guard counts
        // only starts that follow a process that died on its own. Without
        // this, background running off made every open of the app and every
        // SPEC 5.8 wake a "start", and the third inside ten minutes halted
        // the engine (found on the Pixel Tablet).
        EngineHome.of(this).startsFile.delete()
        releaseCompanion()
        super.onDestroy()
        endProcess()
    }

    private fun stopForegroundAndSelf() {
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        releaseCompanion()
        stopSelf()
    }

    /**
     * Bind the Companion's process while running in the background. The
     * bind is automatic-create, so Android also restarts that process,
     * whose application then starts its listener again, if it is lost.
     */
    private fun holdCompanion() {
        if (holdingCompanion) return
        holdingCompanion = bindService(
            Intent(this, CompanionKeepaliveService::class.java),
            companionHold,
            BIND_AUTO_CREATE,
        )
        if (!holdingCompanion) Log.w(TAG, "the Companion process could not be held")
    }

    private fun releaseCompanion() {
        if (!holdingCompanion) return
        holdingCompanion = false
        runCatching { unbindService(companionHold) }
    }

    /**
     * Upstream's own shutdown shape (EmacsService.onDestroy): auto-save and
     * unlock in Emacs, then let the process go. A sticky restart or the next
     * bind brings a fresh process with a fresh Emacs.
     */
    private fun endProcess() {
        if (EngineRuntime.started) runCatching { EmacsNative.shutDownEmacs() }
        Process.killProcess(Process.myPid())
    }

    private fun postHaltedNotice() {
        val manager = getSystemService(NotificationManager::class.java)
        buildNotification() // ensures the channel
        val open = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notice = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Jetpacs engine stopped")
            .setContentText("It failed repeatedly. Open Jetpacs to try again.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ERROR)
            .build()
        runCatching { manager.notify(HALTED_NOTIFICATION_ID, notice) }
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
        val pause = PendingIntent.getService(
            this,
            1,
            Intent(this, JetpacsEngineService::class.java).setAction(ACTION_PAUSE),
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
            .addAction(Notification.Action.Builder(null, "Pause", pause).build())
            .build()
    }

    companion object {
        private const val TAG = "JetpacsEngine"
        const val PROCESS_SUFFIX = ":emacs"
        const val ACTION_PAUSE = "com.calebc42.jetpacs.companion.action.PAUSE_ENGINE"
        private const val CHANNEL_ID = "jetpacs-engine"
        private const val NOTIFICATION_ID = 0x454d43
        private const val HALTED_NOTIFICATION_ID = 0x454d44
    }
}
