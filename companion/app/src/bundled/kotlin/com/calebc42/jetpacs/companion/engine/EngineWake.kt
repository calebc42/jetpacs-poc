// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * SPEC 5.8, "Android, same application, by bound service": after a durable
 * `wake` admission the Companion binds the engine's service with the
 * auto-create flag and no extras, holds the binding until the session
 * reaches READY or ten seconds pass, whichever is first, and never signals
 * more than once in sixty seconds. In a bundled deployment the target is
 * fixed by the application (amendment #209), so the "user has configured"
 * re-check is the component's existence. The binding is never a keep-alive:
 * the always-on policy is the supervisor's own started service.
 *
 * The platform calls are injected so a JVM test can drive the bounds with
 * a fake clock and binder.
 */
internal class EngineWake(
    private val bind: (ServiceConnection) -> Boolean,
    private val unbind: (ServiceConnection) -> Unit,
    private val postDelayed: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    /** Injected so the JVM test runs without android.util.Log. */
    private val log: (String) -> Unit = {},
) {
    private var connection: ServiceConnection? = null
    private var release: Runnable? = null
    private var lastWakeMs: Long? = null

    /** Signal the engine, or do nothing while coalesced; true when a bind was issued. */
    @Synchronized
    fun request(): Boolean {
        val at = now()
        if (connection != null) return false
        lastWakeMs?.let { if (at - it < WAKE_INTERVAL_MS) return false }
        lastWakeMs = at
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                log("wake bound")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                log("wake unbound by the system")
            }
        }
        connection = serviceConnection
        if (!bind(serviceConnection)) {
            connection = null
            log("wake bind refused")
            return false
        }
        val timeout = Runnable { release("10 s bound") }
        release = timeout
        postDelayed(timeout, WAKE_WAIT_MS)
        return true
    }

    /** Release the binding, if held: at READY, at the bound, or when the host stops. */
    @Synchronized
    fun release(reason: String) {
        val held = connection ?: return
        release?.let(cancel)
        release = null
        connection = null
        log("wake released: $reason")
        unbind(held)
    }

    val isHeld: Boolean get() = synchronized(this) { connection != null }

    companion object {
        private const val TAG = "JetpacsEngine"
        const val WAKE_INTERVAL_MS = 60_000L
        const val WAKE_WAIT_MS = 10_000L

        /** The real one: binds [JetpacsEngineService] from the application context on the main thread. */
        fun forApplication(context: Context): EngineWake {
            val app = context.applicationContext
            val handler = Handler(Looper.getMainLooper())
            return EngineWake(
                bind = { connection ->
                    // No extras: the bind is the whole signal (SPEC 5.8).
                    val intent = Intent(app, JetpacsEngineService::class.java)
                    runCatching { app.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
                        .getOrElse { failure -> Log.w(TAG, "wake bind failed", failure); false }
                },
                unbind = { connection ->
                    handler.post {
                        runCatching { app.unbindService(connection) }
                    }
                },
                postDelayed = { runnable, delay -> handler.postDelayed(runnable, delay) },
                cancel = { runnable -> handler.removeCallbacks(runnable) },
                log = { message -> Log.d(TAG, message) },
            )
        }
    }
}
