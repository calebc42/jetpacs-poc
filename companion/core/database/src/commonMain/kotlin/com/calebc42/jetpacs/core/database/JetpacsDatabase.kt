package com.calebc42.jetpacs.core.database

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor

@Database(
    entities = [
        PairingPartitionEntity::class,
        PairingRuntimeEntity::class,
        PairingRevocationEntity::class,
        RevocationArtifactEntity::class,
        SurfaceRecordEntity::class,
        SurfaceDraftEntity::class,
        QueueEventEntity::class,
        IssuedEventIdEntity::class,
        ReminderEntity::class,
        ReminderReceiptEntity::class,
        TriggerRegistrationEntity::class,
        TriggerRuntimeEntity::class,
        PairingThemeEntity::class,
        PlatformEffectEntity::class,
        AppRuntimeEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(JetpacsDatabaseConstructor::class)
abstract class JetpacsDatabase : RoomDatabase() {
    abstract fun pairingDao(): PairingDao

    abstract fun revocationDao(): RevocationDao

    abstract fun surfaceDao(): SurfaceDao

    abstract fun queueEventDao(): QueueEventDao

    abstract fun issuedEventIdDao(): IssuedEventIdDao

    abstract fun reminderDao(): ReminderDao

    abstract fun triggerDao(): TriggerDao

    abstract fun themeAndEffectDao(): ThemeAndEffectDao

    abstract fun appRuntimeDao(): AppRuntimeDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object JetpacsDatabaseConstructor : RoomDatabaseConstructor<JetpacsDatabase> {
    override fun initialize(): JetpacsDatabase
}
