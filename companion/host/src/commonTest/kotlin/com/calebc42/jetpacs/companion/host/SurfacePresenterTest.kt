// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * PLAN-responsiveness C2c: a presentation returns to its caller at once and
 * runs later, in enqueue order, on one thread; a failure is reported and
 * stops nothing behind it.
 */
class SurfacePresenterTest {
    @Test
    fun presentReturnsBeforeTheBlockRunsAndBlocksRunInOrderOnOneThread() {
        val presenter = SurfacePresenter()
        val gate = CountDownLatch(1)
        val ran = CopyOnWriteArrayList<String>()
        val threads = CopyOnWriteArrayList<Thread>()
        val done = CountDownLatch(3)
        presenter.present("app:a") { gate.await(); ran += "a"; threads += Thread.currentThread(); done.countDown() }
        presenter.present("app:b") { ran += "b"; threads += Thread.currentThread(); done.countDown() }
        presenter.present("app:c") { ran += "c"; threads += Thread.currentThread(); done.countDown() }
        // All three calls returned while the first is still gated.
        assertEquals(emptyList<String>(), ran)
        gate.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("a", "b", "c"), ran)
        assertEquals(1, threads.toSet().size)
        assertTrue(threads.first() !== Thread.currentThread())
    }

    @Test
    fun aFailingPresentationIsReportedAndTheNextOneStillRuns() {
        val failures = CopyOnWriteArrayList<String>()
        val presenter = SurfacePresenter(onFailure = { surface, failure -> failures += "$surface:${failure.message}" })
        val done = CountDownLatch(1)
        val ran = CopyOnWriteArrayList<String>()
        presenter.present("app:bad") { error("boom") }
        presenter.present("app:good") { ran += "good"; done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("app:bad:boom"), failures)
        assertEquals(listOf("good"), ran)
    }

    @Test
    fun aTimedPresentationReportsItsWaitAndRun() {
        val timings = CopyOnWriteArrayList<Triple<String, Long, Long>>()
        val done = CountDownLatch(2)
        val presenter = SurfacePresenter(onTiming = { s, wait, run -> timings += Triple(s, wait, run); done.countDown() })
        presenter.present("app:t", timed = true) { Thread.sleep(2) }
        presenter.present("app:u", timed = false) { }
        presenter.present("app:v", timed = true) { }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("app:t", "app:v"), timings.map { it.first })
        assertTrue(timings.all { it.second >= 0L && it.third >= 0L })
        assertTrue(timings[0].third >= 2_000_000L)
    }
}
