// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalElispDesignRuntimeTest {
    @Test
    fun changePersistsThenPublishesThenClosesTheActiveSession() {
        val events = mutableListOf<String>()

        val applied = applyExperimentalDesignRuntimeChange(
            current = false,
            requested = true,
            persist = {
                events += "persist:$it"
                true
            },
            publish = { events += "publish:$it" },
            closeActiveSession = { events += "close" },
        )

        assertTrue(applied)
        assertTrue(events == listOf("persist:true", "publish:true", "close"))
    }

    @Test
    fun persistenceFailureLeavesConfigurationAndSessionUntouched() {
        val events = mutableListOf<String>()

        val applied = applyExperimentalDesignRuntimeChange(
            current = false,
            requested = true,
            persist = {
                events += "persist:$it"
                false
            },
            publish = { events += "publish:$it" },
            closeActiveSession = { events += "close" },
        )

        assertFalse(applied)
        assertTrue(events == listOf("persist:true"))
    }

    @Test
    fun unchangedSettingDoesNotPersistOrReconnect() {
        val events = mutableListOf<String>()

        val applied = applyExperimentalDesignRuntimeChange(
            current = true,
            requested = true,
            persist = {
                events += "persist"
                true
            },
            publish = { events += "publish" },
            closeActiveSession = { events += "close" },
        )

        assertTrue(applied)
        assertTrue(events.isEmpty())
    }
}
