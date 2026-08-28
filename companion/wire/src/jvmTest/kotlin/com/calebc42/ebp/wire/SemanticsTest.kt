// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Cross-language conformance and admission gates for SPEC 16.5.1. */
class SemanticsTest {
    private val ebpDir = File(
        System.getProperty("ebp.dir") ?: error("ebp.dir system property not set"),
    )

    @Test
    fun sharedAcceptedAndRejectedGoldensMatchTheReferenceValidator() {
        val lines = ebpDir.resolve("goldens/semantics.golden").readLines()
            .filter(String::isNotBlank)
        assertTrue("semantics corpus truncated", lines.size >= 19)
        for ((index, line) in lines.withIndex()) {
            val case = Json.parseToJsonElement(line.substringAfter(' ')) as JsonObject
            val node = case.getValue("node")
            if ((case["valid"] as JsonPrimitive).content == "true") {
                SpecValidator.validateSurfaceSpec(node, "semantics:$index")
            } else {
                val expected = (case.getValue("reason") as JsonPrimitive).content
                try {
                    SpecValidator.validateSurfaceSpec(node, "semantics:$index")
                    fail("semantics:$index accepted; expected $expected")
                } catch (error: ContentInvalid) {
                    assertTrue(
                        "semantics:$index reported ${error.reason}, expected $expected",
                        error.reason.contains(expected),
                    )
                }
            }
        }
    }

    @Test
    fun semanticActionsUseOrdinaryProfileFeatureAndCaptureGates() {
        fun semanticAction(descriptor: JsonObject) = buildJsonObject {
            put("name", "Control")
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("label", "Act")
                    put("on_action", descriptor)
                })
            })
        }
        fun document(descriptor: JsonObject) = node(
            "column",
            "children" to JsonArray(listOf(
                node("text_input", "id" to JsonPrimitive("draft")),
                node(
                    "text",
                    "text" to JsonPrimitive("Control"),
                    "semantics" to semanticAction(descriptor),
                ),
            )),
        )

        val captured = buildJsonObject {
            put("action", "demo.accessible")
            put("capture_fields", buildJsonArray { add(JsonPrimitive("draft")) })
        }
        SpecValidator.validateSurfaceSpec(document(captured))

        rejects(
            document(buildJsonObject {
                put("action", "demo.accessible")
                put("capture_fields", buildJsonArray { add(JsonPrimitive("missing")) })
            }),
            "stateful node",
        )
        rejects(
            document(buildJsonObject {
                put("builtin", "clipboard.copy")
                put("text", "copy")
            }),
            "not valid in this context",
            advertisedBuiltins = emptySet(),
        )
        rejects(
            document(buildJsonObject {
                put("action", "demo.open")
                put("open_surface", "app:detail")
            }),
            "not valid in this context",
            advertisedFeatures = emptySet(),
        )
    }

    @Test
    fun unknownSemanticMembersAreOpaqueToTheNodeWalker() {
        val hiddenLookalike = buildJsonObject {
            put("t", "text_input")
            put("id", "same")
        }
        val document = node(
            "text",
            "id" to JsonPrimitive("same"),
            "text" to JsonPrimitive("Visible"),
            "semantics" to buildJsonObject {
                put("future_member", hiddenLookalike)
            },
        )
        SpecValidator.validateSurfaceSpec(document)
    }

    private fun rejects(
        node: JsonObject,
        reason: String,
        advertisedBuiltins: Set<String>? = null,
        advertisedFeatures: Set<String>? = null,
    ) {
        try {
            SpecValidator.validateSurfaceSpec(
                node,
                advertisedBuiltins = advertisedBuiltins,
                advertisedFeatures = advertisedFeatures,
            )
            fail("accepted invalid semantics; expected $reason")
        } catch (error: ContentInvalid) {
            assertTrue(
                "reported ${error.reason}, expected $reason",
                error.reason.contains(reason),
            )
        }
    }

    private fun node(type: String, vararg members: Pair<String, Any>): JsonObject =
        buildJsonObject {
            put("t", type)
            for ((name, value) in members) {
                when (value) {
                    is JsonObject -> put(name, value)
                    is JsonArray -> put(name, value)
                    is JsonPrimitive -> put(name, value)
                    else -> error("unsupported test member $name=$value")
                }
            }
        }
}
