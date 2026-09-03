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
        selectionOptions = JETPACS_COMPONENTS_SELECTION_OPTIONS,
        trueRequiresParent = JETPACS_COMPONENTS_TRUE_REQUIRES_PARENT,
        extensions = mapOf(
            JETPACS_COMPONENTS_EXTENSION to JETPACS_COMPONENTS_NODE_SCHEMA.keys,
        ),
    )
    private val advertised = CORE_NODE_SET + setOf("lazy_column") +
        JETPACS_COMPONENTS_NODE_SCHEMA.keys

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
            setOf(
                "jetpacs.action",
                "jetpacs.choice",
                "jetpacs.list_item",
                "jetpacs.panel",
                "jetpacs.scope",
                "jetpacs.section_navigator",
                "jetpacs.tabs",
            ),
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
            """{"t":"jetpacs.scope"}""",
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

    @Test
    fun tabsSelectionContractIsClosedControlledAndNonStateful() {
        val valid = Json.parseToJsonElement(
            """{"t":"jetpacs.tabs","id":"projection","options":[{"label":"Preview","value":"preview"},{"label":"Source","value":"source"}],"value":"preview","on_change":{"action":"catalog.change"},"enabled":true,"scrollable":false,"pinned":false}""",
        ) as JsonObject
        val statefuls = SpecValidator.validateSurfaceSpec(
            valid,
            advertisedTypes = advertised,
            nodeVocabulary = vocabulary,
        )
        assertEquals(emptySet<String>(), statefuls.keys)

        val malformed = listOf(
            """{"t":"jetpacs.tabs","id":"tabs","options":[],"value":"preview","on_change":{"action":"x.change"}}""",
            """{"t":"jetpacs.tabs","id":"tabs","options":["preview"],"value":"preview","on_change":{"action":"x.change"}}""",
            """{"t":"jetpacs.tabs","id":"tabs","options":[{"label":"Preview"}],"value":"preview","on_change":{"action":"x.change"}}""",
            """{"t":"jetpacs.tabs","id":"tabs","options":[{"label":"","value":"preview"}],"value":"preview","on_change":{"action":"x.change"}}""",
            """{"t":"jetpacs.tabs","id":"tabs","options":[{"label":"Preview","value":""}],"value":"","on_change":{"action":"x.change"}}""",
            """{"t":"jetpacs.tabs","id":"tabs","options":[{"label":"Preview","value":1}],"value":"preview","on_change":{"action":"x.change"}}""",
            """{"t":"jetpacs.tabs","id":"tabs","options":[{"label":"Preview","value":"preview"},{"label":"Again","value":"preview"}],"value":"preview","on_change":{"action":"x.change"}}""",
            """{"t":"jetpacs.tabs","id":"tabs","options":[{"label":"Preview","value":"preview"}],"value":"source","on_change":{"action":"x.change"}}""",
            """{"t":"jetpacs.tabs","id":"tabs","options":[{"label":"Preview","value":"preview"}],"value":1,"on_change":{"action":"x.change"}}""",
            """{"t":"jetpacs.tabs","id":"tabs","options":[{"label":"Preview","value":"preview","icon":"play"}],"value":"preview","on_change":{"action":"x.change"}}""",
            """{"t":"jetpacs.tabs","id":"tabs","options":[{"label":"Preview","value":"preview"}],"value":"preview","on_change":"x.change"}""",
            """{"t":"jetpacs.tabs","id":"tabs","options":[{"label":"Preview","value":"preview"}],"value":"preview","on_change":{"action":"x.change"},"enabled":"true"}""",
            """{"t":"jetpacs.tabs","id":"tabs","options":[{"label":"Preview","value":"preview"}],"value":"preview","on_change":{"action":"x.change"},"scrollable":0}""",
            """{"t":"jetpacs.tabs","id":"tabs","options":[{"label":"Preview","value":"preview"}],"value":"preview","on_change":{"action":"x.change"},"pinned":"true"}""",
            """{"t":"jetpacs.tabs","id":"tabs","options":[{"label":"Preview","value":"preview"}],"value":"preview","on_change":{"action":"x.change"},"pinned":true}""",
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

        // Variant names are forward-compatible renderer policy. The wire
        // enforces only their string shape so a newer sender can degrade
        // through the legacy scrollable member on an older implementation.
        SpecValidator.validateSurfaceSpec(
            Json.parseToJsonElement(
                """{"t":"jetpacs.tabs","id":"tabs","options":[{"label":"Preview","value":"preview"}],"value":"preview","on_change":{"action":"x.change"},"variant":"future"}""",
            ) as JsonObject,
            advertisedTypes = advertised,
            nodeVocabulary = vocabulary,
        )
        assertThrows(Exception::class.java) {
            SpecValidator.validateSurfaceSpec(
                Json.parseToJsonElement(
                    """{"t":"jetpacs.tabs","id":"tabs","options":[{"label":"Preview","value":"preview"}],"value":"preview","on_change":{"action":"x.change"},"variant":1}""",
                ) as JsonObject,
                advertisedTypes = advertised,
                nodeVocabulary = vocabulary,
            )
        }
    }

    @Test
    fun sectionNavigatorRequiresClosedLeveledOptionsAndRemainsControlled() {
        val valid = Json.parseToJsonElement(
            """{"t":"lazy_column","children":[{"t":"jetpacs.section_navigator","id":"outline","options":[{"label":"Introduction","value":"introduction","level":1},{"label":"Configuration","value":"configuration","level":2}],"value":"configuration","on_change":{"action":"catalog.section.change"},"enabled":true,"pinned":true}]}""",
        ) as JsonObject
        assertEquals(
            emptySet<String>(),
            SpecValidator.validateSurfaceSpec(
                valid,
                advertisedTypes = advertised,
                nodeVocabulary = vocabulary,
            ).keys,
        )

        val malformed = listOf(
            """{"t":"jetpacs.section_navigator","id":"outline","options":[{"label":"Intro","value":"intro"}],"value":"intro","on_change":{"action":"x.change"}}""",
            """{"t":"jetpacs.section_navigator","id":"outline","options":[{"label":"Intro","value":"intro","level":0}],"value":"intro","on_change":{"action":"x.change"}}""",
            """{"t":"jetpacs.section_navigator","id":"outline","options":[{"label":"Intro","value":"intro","level":7}],"value":"intro","on_change":{"action":"x.change"}}""",
            """{"t":"jetpacs.section_navigator","id":"outline","options":[{"label":"Intro","value":"intro","level":"1"}],"value":"intro","on_change":{"action":"x.change"}}""",
            """{"t":"jetpacs.section_navigator","id":"outline","options":[{"label":"Intro","value":"intro","level":1,"icon":"home"}],"value":"intro","on_change":{"action":"x.change"}}""",
            """{"t":"jetpacs.section_navigator","id":"outline","options":[{"label":"Intro","value":"intro","level":1}],"value":"missing","on_change":{"action":"x.change"}}""",
            """{"t":"jetpacs.section_navigator","id":"outline","options":[{"label":"Intro","value":"intro","level":1}],"value":"intro","on_change":{"action":"x.change"},"pinned":true}""",
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

    @Test
    fun pinnedTabsRequireADirectLazyColumnParent() {
        val directChild = Json.parseToJsonElement(
            """{"t":"lazy_column","children":[{"t":"jetpacs.tabs","id":"tabs","options":[{"label":"Preview","value":"preview"}],"value":"preview","on_change":{"action":"x.change"},"pinned":true}]}""",
        ) as JsonObject
        SpecValidator.validateSurfaceSpec(
            directChild,
            advertisedTypes = advertised,
            nodeVocabulary = vocabulary,
        )
    }
}
