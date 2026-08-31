package com.jetpacs.platform.tools

import com.google.gson.JsonParser
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpServerTest {
    @Test
    fun `every tool carries a closed schema and behavioral documentation`() {
        val root = createTempDirectory("jetpacs-platform-tools-tool-docs")
        val tools = JetpacsWorkbench(Workspace.open(root)).toolDefinitions()

        assertTrue(tools.size() >= 7)
        tools.forEach { element ->
            val tool = element.asJsonObject
            assertTrue(tool.get("name").asString.startsWith("jetpacs_"))
            assertTrue(tool.get("title").asString.isNotBlank())
            assertTrue(tool.get("description").asString.length >= 40)
            assertFalse(tool.getAsJsonObject("inputSchema").get("additionalProperties").asBoolean)
            val annotations = tool.getAsJsonObject("annotations")
            assertTrue(annotations.get("readOnlyHint").asBoolean)
            assertFalse(annotations.get("destructiveHint").asBoolean)
            assertTrue(annotations.get("idempotentHint").asBoolean)
            assertFalse(annotations.get("openWorldHint").asBoolean)
        }

        Files.deleteIfExists(root)
    }

    @Test
    fun `server negotiates lifecycle and exposes static capabilities`() {
        val root = createTempDirectory("jetpacs-platform-tools-mcp-test")
        root.resolve("README.org").toFile().writeText("* Test")
        val server = McpServer(JetpacsWorkbench(Workspace.open(root)), "test")

        val initialize = server.handleLine(
            """{"jsonrpc":"2.0","id":"init-1","method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""",
        )!!
        assertEquals("init-1", initialize.get("id").asString)
        val result = initialize.getAsJsonObject("result")
        assertEquals(
            "2025-11-25",
            result.get("protocolVersion").asString,
        )
        val serverInfo = result.getAsJsonObject("serverInfo")
        assertEquals("jetpacs-platform-tools", serverInfo.get("name").asString)
        assertEquals("Jetpacs Platform Tools", serverInfo.get("title").asString)
        assertTrue(result.get("instructions").asString.contains("jetpacs://project/implementation-guide"))

        assertEquals(
            -32002,
            server.handleLine("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")!!
                .getAsJsonObject("error").get("code").asInt,
        )
        assertEquals(
            null,
            server.handleLine("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""),
        )
        val listed = server.handleLine("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""")!!
        val tools = listed.getAsJsonObject("result").getAsJsonArray("tools")
        assertTrue(tools.size() >= 6)
        assertTrue(tools.all { it.asJsonObject.get("name").asString.startsWith("jetpacs_") })

        Files.deleteIfExists(root.resolve("README.org"))
        Files.deleteIfExists(root)
    }

    @Test
    fun `implementation resource returns root then local agent contracts`() {
        val root = createTempDirectory("jetpacs-platform-tools-guide")
        val local = Files.createDirectories(root.resolve("jetpacs-platform-tools"))
        Files.writeString(root.resolve("AGENTS.md"), "# Root contract\n\nROOT-INVARIANT")
        Files.writeString(local.resolve("AGENTS.md"), "# Local contract\n\nLOCAL-RECIPE")
        val server = McpServer(JetpacsWorkbench(Workspace.open(root)), "test")

        server.handleLine(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""",
        )
        server.handleLine("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")

        val listed = server.handleLine("""{"jsonrpc":"2.0","id":2,"method":"resources/list"}""")!!
        val resources = listed.getAsJsonObject("result").getAsJsonArray("resources")
        assertTrue(resources.any {
            it.asJsonObject.get("uri").asString == "jetpacs://project/implementation-guide"
        })

        val read = server.handleLine(
            """{"jsonrpc":"2.0","id":3,"method":"resources/read","params":{"uri":"jetpacs://project/implementation-guide"}}""",
        )!!
        val text = read.getAsJsonObject("result").getAsJsonArray("contents")
            .first().asJsonObject.get("text").asString
        assertTrue(text.indexOf("ROOT-INVARIANT") < text.indexOf("LOCAL-RECIPE"))

        Files.deleteIfExists(local.resolve("AGENTS.md"))
        Files.deleteIfExists(root.resolve("AGENTS.md"))
        Files.deleteIfExists(local)
        Files.deleteIfExists(root)
    }

    @Test
    fun `parse failures are JSON-RPC errors and notifications stay silent`() {
        val root = createTempDirectory("jetpacs-platform-tools-mcp-errors")
        val server = McpServer(JetpacsWorkbench(Workspace.open(root)), "test")

        val parseError = server.handleLine("{")!!
        assertEquals(-32700, parseError.getAsJsonObject("error").get("code").asInt)
        assertTrue(parseError.get("id").isJsonNull)
        assertFalse(
            JsonParser.parseString(parseError.toString()).asJsonObject.has("result"),
        )
        assertEquals(
            null,
            server.handleLine("""{"jsonrpc":"2.0","method":"unknown/notification"}"""),
        )

        Files.deleteIfExists(root)
    }

    @Test
    fun `initialized notification cannot bypass initialize`() {
        val root = createTempDirectory("jetpacs-platform-tools-mcp-lifecycle")
        val server = McpServer(JetpacsWorkbench(Workspace.open(root)), "test")

        server.handleLine("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        val response = server.handleLine("""{"jsonrpc":"2.0","id":8,"method":"tools/list"}""")!!

        assertEquals(-32002, response.getAsJsonObject("error").get("code").asInt)
        Files.deleteIfExists(root)
    }
}
