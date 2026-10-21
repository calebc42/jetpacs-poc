// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.core.database

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

private val OWNER_ONLY = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.OWNER_EXECUTE,
)

/**
 * Make [directory] exist as a directory only its owner can enter: the
 * discipline ebp-java's host tier applies to its socket and pairing
 * directories. A symbolic link or a non-directory is refused; on a POSIX
 * filesystem the leaf is created, or tightened, to `rwx------` and must be
 * owned by the running user. On a filesystem without POSIX permissions the
 * application's own directory is the floor SPEC §9.1 allows.
 */
fun ensurePrivateDirectory(directory: Path): Path {
    check(!Files.isSymbolicLink(directory)) { "$directory is a symbolic link" }
    val posix = "posix" in directory.fileSystem.supportedFileAttributeViews()
    if (Files.notExists(directory)) {
        directory.parent?.let { Files.createDirectories(it) }
        if (posix) {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        } else {
            Files.createDirectory(directory)
        }
    }
    check(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "$directory is not a directory" }
    if (posix) {
        val user = System.getProperty("user.name")
        val owner = Files.getOwner(directory).name
        check(owner == user) { "$directory is owned by $owner, not $user" }
        if (Files.getPosixFilePermissions(directory) != OWNER_ONLY) {
            Files.setPosixFilePermissions(directory, OWNER_ONLY)
        }
    }
    return directory
}
