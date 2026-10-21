// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.core.database

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where a desktop host keeps the Companion's durable state: the database, and
 * the credential file when no keychain is reachable (SPEC §9.1 reserves it to
 * the user's own account, outside anything browsable or shared). The rule is
 * pure path logic so a test can run it for each operating system.
 *
 * - `JETPACS_DATA_DIR` when set, for a developer or a test;
 * - Linux and other POSIX systems: `$XDG_DATA_HOME/jetpacs`, or
 *   `~/.local/share/jetpacs`;
 * - macOS: `~/Library/Application Support/Jetpacs`;
 * - Windows: `%LOCALAPPDATA%\Jetpacs`, or `~\AppData\Local\Jetpacs`.
 */
object JetpacsDataDirectory {
    const val OVERRIDE_VARIABLE = "JETPACS_DATA_DIR"

    fun resolve(
        osName: String = System.getProperty("os.name").orEmpty(),
        environment: (String) -> String? = System::getenv,
        userHome: String = System.getProperty("user.home"),
    ): Path {
        fun env(name: String): Path? = environment(name)?.takeIf { it.isNotBlank() }?.let(Paths::get)
        env(OVERRIDE_VARIABLE)?.let { return it }
        val os = osName.lowercase()
        return when {
            "mac" in os || "darwin" in os ->
                Paths.get(userHome, "Library", "Application Support", "Jetpacs")
            "win" in os ->
                (env("LOCALAPPDATA") ?: Paths.get(userHome, "AppData", "Local")).resolve("Jetpacs")
            else ->
                (env("XDG_DATA_HOME") ?: Paths.get(userHome, ".local", "share")).resolve("jetpacs")
        }
    }
}
