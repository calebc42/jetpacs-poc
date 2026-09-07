// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import androidx.compose.ui.text.AnnotatedString
import com.calebc42.ebp.renderer.compose.MaskVisualTransformation
import org.junit.Assert.assertEquals
import org.junit.Test

class MaskTransformationTest {
    @Test
    fun slotsConsumeUnicodeScalarsAndMappingsRemainTotal() {
        val transformed = MaskVisualTransformation("##-#").filter(AnnotatedString("1😀2"))

        assertEquals("1😀-2", transformed.text.text)
        assertEquals(listOf(0, 1, 2, 4, 5), (0..4).map {
            transformed.offsetMapping.originalToTransformed(it)
        })
        assertEquals(listOf(0, 1, 2, 3, 3, 4), (0..5).map {
            transformed.offsetMapping.transformedToOriginal(it)
        })
    }

    @Test
    fun astralLiteralAndOverflowKeepBothMappingsInBounds() {
        val prefixed = MaskVisualTransformation("😀#").filter(AnnotatedString("a"))
        assertEquals("😀a", prefixed.text.text)
        assertEquals(listOf(2, 3), (0..1).map {
            prefixed.offsetMapping.originalToTransformed(it)
        })
        assertEquals(listOf(0, 0, 0, 1), (0..3).map {
            prefixed.offsetMapping.transformedToOriginal(it)
        })

        val overflow = MaskVisualTransformation("#").filter(AnnotatedString("😀x"))
        assertEquals("😀x", overflow.text.text)
        assertEquals(listOf(0, 1, 2, 3), (0..3).map {
            overflow.offsetMapping.originalToTransformed(it)
        })
        assertEquals(listOf(0, 1, 2, 3), (0..3).map {
            overflow.offsetMapping.transformedToOriginal(it)
        })
    }
}
