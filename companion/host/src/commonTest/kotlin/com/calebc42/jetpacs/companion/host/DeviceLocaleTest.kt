// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

import org.junit.Assert.assertEquals
import org.junit.Test

/** SPEC 20.1.2 (#197): the locale advice is three facts and nothing else. */
class DeviceLocaleTest {
    @Test
    fun twentyFourHourClockIsH23() {
        val advice = localeAdvice("de-DE", true, "Europe/Berlin")
        assertEquals("de-DE", advice.tag)
        assertEquals("h23", advice.hourCycle)
        assertEquals("Europe/Berlin", advice.timeZone)
    }

    @Test
    fun twelveHourClockIsH12() {
        val advice = localeAdvice("en-US", false, "America/Denver")
        assertEquals("h12", advice.hourCycle)
        assertEquals("en-US", advice.tag)
        assertEquals("America/Denver", advice.timeZone)
    }
}
