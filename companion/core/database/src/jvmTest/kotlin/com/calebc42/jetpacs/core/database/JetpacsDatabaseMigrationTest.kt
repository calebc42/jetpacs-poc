// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.core.database

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * A database exported at schema version 1 migrates through both
 * auto-migrations to the current version, validates against the exported
 * current schema, and then opens through the desktop builder with its rows
 * intact. The module's first migration test (D4 of
 * docs/PLAN-desktop-companion.md).
 */
class JetpacsDatabaseMigrationTest {
    @Test
    fun aVersionOneDatabaseMigratesToTheCurrentSchemaAndOpens() = runTest {
        val root = Files.createTempDirectory("jetpacs-migration-")
        try {
            val home = ensurePrivateDirectory(root.resolve("data"))
            val databasePath = home.resolve(JETPACS_DATABASE_NAME)
            val helper = MigrationTestHelper(
                schemaDirectoryPath = Paths.get("schemas").toAbsolutePath(),
                databasePath = databasePath,
                driver = BundledSQLiteDriver(),
                databaseClass = JetpacsDatabase::class,
                databaseFactory = JetpacsDatabaseConstructor::initialize,
            )
            helper.createDatabase(1).use { connection ->
                connection.execSQL(
                    "INSERT INTO app_runtime (singleton_id, background_bridge_enabled) VALUES (0, 1)")
            }
            helper.runMigrationsAndValidate(3, emptyList()).close()
            buildJetpacsDatabase(home).let { database ->
                assertEquals(true, database.appRuntimeDao().get()?.backgroundBridgeEnabled)
                database.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
