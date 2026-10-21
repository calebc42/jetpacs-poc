// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.core.database

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers

/**
 * The desktop's Room database: `jetpacs.db` inside [directory], which must be
 * private to the user (see [ensurePrivateDirectory]); the host passes
 * [JetpacsDataDirectory.resolve] or a directory of its own. The same bundled
 * driver and query context as the Android builder (D4 of
 * docs/PLAN-desktop-companion.md).
 */
fun buildJetpacsDatabase(directory: Path): JetpacsDatabase {
    val home = ensurePrivateDirectory(directory)
    return Room.databaseBuilder<JetpacsDatabase>(
        name = home.resolve(JETPACS_DATABASE_NAME).toAbsolutePath().toString(),
        factory = JetpacsDatabaseConstructor::initialize,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
