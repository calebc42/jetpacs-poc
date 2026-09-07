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
    fun `consolidated modules ignore stale siblings and reject escapes`() {
        val parent = createTempDirectory("jetpacs-consolidated-modules")
        try {
            val root = parent.resolve("jetpacs-poc").createDirectory()
            for (name in listOf("jetpacs-components", "jetpacs-automations", "jetpacs-component-catalog")) {
                val local = root.resolve(name).createDirectory()
                local.resolve("live.el").writeText("(provide 'live)")
                val stale = parent.resolve(name).createDirectory()
                stale.resolve("stale.el").writeText("(provide 'stale)")
                Files.createSymbolicLink(local.resolve("escape.el"), stale.resolve("stale.el"))
            }
            val workspace = Workspace.open(root, explicitRepositoriesRoot = parent)
            val paths = workspace.sourceFiles(setOf("el")).map(workspace::relative).toList()
            assertEquals(listOf("jetpacs-automations/live.el", "jetpacs-component-catalog/live.el", "jetpacs-components/live.el"), paths)
            for (name in listOf("jetpacs-components", "jetpacs-automations", "jetpacs-component-catalog")) {
                assertEquals("$name/live.el", workspace.relative(workspace.resolveFile("$name/live.el")))
                assertFailsWith<UserInputException> { workspace.resolveFile("$name/stale.el") }
                assertFailsWith<UserInputException> { workspace.resolveFile("$name/escape.el") }
            }
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `workbench uses flattened root and named sibling repositories`() {
        val root = createTempDirectory("jetpacs-platform-tools-canonical-layout")
        val repositories = Files.createDirectories(root.parent.resolve("repositories-${root.fileName}"))
        val specification = Files.createDirectories(repositories.resolve("ebp"))
        specification.resolve("SPEC.md").writeText("# EBP")
        specification.resolve("contract.json").writeText(
            """{"protocol_version":3,"spec_version":"test","contract_format":1,"reference_api_version":"test"}""",
        )
        Files.createDirectories(repositories.resolve("ebp.el/lisp"))
            .resolve("ebp.el")
            .writeText("(provide 'ebp)")
        Files.createDirectories(root.resolve("emacs"))
            .resolve("jetpacs-surfaces.el")
            .writeText("(provide 'jetpacs-surfaces)")
        Files.createDirectories(root.resolve("companion"))
            .resolve("settings.gradle.kts")
            .writeText("include(\":canonical\")")

        // Old nested candidates are deliberately ignored after flattening.
        Files.createDirectories(root.resolve("jetpacs/emacs"))
            .resolve("jetpacs-surfaces.el")
            .writeText("(provide 'legacy-surfaces)")
        Files.createDirectories(root.resolve("ebp-poc/ebp"))
            .resolve("SPEC.md")
            .writeText("# legacy")
        Files.createDirectories(root.resolve("jetpacs/companion"))
            .resolve("settings.gradle.kts")
            .writeText("include(\":legacy\")")

        val workbench = JetpacsWorkbench(Workspace.open(root, explicitRepositoriesRoot = repositories))
        val doctor = workbench.doctor()
        val overview = workbench.projectOverview()

        assertContains(doctor, "emacs/jetpacs-surfaces.el")
        assertContains(doctor, "companion/settings.gradle.kts")
        assertContains(doctor, "ebp/contract.json")
        assertContains(overview, ":canonical")
        assertFalse(overview.contains(":legacy"))

        root.toFile().deleteRecursively()
        repositories.toFile().deleteRecursively()
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
    fun `named sibling namespace rejects nested symlink escape`() {
        val parent = createTempDirectory("jetpacs-platform-tools-sibling-boundary")
        val root = parent.resolve("workspace").createDirectory()
        val repositories = parent.resolve("repositories").createDirectory()
        val ebp = repositories.resolve("ebp").createDirectory()
        val outside = parent.resolve("outside.kt")
        ebp.resolve("inside.kt").writeText("class Inside")
        outside.writeText("class Outside")
        Files.createSymbolicLink(ebp.resolve("escape.kt"), outside)
        val workspace = Workspace.open(root, explicitRepositoriesRoot = repositories)

        assertEquals("ebp/inside.kt", workspace.relative(workspace.resolveFile("ebp/inside.kt")))
        assertFailsWith<UserInputException> { workspace.resolveFile("ebp/escape.kt") }
        assertFailsWith<UserInputException> { workspace.resolveFile("ebp/../outside.kt") }

        Files.deleteIfExists(ebp.resolve("inside.kt"))
        Files.deleteIfExists(ebp.resolve("escape.kt"))
        Files.deleteIfExists(outside)
        Files.deleteIfExists(ebp)
        Files.deleteIfExists(repositories)
        Files.deleteIfExists(root)
        Files.deleteIfExists(parent)
    }

    @Test
    fun `named repository symlink root must remain a direct sibling`() {
        val parent = createTempDirectory("jetpacs-platform-tools-sibling-root")
        val root = parent.resolve("workspace").createDirectory()
        val repositories = parent.resolve("repositories").createDirectory()
        val outside = parent.resolve("outside-repository").createDirectory()
        Files.createSymbolicLink(repositories.resolve("ebp"), outside)

        assertFailsWith<UserInputException> {
            Workspace.open(root, explicitRepositoriesRoot = repositories)
        }

        Files.deleteIfExists(repositories.resolve("ebp"))
        Files.deleteIfExists(outside)
        Files.deleteIfExists(repositories)
        Files.deleteIfExists(root)
        Files.deleteIfExists(parent)
    }

    @Test
    fun `default repository discovery checks only workspace parent and grandparent`() {
        val parent = createTempDirectory("jetpacs-platform-tools-discovery")
        val root = Files.createDirectories(parent.resolve("jetpacs/jetpacs-poc"))
        val repositories = parent.resolve("ebp").createDirectory()
        repositories.resolve("contract.json").writeText("{}")
        val workspace = Workspace.open(root)

        assertEquals(parent, workspace.repositoriesRoot)
        assertEquals("ebp/contract.json", workspace.relative(workspace.resolveFile("ebp/contract.json")))

        parent.toFile().deleteRecursively()
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
