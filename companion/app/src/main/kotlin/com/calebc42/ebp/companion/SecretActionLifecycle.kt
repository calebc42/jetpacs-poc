// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion

import com.calebc42.ebp.wire.CompanionEngine
import com.calebc42.ebp.wire.TEXT_INPUT_CONTRACT
import com.calebc42.ebp.wire.UnsafeAdmissionReason
import com.calebc42.ebp.renderer.model.RendererActionOutcome
import com.calebc42.ebp.renderer.model.RendererVolatileSecret
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A cancellable task scheduled against a monotonic time source. */
internal fun interface SecretDeadlineHandle {
    fun cancel()
}

/** Injectable monotonic scheduler for the hard password lifetime. */
internal interface SecretDeadlineScheduler {
    fun nowNanos(): Long
    fun scheduleAt(deadlineNanos: Long, task: () -> Unit): SecretDeadlineHandle
}

/** Process-owned scheduler; wall-clock changes cannot extend a password life. */
internal object SystemSecretDeadlineScheduler : SecretDeadlineScheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "ebp-secret-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    override fun nowNanos(): Long = System.nanoTime()

    override fun scheduleAt(
        deadlineNanos: Long,
        task: () -> Unit,
    ): SecretDeadlineHandle {
        val delay = (deadlineNanos - nowNanos()).coerceAtLeast(0L)
        val future = executor.schedule(task, delay, TimeUnit.NANOSECONDS)
        return SecretDeadlineHandle { future.cancel(false) }
    }
}

/** Exactly-once terminal gate shared by every renderer occurrence. */
internal class RendererOutcomeGate(
    private val terminal: (RendererActionOutcome) -> Unit,
) {
    private val concluded = AtomicBoolean(false)

    fun complete(outcome: RendererActionOutcome) {
        if (concluded.compareAndSet(false, true)) terminal(outcome)
    }
}

/**
 * Session-owned lifetime of one handed-off password occurrence.
 *
 * The deadline is stamped at construction, before confirmation or executor
 * admission. Timeout wins its atomic terminal decision before closing the
 * exact bound engine, so teardown callbacks and late peer responses cannot
 * replace the mandated Timeout conclusion or erase twice.
 */
internal class SecretActionAttempt(
    val engine: CompanionEngine?,
    private val capture: RendererVolatileSecret,
    scheduler: SecretDeadlineScheduler,
    private val abortTransport: (CompanionEngine?) -> Unit,
    private val beforeTimeout: (SecretActionAttempt) -> Unit = {},
    private val terminal: (RendererActionOutcome) -> Unit,
) {
    private val pending = AtomicBoolean(true)
    private val deadlineHandle = AtomicReference<SecretDeadlineHandle?>()

    init {
        val duration = TimeUnit.MILLISECONDS.toNanos(
            TEXT_INPUT_CONTRACT.password.deadlineMillis,
        )
        val now = scheduler.nowNanos()
        val deadline = if (Long.MAX_VALUE - now < duration) Long.MAX_VALUE
        else now + duration
        val handle = scheduler.scheduleAt(deadline, ::timeout)
        if (!deadlineHandle.compareAndSet(null, handle)) handle.cancel()
        if (!pending.get()) deadlineHandle.getAndSet(null)?.cancel()
    }

    fun isPending(): Boolean = pending.get()

    /** Release the captured JSON immediately after synchronous wire handoff. */
    fun releaseAfterDispatch() {
        capture.releaseCapturedValues()
    }

    fun complete(outcome: RendererActionOutcome) {
        if (!pending.compareAndSet(true, false)) return
        deadlineHandle.getAndSet(null)?.cancel()
        capture.releaseCapturedValues()
        terminal(outcome)
    }

    private fun timeout() {
        if (!pending.compareAndSet(true, false)) return
        deadlineHandle.set(null)
        capture.releaseCapturedValues()
        beforeTimeout(this)
        abortTransport(engine)
        terminal(RendererActionOutcome.NotAdmitted(UnsafeAdmissionReason.Timeout))
    }
}
