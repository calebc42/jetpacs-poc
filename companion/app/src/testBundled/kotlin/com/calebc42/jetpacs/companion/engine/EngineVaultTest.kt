// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
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
}
