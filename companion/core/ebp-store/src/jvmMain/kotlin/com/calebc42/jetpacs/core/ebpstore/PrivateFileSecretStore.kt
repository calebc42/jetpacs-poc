// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.core.ebpstore

import com.calebc42.jetpacs.core.database.ensurePrivateDirectory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SPEC §9.1's floor: a JSON object of account to value in a file only the
 * user can read, inside a private directory ([ensurePrivateDirectory]).
 * Every write goes to a temporary file that is synced and then moved over
 * the live one atomically, ebp-java's pairing-file discipline, so a crash
 * mid-write leaves the previous contents. Refused on a filesystem with
 * neither POSIX permissions nor Windows ACLs: there is no private place.
 */
class PrivateFileSecretStore(private val file: Path) : SecretStore {
    private val lock = Any()
    private val posix: Boolean

    init {
        val views = file.fileSystem.supportedFileAttributeViews()
        posix = "posix" in views
        check(posix || "acl" in views) {
            "no private storage: the filesystem has neither POSIX permissions nor ACLs"
        }
        ensurePrivateDirectory(file.toAbsolutePath().parent)
        check(!Files.isSymbolicLink(file)) { "$file is a symbolic link" }
    }

    override val protection: CredentialProtection =
        CredentialProtection.PrivateFile(file.toAbsolutePath().toString())

    override fun get(account: String): String? = synchronized(lock) { load()[account] }

    override fun set(account: String, value: String) = synchronized(lock) {
        save(load().apply { put(account, value) })
    }

    override fun delete(account: String) = synchronized(lock) {
        val values = load()
        if (values.remove(account) != null) save(values)
    }

    private fun load(): MutableMap<String, String> {
        if (Files.notExists(file)) return linkedMapOf()
        return Json.parseToJsonElement(Files.readString(file)).jsonObject
            .mapValuesTo(linkedMapOf()) { (_, value) -> value.jsonPrimitive.content }
    }

    private fun save(values: Map<String, String>) {
        val temporary = file.resolveSibling(file.fileName.toString() + TEMPORARY_SUFFIX)
        Files.deleteIfExists(temporary)
        val attributes = if (posix) {
            arrayOf(PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE))
        } else {
            emptyArray()
        }
        val encoded = Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(values.mapValues { (_, value) -> JsonPrimitive(value) }),
        ).encodeToByteArray()
        FileChannel.open(
            temporary,
            setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
            *attributes,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(encoded)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        Files.move(
            temporary,
            file,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        syncDirectory(file.toAbsolutePath().parent)
    }

    private fun syncDirectory(directory: Path) {
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // Windows cannot open a directory as a channel; the move itself is
            // atomic there and the data file was already forced.
        }
    }

    companion object {
        const val FILE_NAME = "credentials.json"
        private const val TEMPORARY_SUFFIX = ".tmp"
        private val OWNER_READ_WRITE = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }
}
