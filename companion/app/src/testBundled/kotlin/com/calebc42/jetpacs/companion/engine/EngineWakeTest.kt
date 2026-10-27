// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

import android.content.ServiceConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC 5.8 bounds over a fake binder and clock: once per 60 s, released at READY or 10 s. */
class EngineWakeTest {
    private class Fake {
        var clock = 1_000_000L
        val bound = mutableListOf<ServiceConnection>()
        val unbound = mutableListOf<ServiceConnection>()
        val scheduled = mutableListOf<Pair<Runnable, Long>>()
        var refuse = false
        val wake = EngineWake(
            bind = { if (refuse) false else { bound += it; true } },
            unbind = { unbound += it },
            postDelayed = { r, delay -> scheduled += r to delay },
            cancel = { r -> scheduled.removeAll { it.first === r } },
            now = { clock },
        )
    }

    @Test
    fun bindsOnceAndCoalescesWithinSixtySeconds() {
        val f = Fake()
        assertTrue(f.wake.request())
        assertEquals(1, f.bound.size)
        assertEquals(listOf(EngineWake.WAKE_WAIT_MS), f.scheduled.map { it.second })
        assertFalse(f.wake.request())
        f.wake.release("ready")
        assertEquals(1, f.unbound.size)
        assertTrue(f.scheduled.isEmpty())
        f.clock += 30_000
        assertFalse(f.wake.request())
        f.clock += 30_000
        assertTrue(f.wake.request())
        assertEquals(2, f.bound.size)
    }

    @Test
    fun theTenSecondBoundReleasesTheBinding() {
        val f = Fake()
        assertTrue(f.wake.request())
        val timeout = f.scheduled.single().first
        timeout.run()
        assertEquals(1, f.unbound.size)
        assertFalse(f.wake.isHeld)
        f.wake.release("again")
        assertEquals(1, f.unbound.size)
    }

    @Test
    fun aRefusedBindHoldsNothing() {
        val f = Fake().apply { refuse = true }
        assertFalse(f.wake.request())
        assertFalse(f.wake.isHeld)
        assertTrue(f.scheduled.isEmpty())
        f.refuse = false
        f.clock += EngineWake.WAKE_INTERVAL_MS
        assertTrue(f.wake.request())
    }
}
