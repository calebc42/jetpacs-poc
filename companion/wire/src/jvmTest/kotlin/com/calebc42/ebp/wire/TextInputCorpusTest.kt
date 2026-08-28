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

/** Permanent seed-20260828 cross-language conformance and normalization rail. */
class TextInputCorpusTest {
    private val ebpDir = File(
        System.getProperty("ebp.dir") ?: error("ebp.dir system property not set"),
    )

    @Test
    fun deterministicTenThousandCaseCorpusMatchesReceiverAndNormalizer() {
        val root = ebpDir.parentFile
        val corpus = File.createTempFile("jetpacs-text-input-", ".jsonl")
        try {
            val process = ProcessBuilder(
                "python3",
                root.resolve("test/generate-text-input-corpus.py").absolutePath,
                corpus.absolutePath,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals("corpus generator failed: $output", 0, process.waitFor())

            var count = 0
            val families = mutableSetOf<Int>()
            corpus.forEachLine(Charsets.UTF_8) { line ->
                val row = Json.parseToJsonElement(line) as JsonObject
                val index = integralLongOrNull(row["index"])!!.toInt()
                val family = integralLongOrNull(row["family"])!!.toInt()
                val expectedValid = (row.getValue("receiver_valid") as JsonPrimitive)
                    .content == "true"
                val node = row.getValue("node")
                try {
                    SpecValidator.validateSurfaceSpec(node, "corpus:$index")
                    if (!expectedValid)
                        fail("corpus:$index family $family accepted unexpectedly")
                } catch (error: ContentInvalid) {
                    if (expectedValid)
                        fail("corpus:$index family $family rejected: ${error.reason}")
                }

                val edit = row.reqObj("edit")
                val normalized = normalizeTextInput(
                    edit.reqString("text"),
                    edit.boolOr("single_line"),
                    edit.stringOrNull("filter"),
                    integralLongOrNull(edit["max_length"]),
                )
                assertEquals(
                    "corpus:$index family $family normalization",
                    edit.reqString("normalized"),
                    normalized,
                )
                count++
                families += family
            }
            assertEquals(10_000, count)
            assertEquals((0 until 32).toSet(), families)
            assertTrue(corpus.length() > 1_000_000L)
        } finally {
            corpus.delete()
        }
    }
}
