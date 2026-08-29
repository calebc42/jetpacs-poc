// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class JetpacsEditorTest {
    @Test
    fun logicalLineStartsIncludeEmptyAndTrailingLinesWithoutCountingWraps() {
        assertArrayEquals(intArrayOf(0), logicalLineStarts(""))
        assertArrayEquals(intArrayOf(0), logicalLineStarts("a long visual line"))
        assertArrayEquals(intArrayOf(0, 2, 3), logicalLineStarts("a\n\n"))
        assertArrayEquals(intArrayOf(0, 3), logicalLineStarts("😀\nx"))
    }
}
