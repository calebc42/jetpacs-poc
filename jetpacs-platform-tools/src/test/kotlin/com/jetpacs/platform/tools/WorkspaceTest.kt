package com.jetpacs.platform.tools

import java.nio.file.Files
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WorkspaceTest {
    @Test
    fun `workbench ignores legacy Jetpacs layout decoys`() {
        val root = createTempDirectory("jetpacs-platform-tools-canonical-layout")
        val specification = Files.createDirectories(root.resolve("ebp-poc/ebp"))
        specification.resolve("SPEC.md").writeText("# EBP")
        specification.resolve("contract.json").writeText(
            """{"protocol_version":3,"spec_version":"test","contract_format":1,"reference_api_version":"test"}""",
        )
        Files.createDirectories(root.resolve("ebp-poc/ebp.el/lisp"))
            .resolve("ebp.el")
            .writeText("(provide 'ebp)")
        Files.createDirectories(root.resolve("jetpacs/emacs"))
            .resolve("jetpacs-surfaces.el")
            .writeText("(provide 'jetpacs-surfaces)")
        Files.createDirectories(root.resolve("jetpacs/companion"))
            .resolve("settings.gradle.kts")
            .writeText("include(\":canonical\")")

        Files.createDirectories(root.resolve("emacs"))
            .resolve("jetpacs-surfaces.el")
            .writeText("(provide 'legacy-surfaces)")
        Files.createDirectories(root.resolve("companion"))
            .resolve("settings.gradle.kts")
            .writeText("include(\":legacy\")")

        val workbench = JetpacsWorkbench(Workspace.open(root))
        val doctor = workbench.doctor()
        val overview = workbench.projectOverview()

        assertContains(doctor, "jetpacs/emacs/jetpacs-surfaces.el")
        assertContains(doctor, "jetpacs/companion/settings.gradle.kts")
        assertContains(overview, ":canonical")
        assertFalse(overview.contains(":legacy"))

        root.toFile().deleteRecursively()
    }

    @Test
    fun `source discovery excludes stale worktrees and archives`() {
        val root = createTempDirectory("jetpacs-platform-tools-source-discovery")
        val claude = root.resolve(".claude").createDirectory()
        val archives = root.resolve("archives").createDirectory()
        val live = root.resolve("live.kt")
        val stale = claude.resolve("stale.kt")
        val archived = archives.resolve("archived.kt")
        live.writeText("class Live")
        stale.writeText("class Stale")
        archived.writeText("class Archived")

        val sources = Workspace.open(root)
            .sourceFiles(setOf("kt"))
            .map { root.relativize(it).toString() }
            .toList()

        assertEquals(listOf("live.kt"), sources)

        Files.deleteIfExists(live)
        Files.deleteIfExists(stale)
        Files.deleteIfExists(archived)
        Files.deleteIfExists(claude)
        Files.deleteIfExists(archives)
        Files.deleteIfExists(root)
    }

    @Test
    fun `workspace accepts descendants and rejects traversal`() {
        val parent = createTempDirectory("jetpacs-platform-tools-workspace-test")
        val root = parent.resolve("workspace").createDirectory()
        root.resolve("inside.kt").writeText("class Inside")
        parent.resolve("outside.kt").writeText("class Outside")
        Files.createSymbolicLink(root.resolve("escape.kt"), parent.resolve("outside.kt"))
        val workspace = Workspace.open(root)

        assertEquals("inside.kt", workspace.relative(workspace.resolveFile("inside.kt")))
        assertFailsWith<UserInputException> { workspace.resolveFile("../outside.kt") }
        assertFailsWith<UserInputException> { workspace.resolveFile("escape.kt") }

        Files.deleteIfExists(root.resolve("inside.kt"))
        Files.deleteIfExists(root.resolve("escape.kt"))
        Files.deleteIfExists(parent.resolve("outside.kt"))
        Files.deleteIfExists(root)
        Files.deleteIfExists(parent)
    }

    @Test
    fun `Org checkout is explicit namespaced and separately bounded`() {
        val parent = createTempDirectory("jetpacs-platform-tools-org-boundary")
        val root = parent.resolve("workspace").createDirectory()
        val orgRoot = parent.resolve("org-source").createDirectory()
        val orgFile = orgRoot.resolve("org.el")
        val outside = parent.resolve("outside.el")
        orgFile.writeText("(provide 'org)")
        outside.writeText("(provide 'outside)")
        Files.createSymbolicLink(orgRoot.resolve("escape.el"), outside)
        val workspace = Workspace.open(root, orgRoot)

        assertEquals("org/org.el", workspace.relative(workspace.resolveFile("org/org.el")))
        assertContains(
            JetpacsWorkbench(workspace).findSymbol("provide", "org", limit = 5),
            "org/org.el:1",
        )
        assertFailsWith<UserInputException> { workspace.resolveFile(orgFile.toString()) }
        assertFailsWith<UserInputException> { workspace.resolveFile("org/escape.el") }

        Files.deleteIfExists(orgRoot.resolve("escape.el"))
        Files.deleteIfExists(orgFile)
        Files.deleteIfExists(outside)
        Files.deleteIfExists(orgRoot)
        Files.deleteIfExists(root)
        Files.deleteIfExists(parent)
    }

    @Test
    fun `Org source cannot overlap the applet workspace`() {
        val root = createTempDirectory("jetpacs-platform-tools-org-overlap")
        val nestedOrg = root.resolve("org-source").createDirectory()

        assertFailsWith<UserInputException> { Workspace.open(root, nestedOrg) }

        Files.deleteIfExists(nestedOrg)
        Files.deleteIfExists(root)
    }
}
