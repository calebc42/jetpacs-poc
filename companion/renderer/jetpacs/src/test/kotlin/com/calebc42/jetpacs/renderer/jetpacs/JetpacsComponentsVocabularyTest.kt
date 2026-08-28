// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import com.calebc42.ebp.wire.CORE_NODE_SET
import com.calebc42.ebp.wire.EBP_NODE_VOCABULARY
import com.calebc42.ebp.wire.NODE_SCHEMA
import com.calebc42.ebp.wire.SpecValidator
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JetpacsComponentsVocabularyTest {
    private fun repositoryFile(relative: String): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            listOf(
                File(directory, relative),
                File(directory, "../$relative"),
                File(directory, "../../$relative"),
            ).firstOrNull(File::isFile)?.let { return it.canonicalFile }
            directory = directory.parentFile
        }
        error("$relative not found")
    }

    private val vocabulary = EBP_NODE_VOCABULARY.copy(
        schema = NODE_SCHEMA + JETPACS_COMPONENTS_NODE_SCHEMA,
        statefulWhenPresent = EBP_NODE_VOCABULARY.statefulWhenPresent +
            JETPACS_COMPONENTS_STATEFUL_WHEN_PRESENT,
        atLeastOneNonEmpty = JETPACS_COMPONENTS_AT_LEAST_ONE_NON_EMPTY,
        extensions = mapOf(
            JETPACS_COMPONENTS_EXTENSION to JETPACS_COMPONENTS_NODE_SCHEMA.keys,
        ),
    )
    private val advertised = CORE_NODE_SET + JETPACS_COMPONENTS_NODE_SCHEMA.keys

    @Test
    fun generatedVocabularyMatchesManifest() {
        val manifest = Json.parseToJsonElement(
            repositoryFile("renderer-extensions/jetpacs-components.json").readText(),
        ).jsonObject
        assertEquals(
            manifest.getValue("extension").jsonPrimitive.content,
            JETPACS_COMPONENTS_EXTENSION,
        )
        assertEquals(
            manifest.getValue("node_types").jsonArray.map { it.jsonPrimitive.content },
            JETPACS_COMPONENTS_NODE_SCHEMA.keys.toList(),
        )
        assertEquals(
            setOf("jetpacs.action", "jetpacs.choice", "jetpacs.panel"),
            JETPACS_COMPONENTS_TARGET_NODE_TYPES.getValue("app"),
        )
        assertEquals(emptySet<String>(),
            JETPACS_COMPONENTS_TARGET_NODE_TYPES.getValue("dialog"))
    }

    @Test
    fun goldenNodesPassInstalledAdmission() {
        repositoryFile("renderer-extensions/jetpacs-components.golden")
            .readLines()
            .filter(String::isNotBlank)
            .forEachIndexed { index, line ->
                SpecValidator.validateSurfaceSpec(
                    Json.parseToJsonElement(line.substringAfter(' ')) as JsonObject,
                    path = "jetpacs-golden[$index]",
                    advertisedTypes = advertised,
                    nodeVocabulary = vocabulary,
                )
            }
    }

    @Test
    fun malformedChoiceAndEmptyLabelsAreRejected() {
        val malformed = listOf(
            """{"t":"jetpacs.choice","id":"choice","label":"Choice","checked":"false","on_change":{"action":"x.change"}}""",
            """{"t":"jetpacs.action","label":"","on_tap":{"action":"x.run"}}""",
            """{"t":"jetpacs.panel","label":"","children":[]}""",
        )
        malformed.forEach { json ->
            assertThrows(Exception::class.java) {
                SpecValidator.validateSurfaceSpec(
                    Json.parseToJsonElement(json) as JsonObject,
                    advertisedTypes = advertised,
                    nodeVocabulary = vocabulary,
                )
            }
        }
    }
}
