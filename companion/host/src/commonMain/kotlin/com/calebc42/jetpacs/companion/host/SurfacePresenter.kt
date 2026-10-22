// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The presentation of an accepted surface frame, off the engine
 * (PLAN-responsiveness C2c). The engine's surface listener runs inside the
 * engine monitor on the process actor's worker; until now it also did the
 * presentation there — the incarnation projection's walk of the spec, the
 * publish into Compose, a notification post — so the monitor and the actor
 * were held for work that only reads an immutable snapshot. The listener
 * now reads what it needs from the store while it still holds the monitor
 * and hands the rest here; this runs it on one serial thread in enqueue
 * order, which is engine order, so no presentation overtakes an earlier
 * one and the actor is free for the next command — a tap — meanwhile.
 */
class SurfacePresenter(
    /** A presentation that threw: reported, never fatal to the thread. */
    private val onFailure: (surface: String, failure: Throwable) -> Unit = { _, _ -> },
    /** Per timed presentation: how long it waited in the queue and ran. */
    private val onTiming: ((surface: String, waitNanos: Long, runNanos: Long) -> Unit)? = null,
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ebp-present").apply { isDaemon = true }
    },
) {
    /**
     * Enqueue [block] for [surface]. It runs after every presentation
     * enqueued before it, on the presenter's thread; this returns at once.
     * [timed] reports the wait and run through [onTiming].
     */
    fun present(surface: String, timed: Boolean = false, block: () -> Unit) {
        val enqueuedAt = if (timed) System.nanoTime() else 0L
        executor.execute {
            val startedAt = if (timed) System.nanoTime() else 0L
            try {
                block()
            } catch (failure: Throwable) {
                onFailure(surface, failure)
            }
            if (timed) {
                val done = System.nanoTime()
                onTiming?.invoke(surface, startedAt - enqueuedAt, done - startedAt)
            }
        }
    }
}
