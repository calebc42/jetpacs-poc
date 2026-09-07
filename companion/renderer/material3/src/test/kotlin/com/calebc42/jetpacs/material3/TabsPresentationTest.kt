// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabsPresentationTest {
    @Test
    fun smallerSnapshotClampsInvalidRetainedPageWithoutAUserSettle() {
        assertEquals(1, retainedTabsResetIndex(
            lastReported = 4,
            currentPage = 2,
            pageCount = 3,
            initial = 1,
        ))
        assertEquals(0, retainedTabsResetIndex(
            lastReported = 1,
            currentPage = 3,
            pageCount = 3,
            initial = 0,
        ))
    }

    @Test
    fun validRetainedPageNeedsNoAuthoredReset() {
        assertNull(retainedTabsResetIndex(
            lastReported = 2,
            currentPage = 2,
            pageCount = 3,
            initial = 0,
        ))
    }
}
