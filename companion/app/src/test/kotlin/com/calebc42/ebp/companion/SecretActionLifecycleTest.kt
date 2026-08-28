// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion

import com.calebc42.ebp.wire.SafeAdmissionEvidence
import com.calebc42.ebp.wire.UnsafeAdmissionReason
import com.calebc42.jetpacs.renderer.model.RendererActionOutcome
import com.calebc42.jetpacs.renderer.model.RendererVolatileSecret
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class SecretActionLifecycleTest {
    @Test
    fun fakeClockDeadlineAbortsErasesAndConcludesExactlyOnce() {
        val scheduler = ManualSecretScheduler()
        val canary = "SECRET-DEADLINE-CANARY"
        var nativeEraseCount = 0
        var abortCount = 0
        var retainedCorrelation = true
        val outcomes = mutableListOf<RendererActionOutcome>()
        val capture = RendererVolatileSecret(
            buildJsonObject { put("password", canary) },
            setOf("password"),
        ) { nativeEraseCount++ }
        lateinit var attempt: SecretActionAttempt
        attempt = SecretActionAttempt(
            engine = null,
            capture = capture,
            scheduler = scheduler,
            abortTransport = { abortCount++ },
            beforeTimeout = {
                assertTrue(it === attempt)
                retainedCorrelation = false
            },
            terminal = {
                capture.erase()
                outcomes += it
            },
        )

        assertFalse(capture.toString().contains(canary))
        scheduler.advanceByMillis(29_999)
        assertTrue(attempt.isPending())
        assertTrue(outcomes.isEmpty())

        scheduler.advanceByMillis(1)

        assertFalse(attempt.isPending())
        assertNull(capture.fieldsOrNull())
        assertFalse(retainedCorrelation)
        assertEquals(1, abortCount)
        assertEquals(1, nativeEraseCount)
        assertEquals(
            UnsafeAdmissionReason.Timeout,
            (outcomes.single() as RendererActionOutcome.NotAdmitted).reason,
        )

        attempt.complete(
            RendererActionOutcome.SafelyAdmitted(SafeAdmissionEvidence.RemoteAccepted),
        )
        capture.erase()
        assertEquals(1, outcomes.size)
        assertEquals(1, nativeEraseCount)
    }

    @Test
    fun ordinaryOutcomeGateDropsDuplicateEngineCallbacks() {
        val outcomes = mutableListOf<RendererActionOutcome>()
        val gate = RendererOutcomeGate(outcomes::add)

        gate.complete(RendererActionOutcome.NotAdmitted(UnsafeAdmissionReason.TransportClosed))
        gate.complete(
            RendererActionOutcome.SafelyAdmitted(SafeAdmissionEvidence.RemoteAccepted),
        )

        assertEquals(1, outcomes.size)
        assertEquals(
            UnsafeAdmissionReason.TransportClosed,
            (outcomes.single() as RendererActionOutcome.NotAdmitted).reason,
        )
    }

    @Test
    fun terminalResponseCancelsDeadlineAndLateTimerCannotAbort() {
        val scheduler = ManualSecretScheduler()
        var aborted = false
        var erased = 0
        val outcomes = mutableListOf<RendererActionOutcome>()
        val capture = RendererVolatileSecret(
            buildJsonObject { put("password", "short-lived") },
            setOf("password"),
        ) { erased++ }
        val attempt = SecretActionAttempt(
            engine = null,
            capture = capture,
            scheduler = scheduler,
            abortTransport = { aborted = true },
            terminal = {
                capture.erase()
                outcomes += it
            },
        )

        attempt.complete(
            RendererActionOutcome.SafelyAdmitted(SafeAdmissionEvidence.RemoteAccepted),
        )
        scheduler.advanceByMillis(60_000)

        assertFalse(aborted)
        assertEquals(1, erased)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun derivedDialogCaptureSharesParentDisposalLifetime() {
        var nativeErase = 0
        var dialogErase = 0
        val parent = RendererVolatileSecret(
            buildJsonObject { put("password", "derived-canary") },
            setOf("password"),
        ) { nativeErase++ }
        val derived = parent.derive(
            buildJsonObject {
                put("password", "derived-canary")
                put("remember", false)
            },
            setOf("password"),
        ) { dialogErase++ }
        parent.releaseCapturedValues()

        assertTrue(derived.fieldsOrNull() != null)
        parent.erase()

        assertNull(derived.fieldsOrNull())
        assertEquals(1, nativeErase)
        assertEquals(1, dialogErase)
        derived.erase()
        assertEquals(1, nativeErase)
        assertEquals(1, dialogErase)
    }

    private class ManualSecretScheduler : SecretDeadlineScheduler {
        private data class Task(
            val deadline: Long,
            val body: () -> Unit,
            var cancelled: Boolean = false,
        )

        private var now = 0L
        private val tasks = mutableListOf<Task>()

        override fun nowNanos(): Long = now

        override fun scheduleAt(
            deadlineNanos: Long,
            task: () -> Unit,
        ): SecretDeadlineHandle {
            val scheduled = Task(deadlineNanos, task)
            tasks += scheduled
            return SecretDeadlineHandle { scheduled.cancelled = true }
        }

        fun advanceByMillis(milliseconds: Long) {
            now += TimeUnit.MILLISECONDS.toNanos(milliseconds)
            tasks.filter { !it.cancelled && it.deadline <= now }
                .sortedBy(Task::deadline)
                .forEach {
                    it.cancelled = true
                    it.body()
                }
        }
    }
}
