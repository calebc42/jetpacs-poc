// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.nio.file.Files

/**
 * Where the user's notes live (docs/PLAN-bundled-emacs.md, decision 2):
 * app-private by default, or the shared `/sdcard/Jetpacs` once the user
 * grants All files access and asks for it. The choice is a file in the
 * engine's HOME, because the Companion process decides and the engine
 * process's provisioner reads it; `install.conf` follows it, so the
 * composition root's `jetpacs-vault-directory` does too.
 */
internal object EngineVault {
    enum class Mode { PRIVATE, SHARED }

    private const val MODE_FILE = "vault.mode"

    /** The private vault beside HOME. */
    fun privateDir(home: EngineHome): File = home.vault

    /** The shared vault on external storage, readable by any app with All files access. */
    fun sharedDir(): File = File(Environment.getExternalStorageDirectory(), "Jetpacs")

    fun mode(home: EngineHome): Mode =
        File(home.root, MODE_FILE).takeIf { it.isFile }?.readText()?.trim()
            ?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.PRIVATE

    fun setMode(home: EngineHome, mode: Mode) {
        File(home.root, MODE_FILE).writeText(mode.name + "\n")
    }

    /** The vault the engine should use under the recorded mode. */
    fun current(home: EngineHome): File = when (mode(home)) {
        Mode.PRIVATE -> privateDir(home)
        Mode.SHARED -> sharedDir()
    }

    /** The `install.conf` label for the mode. */
    fun label(home: EngineHome): String = when (mode(home)) {
        Mode.PRIVATE -> "private"
        Mode.SHARED -> "shared"
    }

    /** Whether this app may use the shared folder (Android's All files access). */
    fun sharedAccessGranted(): Boolean = Environment.isExternalStorageManager()

    /** The system page where the user grants All files access to this app. */
    fun sharedAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    /**
     * Copy every regular file under [from] into [to] that [to] does not
     * already have, never overwriting and never following symbolic links;
     * returns the number of files copied. A move between vaults is this copy
     * plus the mode switch: nothing is deleted from the old place, so a
     * mistaken switch costs nothing.
     */
    fun mergeCopy(from: File, to: File): Int {
        if (!from.isDirectory) return 0
        var copied = 0
        from.walkTopDown()
            .onEnter { dir -> !isSymlink(dir) }
            .filter { it.isFile && !isSymlink(it) && !it.name.endsWith(".jetpacs-new") }
            .forEach { source ->
                val target = File(to, source.relativeTo(from).path)
                if (target.exists()) return@forEach
                target.parentFile?.mkdirs()
                val staged = File(target.parentFile, target.name + ".jetpacs-new")
                source.inputStream().use { input -> staged.outputStream().use { input.copyTo(it) } }
                if (staged.renameTo(target)) copied++ else staged.delete()
            }
        return copied
    }

    /**
     * Whether this entry itself is a symbolic link. Not a canonical-path
     * comparison: on Android the app's files are reached through
     * /data/user/0, a link to /data/data, so every path under the private
     * vault canonicalizes elsewhere and such a test would skip the whole
     * vault (found on the Pixel Tablet: an empty export).
     */
    internal fun isSymlink(file: File): Boolean = Files.isSymbolicLink(file.toPath())
}
