// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MonthGridPresentationTest {

    @Test
    fun unchangedAuthoredMonthRetainsTheUsersViewedMonth() {
        val saved = MonthGridPresentation(
            authoredMonth = "2026-01",
            shownMonth = "2026-02",
        )

        assertSame(saved, reconcileMonthGridPresentation(saved, "2026-01"))
    }

    @Test
    fun authoredChangeWhileInactiveReseedsOnReactivation() {
        // A: authored January -> user browses February -> switch to B ->
        // Emacs authors March for inactive A -> switch back to A.
        val savedInactiveA = MonthGridPresentation(
            authoredMonth = "2026-01",
            shownMonth = "2026-02",
        )

        assertEquals(
            MonthGridPresentation("2026-03", "2026-03"),
            reconcileMonthGridPresentation(savedInactiveA, "2026-03"),
        )
    }
}
