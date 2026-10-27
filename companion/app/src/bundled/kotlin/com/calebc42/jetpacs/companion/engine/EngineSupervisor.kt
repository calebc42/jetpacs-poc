// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

import android.app.Activity
import android.app.ActivityManager
import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

/**
 * The Companion-process side of the engine supervisor
 * (docs/PLAN-bundled-emacs.md, E5): the user's background policy and the
 * engine's lifetime under it. With "Run in background" on (the default,
 * decision 1) the engine is a foreground service started when the Companion
 * opens and after a reboot or an update; with it off the engine is bound
 * only while the Companion's window is visible and otherwise woken by the
 * SPEC 5.8 bind for at most ten seconds per event. The supervisor is
 * neither EBP endpoint; the Companion still only listens, and the engine's
 * Emacs still dials and authenticates.
 */
internal object EngineSupervisor {
    private const val TAG = "JetpacsEngine"
    private const val PREFS = "jetpacs-engine"
    private const val KEY_BACKGROUND = "background"

    /** Whether the engine keeps running when the Companion is not visible. */
    fun backgroundEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BACKGROUND, true)

    /**
     * Change the policy and apply it now: on starts the foreground service,
     * off stops it and, while a window is visible, keeps the engine bound.
     */
    fun setBackgroundEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BACKGROUND, enabled).apply()
        if (enabled) {
            start(context)
        } else {
            context.applicationContext.stopService(Intent(context, JetpacsEngineService::class.java))
            visible?.let { bindWhileVisible(it) }
        }
    }

    /**
     * Start the engine as a foreground service when background running is
     * on. A start from a background position may be refused by the
     * platform; that is logged, not thrown, and the wake path remains.
     */
    fun start(context: Context): Boolean {
        // A launch is user intent, whatever the policy: the crash-loop guard's
        // halt and the start history it counted both clear, so the guard
        // counts afresh. (Clearing only when background running was on left a
        // halt in place for good once it was off; found on the Pixel Tablet.)
        clearCrashGuard(context)
        if (!backgroundEnabled(context)) return false
        val app = context.applicationContext
        return try {
            app.startForegroundService(Intent(app, JetpacsEngineService::class.java))
            true
        } catch (refused: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "engine foreground start refused from this position", refused)
            false
        }
    }

    /** Stop the engine on the user's request; it comes back on the next launch or wake. */
    fun stop(context: Context) {
        visible?.let { unbindVisible(it) }
        context.applicationContext.stopService(Intent(context, JetpacsEngineService::class.java))
    }

    /**
     * Stop and start again, for the Settings "Restart engine" action. The
     * stop ends the engine process (the service's destroy runs Emacs's
     * shutdown and exits), and the start that follows gets a fresh one.
     */
    fun restart(context: Context) {
        // Read before stop(): unbinding the visible window clears it, and
        // with background running off the rebind below is the only start.
        val activity = visible
        stop(context)
        if (!start(context) && activity != null) bindWhileVisible(activity)
    }

    /** Delete the engine's dump so the next start rebuilds it, then restart. */
    fun rebuildCache(context: Context) {
        val home = EngineHome.of(context)
        home.root.listFiles()?.filter { it.name.endsWith(".pdmp") }?.forEach { it.delete() }
        java.io.File(home.root, "dump.stamp").delete()
        restart(context)
    }

    /**
     * Move the notes between the private and the shared vault (decision 2):
     * stop the engine so nothing writes meanwhile, copy what the target
     * lacks (never overwriting, never deleting the source), record the
     * mode the provisioner reads, start again. Returns the files copied.
     */
    fun switchVault(context: Context, target: EngineVault.Mode): Int {
        val home = EngineHome.of(context)
        if (target == EngineVault.Mode.SHARED) {
            check(EngineVault.sharedAccessGranted()) { "All files access is not granted" }
        }
        val from = EngineVault.current(home)
        val to = if (target == EngineVault.Mode.SHARED) EngineVault.sharedDir() else EngineVault.privateDir(home)
        check(to.isDirectory || to.mkdirs()) { "cannot create ${to.path}" }
        // As in restart(): stop() clears the visible window, read it first.
        val activity = visible
        stop(context)
        var waited = 0
        while (processAlive(context) && waited < 3_000) {
            Thread.sleep(100); waited += 100
        }
        val copied = EngineVault.mergeCopy(from, to)
        EngineVault.setMode(home, target)
        // The provisioner rewrites install.conf on the next start.
        if (!start(context) && activity != null) bindWhileVisible(activity)
        return copied
    }

    /** Whether the engine's process exists right now (a fact, not a session claim). */
    fun processAlive(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.runningAppProcesses.orEmpty()
            .any { it.processName.endsWith(JetpacsEngineService.PROCESS_SUFFIX) }
    }

    /** The engine stopped itself after repeated failures; `start` clears it. */
    fun halted(context: Context): Boolean = EngineHome.of(context).haltedFile.isFile

    /** Forget the crash-loop guard's halt and start history (user intent). */
    private fun clearCrashGuard(context: Context) {
        EngineHome.of(context).let { it.haltedFile.delete(); it.startsFile.delete() }
    }

    // ---- bound while visible (background running off)

    @Volatile private var visible: Activity? = null
    private var visibleConnection: ServiceConnection? = null

    /** The Companion's window is visible: keep the engine bound when it is not a service. */
    fun bindWhileVisible(activity: Activity) {
        visible = activity
        if (backgroundEnabled(activity)) {
            start(activity)
            return
        }
        // The window became visible: user intent, as a launch is. Reopening
        // the app from Recents gives no launch, only this.
        clearCrashGuard(activity)
        synchronized(this) {
            if (visibleConnection != null) return
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
            val intent = Intent(activity.applicationContext, JetpacsEngineService::class.java)
            if (activity.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                visibleConnection = connection
            } else {
                Log.w(TAG, "visible bind refused")
            }
        }
    }

    /** The window went away: release the visible-only binding. */
    fun unbindVisible(activity: Activity) {
        if (visible === activity) visible = null
        val connection = synchronized(this) { visibleConnection.also { visibleConnection = null } } ?: return
        runCatching { activity.applicationContext.unbindService(connection) }
    }

    // ---- SPEC 5.8 wake

    @Volatile private var wake: EngineWake? = null

    private fun wake(context: Context): EngineWake =
        wake ?: synchronized(this) { wake ?: EngineWake.forApplication(context).also { wake = it } }

    /** SPEC 5.8: the bound-service wake after a durable `wake` admission. */
    fun requestWake(context: Context): Boolean = wake(context).request()

    /** A session reached READY: the wake binding, if held, has done its job. */
    fun releaseWake(context: Context) {
        wake?.release("ready")
    }
}
