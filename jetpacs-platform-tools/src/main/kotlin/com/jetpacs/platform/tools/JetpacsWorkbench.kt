package com.jetpacs.platform.tools

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/** One MCP-ready result with optional machine-readable content. */
internal data class ToolResult(
    val text: String,
    val structured: JsonObject? = null,
    val isError: Boolean = false,
)

/** A lazily rendered, repository-backed MCP resource. */
internal data class Resource(
    val uri: String,
    val name: String,
    val title: String,
    val description: String,
    val text: () -> String,
)

/**
 * Source-backed platform questions shared by the CLI and MCP adapter.
 *
 * Methods return bounded text and never build, load, evaluate, or modify the
 * inspected project. Keeping protocol adaptation out of this class makes each
 * operation directly testable and useful from the command line.
 */
internal class JetpacsWorkbench(internal val workspace: Workspace) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** Report whether every required platform source-of-truth artifact exists. */
    fun doctor(): String {
        val checks = listOf(
            "EBP contract" to workspace.firstExisting("ebp/contract.json"),
            "EBP specification" to workspace.firstExisting("ebp/SPEC.md"),
            "Emacs endpoint" to workspace.firstExisting("ebp.el/lisp/ebp.el"),
            "Jetpacs surface API" to workspace.firstExisting("emacs/jetpacs-surfaces.el"),
            "Companion settings" to workspace.firstExisting("companion/settings.gradle.kts"),
        )
        val missing = checks.filter { it.second == null }
        return buildString {
            appendLine("Jetpacs Platform Tools doctor")
            appendLine("workspace: ${workspace.root}")
            appendLine(
                "repositories root: ${workspace.repositoriesRoot ?: "unavailable (set JETPACS_REPOSITORIES_ROOT or --repositories-root)"}",
            )
            appendLine(
                "org source: ${workspace.orgSourceRoot?.let { "org/ ($it)" } ?: "unavailable (optional)"}",
            )
            appendLine("java: ${System.getProperty("java.version")}")
            appendLine()
            checks.forEach { (label, path) ->
                appendLine("${if (path == null) "MISSING" else "ok"}  $label${path?.let { ": ${workspace.relative(it)}" } ?: ""}")
            }
            appendLine()
            if (missing.isEmpty()) {
                appendLine("Ready: the platform, protocol, and applet source-of-truth artifacts are discoverable.")
            } else {
                appendLine("Not ready: ${missing.size} required artifact(s) are missing.")
            }
        }.trimEnd()
    }

    /** Summarize modules, authority boundaries, contract metadata, and roots. */
    fun projectOverview(): String {
        val extensions = setOf("kt", "kts", "el", "md", "org", "json")
        val counts = linkedMapOf<String, Int>()
        var total = 0
        workspace.sourceFiles(extensions).forEach { path ->
            counts[path.extension.lowercase()] = (counts[path.extension.lowercase()] ?: 0) + 1
            total += 1
        }
        val settings = workspace.firstExisting("companion/settings.gradle.kts")
        val modules = settings?.let { moduleNames(workspace.readText(it)) }.orEmpty()
        val contract = contractDocument()
        val orgCount = workspace.orgSourceRoot?.let { orgRoot ->
            workspace.sourceFiles(setOf("el"), includeOrg = true).count { it.startsWith(orgRoot) }
        }

        return buildString {
            appendLine("Jetpacs project overview")
            appendLine()
            appendLine("Workspace: ${workspace.root}")
            appendLine(
                "Named repositories: ${workspace.repositoriesRoot ?: "unavailable (set JETPACS_REPOSITORIES_ROOT or --repositories-root)"}",
            )
            appendLine("Source documents scanned: $total (${counts.entries.joinToString { "${it.key}=${it.value}" }})")
            if (orgCount != null) {
                appendLine("Org source: org/ ($orgCount Elisp files; separate read-only root)")
            } else {
                appendLine("Org source: unavailable; set JETPACS_ORG_SOURCE or --org-source")
            }
            appendLine()
            appendLine("Authoritative boundaries")
            appendLine("- EBP is the implementation-neutral protocol and owns wire/session/durability behavior.")
            appendLine("- ebp-kmp and wire implement that protocol without depending on Jetpacs, Room, Navigation, or Compose.")
            appendLine("- Jetpacs owns Android persistence/navigation/rendering and the Elisp widget, surface, shell, and app layers.")
            appendLine("- Applets are Elisp applications registered through owner-scoped Jetpacs APIs; they transmit data, never code.")
            appendLine()
            if (modules.isNotEmpty()) {
                appendLine("Companion modules (${modules.size})")
                modules.forEach { appendLine("- $it") }
                appendLine()
            }
            if (contract != null) {
                appendLine("Active EBP contract")
                appendLine("- protocol_version: ${contract.get("protocol_version")}")
                appendLine("- spec_version: ${contract.get("spec_version")}")
                appendLine("- contract_format: ${contract.get("contract_format")}")
                appendLine("- reference_api_version: ${contract.get("reference_api_version")}")
                appendLine()
            }
            appendLine("Canonical local references")
            listOf(
                "docs/ARCHITECTURE-POC3.md",
                "docs/REWRITE-PLAN.md",
                "ebp/SPEC.md",
                "ebp/contract.json",
                "emacs/jetpacs-surfaces.el",
                "emacs/jetpacs-apps.el",
            ).mapNotNull { path -> workspace.firstExisting(path)?.let(workspace::relative) }
                .forEach { appendLine("- $it") }
        }.trimEnd().capped()
    }

    /** Produce a language-aware outline for one explicitly resolved file. */
    fun sourceSummary(requestedPath: String, includePrivate: Boolean = false): String {
        val path = workspace.resolveFile(requestedPath)
        val relative = workspace.relative(path)
        return SourceOutlines.summarize(relative, workspace.readText(path), includePrivate)
    }

    /** Search deterministic source order with an explicit language/Org scope. */
    fun findSymbol(query: String, kind: String?, limit: Int, includeOrg: Boolean = false): String {
        val needle = query.trim()
        if (needle.length !in 2..160) throw UserInputException("query must contain 2..160 characters")
        val boundedLimit = limit.coerceIn(1, 100)
        val normalizedKind = kind?.lowercase()
        val extensions = when (normalizedKind) {
            null, "all" -> setOf("kt", "kts", "el")
            "kotlin" -> setOf("kt", "kts")
            "elisp", "org" -> setOf("el")
            else -> throw UserInputException("kind must be all, kotlin, elisp, or org")
        }
        val orgOnly = normalizedKind == "org"
        if (orgOnly && workspace.orgSourceRoot == null) {
            throw UserInputException("Org source is unavailable; set JETPACS_ORG_SOURCE or --org-source")
        }
        val matches = mutableListOf<String>()
        workspace.sourceFiles(extensions, includeOrg = includeOrg || orgOnly)
            .filter { path -> !orgOnly || path.startsWith(workspace.orgSourceRoot!!) }
            .forEach file@{ path ->
                if (matches.size >= boundedLimit) return@file
                val lines = try {
                    Files.readAllLines(path)
                } catch (_: Exception) {
                    return@file
                }
                lines.forEachIndexed { index, line ->
                    if (matches.size < boundedLimit && line.contains(needle, ignoreCase = true)) {
                        matches += "${workspace.relative(path)}:${index + 1}: ${line.trim().take(320)}"
                    }
                }
            }
        return buildString {
            appendLine("Symbol/source matches for: $needle")
            appendLine(
                "scope: ${kind ?: "all"}; Org source: ${if (includeOrg || orgOnly) "included" else "excluded"}; limit: $boundedLimit",
            )
            appendLine()
            if (matches.isEmpty()) appendLine("No matches.") else matches.forEach(::appendLine)
        }.trimEnd()
    }

    /** Query the active contract by JSON Pointer or bounded scalar/key search. */
    fun contractQuery(pointer: String?, search: String?): String {
        val contract = contractDocument()
            ?: throw UserInputException("no EBP contract.json found at ebp/contract.json")
        if (!search.isNullOrBlank()) {
            val matches = mutableListOf<String>()
            searchJson(contract, "", search, matches, 80)
            return buildString {
                appendLine("EBP contract matches for: $search")
                appendLine()
                if (matches.isEmpty()) appendLine("No matches.") else matches.forEach(::appendLine)
            }.trimEnd().capped()
        }
        if (pointer.isNullOrBlank() || pointer == "/") {
            return buildString {
                appendLine("EBP contract metadata")
                listOf("protocol_version", "spec_version", "contract_format", "reference_api_version")
                    .forEach { key -> appendLine("- $key: ${contract.get(key)}") }
                appendLine("- sections: ${contract.keySet().joinToString()}")
                appendLine()
                appendLine("Pass a JSON Pointer such as /methods/surface.update or use search for focused output.")
            }.trimEnd()
        }
        val selected = resolveJsonPointer(contract, pointer)
            ?: throw UserInputException("JSON Pointer does not exist: $pointer")
        return "EBP contract $pointer\n\n${gson.toJson(selected)}".capped()
    }

    /** Return the named, source-cited architecture invariant set. */
    fun architecture(topic: String?): String {
        val normalized = topic?.lowercase()?.replace('-', '_') ?: "all"
        val sections = linkedMapOf(
            "boundaries" to """
                |Boundaries
                |- EBP is Jetpacs-agnostic. `ebp-`/`:ebp-kmp`/`:wire` own protocol duties only.
                |- `jetpacs-` and Jetpacs core/feature modules own Compose-shaped application behavior.
                |- `:wire` may depend on `:ebp-kmp`; neither may depend on Room, Navigation, Compose, or Jetpacs packages.
                |- The contract decides the boundary: wire vocabulary belongs to EBP; node/surface composition belongs to Jetpacs.
                |Source: docs/ARCHITECTURE-POC3.md and docs/REWRITE-PLAN.md.
            """.trimMargin(),
            "data_flow" to """
                |Durable data flow
                |`:wire transport -> :ebp-kmp reducer/transaction SPI -> :core:ebp-store Room transaction -> committed Room state -> read-only repository Flow -> screen state -> renderer model -> Compose renderer`.
                |Emacs remains authoritative for application state. Room persists accepted presentation and delivery state; it is not a second application authority.
                |Source: docs/ARCHITECTURE-POC3.md.
            """.trimMargin(),
            "elisp" to """
                |Elisp layering
                |- `ebp.el` is the protocol endpoint: framing, auth, sessions, revisions, event receipts, and module plumbing.
                |- Jetpacs is the application: builders, surfaces, actions, shell, chrome, and app identity.
                |- Builder forms return the actual plist/vector UI IR. The same datum is introspected, gated, canonicalized, transmitted, edited, and re-pushed; there is no parallel generated model.
                |- The applet manifest is derived with the native reader and hashes exact registrations, signatures, docstrings, provenance, and source bytes without evaluation.
                |- Device-originated action handlers must not prompt synchronously; continue interactive flows outside dispatch.
                |- App registrations are owner-scoped with `with-jetpacs-owner`.
                |Source: docs/REWRITE-PLAN.md, emacs/jetpacs-devtools.el, and jetpacs-applet-mcp/DETERMINISM.md.
            """.trimMargin(),
            "testing" to """
                |Testing ladder
                |- Protocol behavior is tested against EBP contract projections and goldens.
                |- Pure reducers and stores are tested before Android adapters.
                |- Android compilation is not device proof; persistence/platform boundaries receive connected-device coverage.
                |- Applets receive offline ERT coverage plus narrow device smoke flows for presentation and interaction.
                |Source: docs/ARCHITECTURE-POC3.md and docs/PLAN-jetpacs-apps.md.
            """.trimMargin(),
            "applets" to """
                |Applet contract
                |- An applet is an Elisp application over Jetpacs' typed node/action/surface APIs.
                |- It claims registrations under one owner, defines actions, registers a root, then calls `jetpacs-defapp`.
                |- EBP carries declarative data and named actions; applets never transmit executable Elisp.
                |- `jetpacs-applet-mcp` is the focused API discovery, validation, and scaffold server for this layer.
                |Source: emacs/jetpacs-apps.el and ebp/SPEC.md.
            """.trimMargin(),
        )
        if (normalized == "all") return sections.values.joinToString("\n\n").capped()
        return sections[normalized]
            ?: throw UserInputException("unknown topic '$topic'; choose ${sections.keys.joinToString()} or all")
    }

    /** Return bounded excerpts at curated locations in the actual checkout. */
    fun findExamples(pattern: String): String {
        val normalized = pattern.lowercase().replace('-', '_')
        val examples = patterns[normalized]
            ?: throw UserInputException("unknown pattern '$pattern'; choose ${patterns.keys.joinToString()}")
        return buildString {
            appendLine("Jetpacs pattern: ${examples.title}")
            appendLine(examples.description)
            appendLine()
            examples.locations.forEach { location ->
                appendLine("--- ${location.path} (${location.needle}) ---")
                appendLine(excerpt(location.path, location.needle))
                appendLine()
            }
        }.trimEnd().capped()
    }

    /** Describe the complete closed, read-only MCP tool surface. */
    fun toolDefinitions(): JsonArray = JsonArray().apply {
        add(tool(
            "jetpacs_project_overview",
            "Jetpacs project overview",
            "Map the detected Jetpacs workspace, its implementation boundaries, modules, active EBP contract, and canonical local references.",
            emptySchema(),
        ))
        add(tool(
            "jetpacs_source_summary",
            "Summarize source API",
            "Return a token-efficient, line-numbered outline of a workspace Kotlin/Elisp file or an explicitly namespaced org/ Elisp file without evaluating it.",
            schema(
                properties = mapOf(
                    "path" to stringProperty("Workspace-relative .kt/.kts/.el path, or org/FILE.el from the separate Org source root."),
                    "includePrivate" to booleanProperty("Include private/internal implementation declarations; defaults to false."),
                ),
                required = listOf("path"),
            ),
        ))
        add(tool(
            "jetpacs_find_symbol",
            "Find Jetpacs symbol",
            "Search Kotlin and/or Elisp sources for a symbol or literal and return bounded file:line matches.",
            schema(
                properties = mapOf(
                    "query" to stringProperty("Literal symbol or source text, 2..160 characters."),
                    "kind" to enumProperty(
                        listOf("all", "kotlin", "elisp", "org"),
                        "Source language scope; org searches only the separately allowlisted Org checkout. Defaults to all.",
                    ),
                    "limit" to integerProperty("Maximum results, 1..100; defaults to 30."),
                    "includeOrg" to booleanProperty("Include the separate read-only Org Elisp source tree; defaults to false."),
                ),
                required = listOf("query"),
            ),
        ))
        add(tool(
            "jetpacs_contract_query",
            "Query EBP contract",
            "Inspect the active EBP contract by JSON Pointer, or search its keys and scalar values. With neither argument, return metadata and section names.",
            schema(
                properties = mapOf(
                    "pointer" to stringProperty("RFC 6901 JSON Pointer, for example /methods/surface.update."),
                    "search" to stringProperty("Case-insensitive contract search term."),
                ),
            ),
        ))
        add(tool(
            "jetpacs_architecture",
            "Explain Jetpacs architecture",
            "Return source-backed architectural invariants for boundaries, data flow, Elisp, testing, applets, or all topics.",
            schema(
                properties = mapOf(
                    "topic" to enumProperty(
                        listOf("all", "boundaries", "data_flow", "elisp", "testing", "applets"),
                        "Architecture topic; defaults to all.",
                    ),
                ),
            ),
        ))
        add(tool(
            "jetpacs_find_examples",
            "Find architecture examples",
            "Return small excerpts from the real codebase for a named implementation pattern; no synthetic APIs or templates.",
            schema(
                properties = mapOf(
                    "pattern" to enumProperty(patterns.keys.toList(), "Pattern to locate."),
                ),
                required = listOf("pattern"),
            ),
        ))
        add(tool(
            "jetpacs_doctor",
            "Check Jetpacs workspace",
            "Verify that the EBP contract/spec, Emacs endpoint, surface API, and Companion settings are discoverable.",
            emptySchema(),
        ))
    }

    /** Execute one named tool while converting user failures to tool errors. */
    fun callTool(name: String, arguments: JsonObject): ToolResult = try {
        when (name) {
            "jetpacs_project_overview" -> ToolResult(projectOverview())
            "jetpacs_source_summary" -> ToolResult(
                sourceSummary(arguments.requiredString("path"), arguments.optionalBoolean("includePrivate") ?: false),
            )
            "jetpacs_find_symbol" -> ToolResult(
                findSymbol(
                    arguments.requiredString("query"),
                    arguments.optionalString("kind"),
                    arguments.optionalInt("limit") ?: 30,
                    arguments.optionalBoolean("includeOrg") ?: false,
                ),
            )
            "jetpacs_contract_query" -> ToolResult(
                contractQuery(arguments.optionalString("pointer"), arguments.optionalString("search")),
            )
            "jetpacs_architecture" -> ToolResult(architecture(arguments.optionalString("topic")))
            "jetpacs_find_examples" -> ToolResult(findExamples(arguments.requiredString("pattern")))
            "jetpacs_doctor" -> ToolResult(doctor())
            else -> throw UnknownToolException(name)
        }
    } catch (error: UnknownToolException) {
        throw error
    } catch (error: Exception) {
        ToolResult("${error.javaClass.simpleName}: ${error.message ?: "tool failed"}", isError = true)
    }

    /** Return stable resource identities backed by fresh repository reads. */
    fun resources(): List<Resource> = listOf(
        Resource(
            uri = "jetpacs://project/implementation-guide",
            name = "implementation-guide",
            title = "Jetpacs platform implementation guide",
            description = "Layered workspace and platform-tools instructions for bounded, source-backed implementation work.",
            text = ::implementationGuide,
        ),
        Resource(
            uri = "jetpacs://project/overview",
            name = "project-overview",
            title = "Jetpacs project overview",
            description = "Detected modules, implementation boundaries, and canonical local references.",
            text = ::projectOverview,
        ),
        Resource(
            uri = "jetpacs://architecture",
            name = "architecture",
            title = "Jetpacs architecture",
            description = "The protocol/platform/application boundaries and durable data flow.",
            text = { architecture("all") },
        ),
        Resource(
            uri = "jetpacs://ebp/contract-metadata",
            name = "ebp-contract-metadata",
            title = "Active EBP contract metadata",
            description = "Versions and top-level sections of the active machine-readable EBP contract.",
            text = { contractQuery(null, null) },
        ),
    )

    /** Read the root and nearest local agent contracts in their application order. */
    private fun implementationGuide(): String = buildString {
        appendLine("# Workspace instructions (`AGENTS.md`)")
        appendLine()
        appendLine(workspace.readText(workspace.resolveFile("AGENTS.md")))
        appendLine()
        appendLine("# Platform-tools instructions (`jetpacs-platform-tools/AGENTS.md`)")
        appendLine()
        append(workspace.readText(workspace.resolveFile("jetpacs-platform-tools/AGENTS.md")))
    }.trimEnd().capped()

    /** Describe repeatable platform implementation/review workflows. */
    fun promptDefinitions(): JsonArray = JsonArray().apply {
        add(prompt(
            "implement_ebp_change",
            "Plan an EBP change",
            "Trace one protocol change through the contract, endpoint, store, renderer, Elisp API, and conformance tests.",
            listOf("change" to true),
        ))
        add(prompt(
            "review_jetpacs_boundary",
            "Review an architecture boundary",
            "Review a proposed change against EBP/Jetpacs and storage/rendering boundaries.",
            listOf("change" to true, "path" to false),
        ))
    }

    /** Materialize one workflow prompt from closed, validated arguments. */
    fun getPrompt(name: String, arguments: JsonObject): JsonObject {
        val change = arguments.requiredString("change")
        val path = arguments.optionalString("path")
        val text = when (name) {
            "implement_ebp_change" -> """
                |Implement this EBP change: $change
                |
                |Read jetpacs://project/implementation-guide first. Then query the relevant part of contract.json and read the normative SPEC section. Identify both endpoint implementations and every persistence/rendering consumer. Preserve the EBP-vs-Jetpacs boundary: protocol behavior belongs in ebp-kmp/wire/ebp.el; product policy and node composition belong in Jetpacs. Define semantic and byte-exact conformance cases before changing adapters. Report any spec/contract/golden mismatch instead of copying the mismatch.
            """.trimMargin()
            "review_jetpacs_boundary" -> """
                |Review this proposed Jetpacs change: $change
                |${path?.let { "Primary path: $it\n" } ?: ""}
                |Read jetpacs://project/implementation-guide first. Check dependency direction, authority, durability timing, platform leakage into EBP, duplicate state, applet owner scoping, and test coverage. Use jetpacs_architecture and jetpacs_find_examples for source-backed precedents. Separate correctness blockers from maintainability suggestions.
            """.trimMargin()
            else -> throw UnknownPromptException(name)
        }
        return JsonObject().apply {
            addProperty("description", "Jetpacs development workflow")
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", text)
                    })
                })
            })
        }
    }

    private fun contractDocument(): JsonObject? {
        val path = workspace.firstExisting("ebp/contract.json") ?: return null
        return gson.fromJson(workspace.readText(path), JsonObject::class.java)
    }

    private fun moduleNames(settings: String): List<String> =
        Regex("""(?m)^\s*include\(([^\n]+)\)""").findAll(settings)
            .flatMap { match -> Regex("\"(:[^\"]+)\"").findAll(match.groupValues[1]).map { it.groupValues[1] } }
            .toList()

    private fun resolveJsonPointer(root: JsonElement, pointer: String): JsonElement? {
        if (pointer.isEmpty()) return root
        if (!pointer.startsWith('/')) throw UserInputException("JSON Pointer must be empty or begin with '/'")
        var current = root
        pointer.drop(1).split('/').forEach { raw ->
            val token = raw.replace("~1", "/").replace("~0", "~")
            current = when {
                current.isJsonObject -> current.asJsonObject.get(token) ?: return null
                current.isJsonArray -> token.toIntOrNull()?.let { current.asJsonArray.getOrNull(it) } ?: return null
                else -> return null
            }
        }
        return current
    }

    private fun searchJson(
        element: JsonElement,
        pointer: String,
        needle: String,
        matches: MutableList<String>,
        limit: Int,
    ) {
        if (matches.size >= limit) return
        when {
            element.isJsonObject -> element.asJsonObject.entrySet().forEach { (key, value) ->
                if (matches.size >= limit) return@forEach
                val child = "$pointer/${escapePointer(key)}"
                if (key.contains(needle, ignoreCase = true)) matches += "$child  ${preview(value)}"
                searchJson(value, child, needle, matches, limit)
            }
            element.isJsonArray -> element.asJsonArray.forEachIndexed { index, value ->
                searchJson(value, "$pointer/$index", needle, matches, limit)
            }
            element !is JsonNull && element.toString().contains(needle, ignoreCase = true) ->
                matches += "${pointer.ifEmpty { "/" }}  ${preview(element)}"
        }
    }

    private fun preview(value: JsonElement): String {
        val rendered = if (value.isJsonPrimitive) value.toString() else gson.toJson(value)
        return rendered.replace(Regex("\\s+"), " ").take(240)
    }

    private fun escapePointer(value: String): String = value.replace("~", "~0").replace("/", "~1")

    private fun excerpt(requestedPath: String, needle: String): String {
        val path = workspace.resolveFile(requestedPath)
        val lines = Files.readAllLines(path)
        val match = lines.indexOfFirst { it.contains(needle, ignoreCase = true) }
        if (match < 0) return "Pattern moved; inspect ${workspace.relative(path)} with jetpacs_source_summary."
        val from = (match - 5).coerceAtLeast(0)
        val to = (match + 16).coerceAtMost(lines.size)
        return (from until to).joinToString("\n") { index -> "${index + 1}: ${lines[index]}" }
    }

    private data class PatternLocation(val path: String, val needle: String)
    private data class Pattern(
        val title: String,
        val description: String,
        val locations: List<PatternLocation>,
    )

    private val patterns = linkedMapOf(
        "ebp_endpoint" to Pattern(
            "EBP endpoint boundary",
            "Compare the Emacs endpoint contract with the Kotlin protocol engine; application code stays above both.",
            listOf(
                PatternLocation("ebp.el/lisp/ebp.el", "cl-defstruct (ebp-client"),
                PatternLocation(
                    "ebp-kmp/wire/src/jvmMain/kotlin/com/calebc42/ebp/wire/CompanionEngine.kt",
                    "class CompanionEngine",
                ),
            ),
        ),
        "durable_store" to Pattern(
            "Storage-neutral durability",
            "The reducer contract is portable; the Room-backed adapter supplies atomic persistence.",
            listOf(
                PatternLocation(
                    "ebp-kmp/ebp-kmp/src/commonMain/kotlin/com/calebc42/ebp/wire/EbpDurableStore.kt",
                    "interface EbpDurableStore",
                ),
                PatternLocation(
                    "companion/core/ebp-store/src/commonMain/kotlin/com/calebc42/jetpacs/core/ebpstore/RoomEbpDurableStore.kt",
                    "class RoomEbpDurableStore",
                ),
            ),
        ),
        "renderer_node" to Pattern(
            "Compose node dispatch",
            "The renderer consumes accepted EBP data and does not reconstruct application behavior.",
            listOf(
                PatternLocation(
                    "ebp-compose/renderer/compose/src/main/kotlin/com/calebc42/ebp/renderer/compose/CoreComposeRenderer.kt",
                    "fun RenderCoreComposeNode",
                ),
            ),
        ),
        "elisp_action" to Pattern(
            "Owner-scoped Elisp action",
            "Actions register through the Jetpacs semantic boundary and execute under dispatch/flow rules.",
            listOf(
                PatternLocation("emacs/jetpacs-surfaces.el", "cl-defun jetpacs-defaction"),
                PatternLocation("glasspane/glasspane.el", "defun glasspane--on-home"),
            ),
        ),
        "applet_registration" to Pattern(
            "Tier-1 applet registration",
            "A real app claims owner-scoped roots and actions before registering app identity and destinations.",
            listOf(
                PatternLocation("glasspane/glasspane.el", "defun glasspane-register"),
                PatternLocation("emacs/jetpacs-apps.el", "cl-defun jetpacs-defapp"),
            ),
        ),
        "navigation" to Pattern(
            "Navigation 3 policy",
            "Serializable Jetpacs navigation keys stay out of the EBP protocol vocabulary.",
            listOf(
                PatternLocation(
                    "companion/core/navigation/src/commonMain/kotlin/com/calebc42/jetpacs/core/navigation/JetpacsNavKey.kt",
                    "sealed interface JetpacsNavKey",
                ),
            ),
        ),
    )

    companion object JsonSchemas {
        private fun tool(name: String, title: String, description: String, inputSchema: JsonObject): JsonObject =
            JsonObject().apply {
                addProperty("name", name)
                addProperty("title", title)
                addProperty("description", description)
                add("inputSchema", inputSchema)
                add("annotations", JsonObject().apply {
                    addProperty("readOnlyHint", true)
                    addProperty("destructiveHint", false)
                    addProperty("idempotentHint", true)
                    addProperty("openWorldHint", false)
                })
            }

        private fun schema(
            properties: Map<String, JsonObject>,
            required: List<String> = emptyList(),
        ): JsonObject = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply { properties.forEach { (name, value) -> add(name, value) } })
            if (required.isNotEmpty()) add("required", JsonArray().apply { required.forEach(::add) })
            addProperty("additionalProperties", false)
        }

        private fun emptySchema(): JsonObject = schema(emptyMap())

        private fun stringProperty(description: String): JsonObject = JsonObject().apply {
            addProperty("type", "string")
            addProperty("description", description)
        }

        private fun booleanProperty(description: String): JsonObject = JsonObject().apply {
            addProperty("type", "boolean")
            addProperty("description", description)
        }

        private fun integerProperty(description: String): JsonObject = JsonObject().apply {
            addProperty("type", "integer")
            addProperty("description", description)
        }

        private fun enumProperty(values: List<String>, description: String): JsonObject =
            stringProperty(description).apply { add("enum", JsonArray().apply { values.forEach(::add) }) }

        private fun prompt(
            name: String,
            title: String,
            description: String,
            arguments: List<Pair<String, Boolean>>,
        ): JsonObject = JsonObject().apply {
            addProperty("name", name)
            addProperty("title", title)
            addProperty("description", description)
            add("arguments", JsonArray().apply {
                arguments.forEach { (argumentName, required) ->
                    add(JsonObject().apply {
                        addProperty("name", argumentName)
                        addProperty("required", required)
                    })
                }
            })
        }
    }
}

