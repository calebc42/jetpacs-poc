// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Cross-language conformance witnesses for the format-10 §17.4 envelope. */
class TextInputContractTest {
    private val ebpDir = File(
        System.getProperty("ebp.dir") ?: error("ebp.dir system property not set"),
    )

    @Test
    fun sharedAcceptedAndRejectedGoldensMatchTheReceiver() {
        val lines = ebpDir.resolve("goldens/text-input.golden").readLines()
            .filter(String::isNotBlank)
        assertTrue("text-input corpus truncated", lines.size >= 27)
        for ((index, line) in lines.withIndex()) {
            val case = Json.parseToJsonElement(line.substringAfter(' ')) as JsonObject
            val node = case.getValue("node")
            if ((case.getValue("valid") as JsonPrimitive).content == "true") {
                SpecValidator.validateSurfaceSpec(node, "text-input:$index")
            } else {
                val expected = (case.getValue("reason") as JsonPrimitive).content
                try {
                    SpecValidator.validateSurfaceSpec(node, "text-input:$index")
                    fail("text-input:$index accepted; expected $expected")
                } catch (error: ContentInvalid) {
                    assertTrue(
                        "text-input:$index reported ${error.reason}, expected $expected",
                        error.reason.contains(expected),
                    )
                }
            }
        }
    }

    @Test
    fun localNormalizationUsesContractOrderAndScalarUnits() {
        assertEquals("12", normalizeTextInput("a1\n😀2", true, "digits", 2))
        assertEquals("a😀", truncateTextInputScalars("a😀b", 2))
        assertEquals(3, textInputScalarToUtf16("a😀b", 2))
        assertTrue(textInputFilterMatches("Az09", "alnum"))
        assertTrue(textInputFilterMatches("café", "future-filter"))
    }
}
