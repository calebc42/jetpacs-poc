// SPDX-License-Identifier: GPL-3.0-or-later
// The shared crash-safe JSON writer, factored from the four File backings at RF-2c(H6.a).
package com.calebc42.ebp.wire

import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream

internal fun writeJsonAtomic(target: File, root: JsonObject) {
    val temp = File(target.parentFile, target.name + ".tmp")
    FileOutputStream(temp).use { out ->
        out.write(root.toString().toByteArray(Charsets.UTF_8))
        out.fd.sync()
    }
    if (!temp.renameTo(target)) {
        temp.delete()
        throw java.io.IOException("atomic replace failed for $target")
    }
    // Directory fsync for the rename's own durability (best-effort; the
    // JVM cannot demand it portably).
    runCatching {
        java.nio.channels.FileChannel.open(
            target.parentFile.toPath(),
            java.nio.file.StandardOpenOption.READ).use { it.force(true) }
    }
}