internal class UnknownToolException(name: String) : RuntimeException("unknown tool: $name")
internal class UnknownPromptException(name: String) : RuntimeException("unknown prompt: $name")

/** Read a required non-blank string from a closed MCP argument object. */
internal fun JsonObject.requiredString(name: String): String =
    optionalString(name)?.takeIf { it.isNotBlank() }
        ?: throw UserInputException("missing required non-empty string argument '$name'")

/** Read an optional string while rejecting JSON values of another type. */
internal fun JsonObject.optionalString(name: String): String? {
    val value = get(name) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
        throw UserInputException("argument '$name' must be a string")
    }
    return value.asString
}

/** Read an optional boolean while rejecting JSON values of another type. */
internal fun JsonObject.optionalBoolean(name: String): Boolean? {
    val value = get(name) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
        throw UserInputException("argument '$name' must be a boolean")
    }
    return value.asBoolean
}

/** Read an optional exact integer while rejecting fractions and overflow. */
internal fun JsonObject.optionalInt(name: String): Int? {
    val value = get(name) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
        throw UserInputException("argument '$name' must be an integer")
    }
    return value.asString.toIntOrNull()
        ?: throw UserInputException("argument '$name' must be an integer")
}

private fun JsonArray.getOrNull(index: Int): JsonElement? = if (index in 0 until size()) get(index) else null
