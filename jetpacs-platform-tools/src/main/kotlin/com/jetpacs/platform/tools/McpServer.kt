package com.jetpacs.platform.tools

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets

/** Minimal, bounded MCP server for the static read-only workbench. */
internal class McpServer(
    private val workbench: JetpacsWorkbench,
    private val version: String,
) {
    private val gson = Gson()
    private var initialized = false
    private var initializeSeen = false
    private var negotiatedVersion = LATEST_PROTOCOL_VERSION

    /** Serve newline-delimited JSON-RPC until input reaches EOF. */
    fun serve(reader: BufferedReader, writer: BufferedWriter) {
        reader.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val response = handleLine(line)
            if (response != null) {
                writer.write(gson.toJson(response))
                writer.newLine()
                writer.flush()
            }
        }
    }

    /** Parse and dispatch one bounded line; notifications return null. */
    internal fun handleLine(line: String): JsonObject? {
        if (line.toByteArray(StandardCharsets.UTF_8).size > MAX_MESSAGE_BYTES) {
            return errorResponse(JsonNull.INSTANCE, INVALID_REQUEST, "MCP message exceeds $MAX_MESSAGE_BYTES bytes")
        }
        val parsed = try {
            JsonParser.parseString(line)
        } catch (_: JsonParseException) {
            return errorResponse(JsonNull.INSTANCE, PARSE_ERROR, "Parse error")
        }
        if (!parsed.isJsonObject) {
            return errorResponse(JsonNull.INSTANCE, INVALID_REQUEST, "Request must be a JSON object")
        }
        return handleRequest(parsed.asJsonObject)
    }

    private fun handleRequest(request: JsonObject): JsonObject? {
        val hasId = request.has("id")
        val rawId = request.get("id")
        val validId = !hasId || (
            rawId != null && rawId !is JsonNull && rawId.isJsonPrimitive &&
                (rawId.asJsonPrimitive.isString || rawId.asJsonPrimitive.isNumber)
            )
        val id = if (hasId && validId) rawId else JsonNull.INSTANCE
        val methodElement = request.get("method")
        if (!validId ||
            request.get("jsonrpc")?.takeIf { it.isJsonPrimitive }?.asString != "2.0" ||
            methodElement == null || !methodElement.isJsonPrimitive || !methodElement.asJsonPrimitive.isString
        ) {
            return errorResponse(id, INVALID_REQUEST, "Invalid JSON-RPC request")
        }
        val method = methodElement.asString
        val params = request.get("params")?.let { value ->
            if (!value.isJsonObject) {
                return if (hasId) errorResponse(id, INVALID_PARAMS, "params must be an object") else null
            }
            value.asJsonObject
        } ?: JsonObject()

        if (!hasId) {
            when (method) {
                "notifications/initialized" -> if (initializeSeen) initialized = true
                "notifications/cancelled", "notifications/roots/list_changed" -> Unit
                else -> Unit // Unknown notifications never receive a response.
            }
            return null
        }

        return try {
            when (method) {
                "initialize" -> successResponse(id, initialize(params))
                "ping" -> successResponse(id, JsonObject())
                else -> {
                    if (!initialized) {
                        errorResponse(id, NOT_INITIALIZED, "Server has not received notifications/initialized")
                    } else {
                        dispatchInitialized(id, method, params)
                    }
                }
            }
        } catch (error: UnknownToolException) {
            errorResponse(id, INVALID_PARAMS, error.message ?: "Unknown tool")
        } catch (error: UnknownPromptException) {
            errorResponse(id, INVALID_PARAMS, error.message ?: "Unknown prompt")
        } catch (error: UserInputException) {
            errorResponse(id, INVALID_PARAMS, error.message ?: "Invalid parameters")
        } catch (error: Exception) {
            System.err.println("jetpacs-platform-tools: internal request failure for $method: ${error.message}")
            errorResponse(id, INTERNAL_ERROR, "Internal error")
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = params.requiredString("protocolVersion")
        if (params.get("capabilities")?.isJsonObject != true) {
            throw UserInputException("initialize capabilities must be an object")
        }
        val clientInfo = params.get("clientInfo")
        if (clientInfo?.isJsonObject != true) {
            throw UserInputException("initialize clientInfo must be an object")
        }
        clientInfo.asJsonObject.requiredString("name")
        clientInfo.asJsonObject.requiredString("version")
        negotiatedVersion = requested.takeIf { it in SUPPORTED_PROTOCOL_VERSIONS } ?: LATEST_PROTOCOL_VERSION
        initialized = false
        initializeSeen = true
        return JsonObject().apply {
            addProperty("protocolVersion", negotiatedVersion)
            add("capabilities", JsonObject().apply {
                add("tools", JsonObject().apply { addProperty("listChanged", false) })
                add("resources", JsonObject().apply {
                    addProperty("subscribe", false)
                    addProperty("listChanged", false)
                })
                add("prompts", JsonObject().apply { addProperty("listChanged", false) })
            })
            add("serverInfo", JsonObject().apply {
                addProperty("name", "jetpacs-platform-tools")
                addProperty("title", "Jetpacs Platform Tools")
                addProperty("version", version)
            })
            addProperty(
                "instructions",
                "Read-only Jetpacs platform workbench. Read jetpacs://project/implementation-guide first, then use jetpacs_project_overview and focused source or contract queries. Use jetpacs-applet-mcp for applet authoring.",
            )
        }
    }

    private fun dispatchInitialized(
        id: JsonElement,
        method: String,
        params: JsonObject,
    ): JsonObject = when (method) {
        "tools/list" -> successResponse(id, JsonObject().apply { add("tools", workbench.toolDefinitions()) })
        "tools/call" -> successResponse(id, callTool(params))
        "resources/list" -> successResponse(id, listResources())
        "resources/read" -> successResponse(id, readResource(params))
        "prompts/list" -> successResponse(
            id,
            JsonObject().apply { add("prompts", workbench.promptDefinitions()) },
        )
        "prompts/get" -> successResponse(
            id,
            workbench.getPrompt(
                params.requiredString("name"),
                params.get("arguments")?.let {
                    if (!it.isJsonObject) throw UserInputException("prompt arguments must be an object")
                    it.asJsonObject
                } ?: JsonObject(),
            ),
        )
        else -> errorResponse(id, METHOD_NOT_FOUND, "Method not found: $method")
    }

    private fun callTool(params: JsonObject): JsonObject {
        val name = params.requiredString("name")
        val arguments = params.get("arguments")?.let {
            if (!it.isJsonObject) throw UserInputException("tool arguments must be an object")
            it.asJsonObject
        } ?: JsonObject()
        val toolResult = workbench.callTool(name, arguments)
        return JsonObject().apply {
            add("content", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", toolResult.text.capped())
                })
            })
            toolResult.structured?.let { add("structuredContent", it) }
            if (toolResult.isError) addProperty("isError", true)
        }
    }

    private fun listResources(): JsonObject = JsonObject().apply {
        add("resources", JsonArray().apply {
            workbench.resources().forEach { resource ->
                add(JsonObject().apply {
                    addProperty("uri", resource.uri)
                    addProperty("name", resource.name)
                    addProperty("title", resource.title)
                    addProperty("description", resource.description)
                    addProperty("mimeType", "text/markdown")
                })
            }
        })
    }

    private fun readResource(params: JsonObject): JsonObject {
        val uri = params.requiredString("uri")
        val resource = workbench.resources().firstOrNull { it.uri == uri }
            ?: throw UserInputException("unknown resource URI: $uri")
        return JsonObject().apply {
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("uri", resource.uri)
                    addProperty("mimeType", "text/markdown")
                    addProperty("text", resource.text().capped())
                })
            })
        }
    }

    private fun successResponse(id: JsonElement, result: JsonObject): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id.deepCopy())
        add("result", result)
    }

    private fun errorResponse(id: JsonElement, code: Int, message: String): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id.deepCopy())
        add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        })
    }

    companion object {
        private const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024
        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602
        private const val INTERNAL_ERROR = -32603
        private const val NOT_INITIALIZED = -32002

        private const val LATEST_PROTOCOL_VERSION = "2025-11-25"
        private val SUPPORTED_PROTOCOL_VERSIONS = setOf(
            LATEST_PROTOCOL_VERSION,
            "2025-06-18",
            "2025-03-26",
            "2024-11-05",
        )
    }
}
