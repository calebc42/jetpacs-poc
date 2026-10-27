// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineStartsTest {
    private val t0 = 1_700_000_000_000L

    @Test
    fun theThirdStartInsideTenMinutesHalts() {
        assertFalse(EngineStarts.shouldHalt(emptyList(), t0))
        val one = EngineStarts.remember(emptyList(), t0)
        assertFalse(EngineStarts.shouldHalt(one, t0 + 1_000))
        val two = EngineStarts.remember(one, t0 + 1_000)
        assertTrue(EngineStarts.shouldHalt(two, t0 + 2_000))
        assertTrue(EngineStarts.shouldHalt(two, t0 + EngineStarts.WINDOW_MS))
    }

    @Test
    fun oldStartsFallOutOfTheWindow() {
        val two = listOf(t0, t0 + 1_000)
        assertFalse(EngineStarts.shouldHalt(two, t0 + EngineStarts.WINDOW_MS + 2_000))
        assertEquals(listOf(t0 + EngineStarts.WINDOW_MS + 2_000),
            EngineStarts.remember(two, t0 + EngineStarts.WINDOW_MS + 2_000))
    }

    @Test
    fun aClockThatWentBackwardsIgnoresTheFuture() {
        val future = listOf(t0 + 60_000, t0 + 61_000)
        assertFalse(EngineStarts.shouldHalt(future, t0))
        assertEquals(listOf(t0), EngineStarts.remember(future, t0))
    }

    @Test
    fun rememberKeepsAShortTail() {
        var list = emptyList<Long>()
        for (i in 0 until 20) list = EngineStarts.remember(list, t0 + i)
        assertEquals(8, list.size)
        assertEquals(t0 + 19, list.last())
    }
}
