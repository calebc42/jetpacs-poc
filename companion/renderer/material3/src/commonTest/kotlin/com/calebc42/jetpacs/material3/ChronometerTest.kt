// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import org.junit.Test
import org.junit.Assert.assertEquals

class ChronometerTest {
    @Test
    fun formatsMinutesThenHours() {
        assertEquals("0:00", formatChronometer(0))
        assertEquals("0:59", formatChronometer(59_999))
        assertEquals("12:05", formatChronometer(725_000))
        assertEquals("1:00:00", formatChronometer(3_600_000))
        assertEquals("27:03:04", formatChronometer(97_384_000))
    }

    @Test
    fun negativeSpansReadAsZero() {
        assertEquals("0:00", formatChronometer(-5_000))
    }

    @Test
    fun spanCountsUpOrDown() {
        val base = 1_761_690_600_000L // epoch ms beyond the Int range
        assertEquals(90_000L, chronometerSpan(base, countDown = false, nowMs = base + 90_000L))
        assertEquals(30_000L, chronometerSpan(base, countDown = true, nowMs = base - 30_000L))
        assertEquals("0:00", formatChronometer(chronometerSpan(base, true, base + 1_000L)))
    }
}
