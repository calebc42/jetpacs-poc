package com.calebc42.jetpacs.core.database

import androidx.room3.ConstructedBy
import androidx.room3.AutoMigration
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
        WidgetBindingEntity::class,
        WidgetActionTokenEntity::class,
    ],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
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

    abstract fun widgetDao(): WidgetDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object JetpacsDatabaseConstructor : RoomDatabaseConstructor<JetpacsDatabase> {
    override fun initialize(): JetpacsDatabase
}
