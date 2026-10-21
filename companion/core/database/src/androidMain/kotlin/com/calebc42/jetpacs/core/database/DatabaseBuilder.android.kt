package com.calebc42.jetpacs.core.database

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

fun buildJetpacsDatabase(context: Context): JetpacsDatabase =
    Room.databaseBuilder<JetpacsDatabase>(
        context = context.applicationContext,
        name = JETPACS_DATABASE_NAME,
        factory = JetpacsDatabaseConstructor::initialize,
    )
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
