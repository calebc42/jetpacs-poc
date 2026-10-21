// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.core.database

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class JetpacsDataDirectoryTest {
    private fun resolve(os: String, vararg env: Pair<String, String>) =
        JetpacsDataDirectory.resolve(os, { name -> env.toMap()[name] }, "/home/u")

    @Test
    fun theOverrideWinsEverywhere() {
        assertEquals(Paths.get("/tmp/jp"), resolve("Linux", "JETPACS_DATA_DIR" to "/tmp/jp", "XDG_DATA_HOME" to "/x"))
        assertEquals(Paths.get("/tmp/jp"), resolve("Mac OS X", "JETPACS_DATA_DIR" to "/tmp/jp"))
    }

    @Test
    fun linuxFollowsXdgWithTheDefaultFallback() {
        assertEquals(Paths.get("/x/jetpacs"), resolve("Linux", "XDG_DATA_HOME" to "/x"))
        assertEquals(Paths.get("/home/u/.local/share/jetpacs"), resolve("Linux"))
        assertEquals(Paths.get("/home/u/.local/share/jetpacs"), resolve("Linux", "XDG_DATA_HOME" to " "))
    }

    @Test
    fun macAndWindowsUseTheirApplicationDirectories() {
        assertEquals(Paths.get("/home/u/Library/Application Support/Jetpacs"), resolve("Mac OS X"))
        assertEquals(Paths.get("/home/u/AppData/Local/Jetpacs"), resolve("Windows 11"))
        assertEquals(Paths.get("C:/Users/u/AppData/Local/Jetpacs"), resolve("Windows 11", "LOCALAPPDATA" to "C:/Users/u/AppData/Local"))
    }
}
