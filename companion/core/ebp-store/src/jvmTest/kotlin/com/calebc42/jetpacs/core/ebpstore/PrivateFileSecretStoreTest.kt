// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.core.ebpstore

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** SPEC §9.1's floor: the private credential file. */
class PrivateFileSecretStoreTest {
    private val root: Path = Files.createTempDirectory("jetpacs-secret-file-")
    private val file: Path = root.resolve("data").resolve(PrivateFileSecretStore.FILE_NAME)
    private val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

    @After
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun valuesRoundTripAndSurviveAReopen() {
        val store = PrivateFileSecretStore(file)
        assertNull(store.get("a"))
        store.set("a", "one")
        store.set("b", "two")
        store.delete("b")
        store.delete("never-there")

        val reopened = PrivateFileSecretStore(file)

        assertEquals("one", reopened.get("a"))
        assertNull(reopened.get("b"))
        assertEquals(CredentialProtection.PrivateFile(file.toAbsolutePath().toString()), reopened.protection)
        assertFalse(Files.exists(file.resolveSibling(file.fileName.toString() + ".tmp")))
    }

    @Test
    fun theFileAndItsDirectoryAreOwnerOnly() {
        if (!posix) return
        PrivateFileSecretStore(file).set("a", "one")

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.parent)))
    }

    @Test
    fun aStaleTemporaryFileNeverReplacesTheLiveFile() {
        PrivateFileSecretStore(file).set("a", "one")
        val temporary = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(temporary, "{ garbage from a crash mid-write")

        val reopened = PrivateFileSecretStore(file)

        assertEquals("one", reopened.get("a"))
        reopened.set("b", "two")
        assertEquals("one", reopened.get("a"))
        assertEquals("two", reopened.get("b"))
        assertFalse(Files.exists(temporary))
        assertEquals("one", PrivateFileSecretStore(file).get("a"))
    }

    @Test
    fun aSymbolicLinkIsRefused() {
        if (!posix) return
        val directory = Files.createDirectories(file.parent)
        val elsewhere = Files.createFile(root.resolve("elsewhere.json"))
        Files.writeString(elsewhere, "{}")
        Files.createSymbolicLink(directory.resolve(PrivateFileSecretStore.FILE_NAME), elsewhere)

        try {
            PrivateFileSecretStore(file)
            fail("a symbolic link in place of the credential file must be refused")
        } catch (_: IllegalStateException) {
            // Expected.
        }
        assertTrue(Files.isSymbolicLink(file))
    }
}
