// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.core.database

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The desktop builder (D4 of docs/PLAN-desktop-companion.md). */
class JetpacsDatabaseBuilderTest {
    private val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

    @Test
    fun opensInsideTheDirectoryAndRowsSurviveAReopen() = runTest {
        val root = Files.createTempDirectory("jetpacs-builder-")
        try {
            val home = root.resolve("data")
            buildJetpacsDatabase(home).let { database ->
                database.appRuntimeDao().upsert(AppRuntimeEntity(backgroundBridgeEnabled = true))
                database.close()
            }
            assertTrue(Files.isRegularFile(home.resolve(JETPACS_DATABASE_NAME)))
            buildJetpacsDatabase(home).let { database ->
                assertEquals(true, database.appRuntimeDao().get()?.backgroundBridgeEnabled)
                database.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun theDirectoryBecomesOwnerOnly() {
        if (!posix) return
        val root = Files.createTempDirectory("jetpacs-private-")
        try {
            val created = ensurePrivateDirectory(root.resolve("fresh"))
            assertEquals(ownerOnly, Files.getPosixFilePermissions(created))
            // A pre-existing, too-open directory is tightened, not refused.
            val open = Files.createDirectory(root.resolve("open"))
            Files.setPosixFilePermissions(open, PosixFilePermissions.fromString("rwxr-xr-x"))
            ensurePrivateDirectory(open)
            assertEquals(ownerOnly, Files.getPosixFilePermissions(open))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun aSymbolicLinkOrAFileIsRefused() {
        if (!posix) return
        val root = Files.createTempDirectory("jetpacs-private-")
        try {
            val target = Files.createDirectory(root.resolve("target"))
            val link = Files.createSymbolicLink(root.resolve("link"), target)
            assertFailsWith<IllegalStateException> { ensurePrivateDirectory(link) }
            val file = Files.createFile(root.resolve("file"))
            assertFailsWith<IllegalStateException> { ensurePrivateDirectory(file) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private val ownerOnly = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )
}
