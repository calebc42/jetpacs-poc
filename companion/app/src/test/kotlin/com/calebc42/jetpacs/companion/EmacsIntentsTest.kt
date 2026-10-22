// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class EmacsIntentsTest {
    @Test
    fun notificationTargetsAndroidEmacs() {
        assertEquals("org.gnu.emacs", ANDROID_EMACS_PACKAGE)
    }
}
