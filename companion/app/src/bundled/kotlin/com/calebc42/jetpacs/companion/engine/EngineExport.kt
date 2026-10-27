// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Export notes" (docs/PLAN-bundled-emacs.md, decision 2): the vault as one
 * zip archive written wherever the user chose through the system's document
 * picker. Regular files only, relative paths, no symbolic links followed,
 * staging leftovers skipped; returns the number of entries written.
 */
internal object EngineExport {
    fun zipVault(vault: File, output: OutputStream): Int {
        var entries = 0
        ZipOutputStream(output.buffered()).use { zip ->
            if (vault.isDirectory) {
                vault.walkTopDown()
                    .onEnter { dir -> !EngineVault.isSymlink(dir) }
                    .filter { it.isFile && !EngineVault.isSymlink(it) && !it.name.endsWith(".jetpacs-new") }
                    .forEach { file ->
                        val name = file.relativeTo(vault).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        entries++
                    }
            }
        }
        return entries
    }
}
