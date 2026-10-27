// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineVaultTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun mergeCopyFillsGapsAndNeverOverwrites() {
        val from = tmp.newFolder("from"); val to = tmp.newFolder("to")
        File(from, "org").mkdirs()
        File(from, "org/inbox.org").writeText("* new\n")
        File(from, "org/notes.org").writeText("* theirs\n")
        File(from, "org/leftover.org.jetpacs-new").writeText("staging")
        File(to, "org").mkdirs()
        File(to, "org/notes.org").writeText("* mine\n")
        assertEquals(1, EngineVault.mergeCopy(from, to))
        assertEquals("* new\n", File(to, "org/inbox.org").readText())
        assertEquals("* mine\n", File(to, "org/notes.org").readText())
        assertTrue(!File(to, "org/leftover.org.jetpacs-new").exists())
        assertTrue(!File(to, "org/leftover.org").exists())
        // Running it again copies nothing more.
        assertEquals(0, EngineVault.mergeCopy(from, to))
    }

    @Test
    fun zipVaultHoldsEveryRegularFileByRelativePath() {
        val vault = tmp.newFolder("vault")
        File(vault, "org/a").mkdirs()
        File(vault, "org/inbox.org").writeText("* one\n")
        File(vault, "org/a/deep.org").writeText("* two\n")
        File(vault, "org/skip.jetpacs-new").writeText("x")
        val bytes = ByteArrayOutputStream()
        assertEquals(2, EngineExport.zipVault(vault, bytes))
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { names += it.name }
        }
        assertEquals(setOf("org/inbox.org", "org/a/deep.org"), names.toSet())
    }

    /**
     * On Android the private vault is reached through /data/user/0, a link
     * to /data/data, so every path under it canonicalizes elsewhere. The
     * export and the move must still see the whole vault (found on the Pixel
     * Tablet: a 22-byte empty export), while a link inside the vault is still
     * never followed.
     */
    @Test
    fun aVaultReachedThroughALinkedAncestorIsExportedAndCopiedWhole() {
        val real = tmp.newFolder("data")
        File(real, "vault/org").mkdirs()
        File(real, "vault/org/inbox.org").writeText("* one\n")
        val outside = tmp.newFolder("outside")
        File(outside, "secret.org").writeText("* not in the vault\n")
        Files.createSymbolicLink(File(real, "vault/org/linked").toPath(), outside.toPath())
        Files.createSymbolicLink(File(real, "vault/org/linked.org").toPath(), File(outside, "secret.org").toPath())
        val user0 = File(tmp.root, "user0")
        Files.createSymbolicLink(user0.toPath(), real.toPath())
        val vault = File(user0, "vault")

        val bytes = ByteArrayOutputStream()
        assertEquals(1, EngineExport.zipVault(vault, bytes))
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { names += it.name }
        }
        assertEquals(listOf("org/inbox.org"), names)

        val to = tmp.newFolder("shared")
        assertEquals(1, EngineVault.mergeCopy(vault, to))
        assertEquals("* one\n", File(to, "org/inbox.org").readText())
        assertTrue(!File(to, "org/linked").exists())
        assertTrue(!File(to, "org/linked.org").exists())
    }
}
