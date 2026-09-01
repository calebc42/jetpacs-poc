package com.calebc42.jetpacs.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = "trigger_registrations",
    primaryKeys = ["pairing_id", "identity", "trigger_id"],
    foreignKeys = [
        ForeignKey(
            entity = PairingPartitionEntity::class,
            parentColumns = ["pairing_id"],
            childColumns = ["pairing_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pairing_id", "identity", "authored_ordinal")],
)
data class TriggerRegistrationEntity(
    @ColumnInfo(name = "pairing_id") val pairingId: String,
    @ColumnInfo(name = "identity") val identity: String,
    @ColumnInfo(name = "trigger_id") val triggerId: String,
    @ColumnInfo(name = "authored_ordinal") val authoredOrdinal: Long,
    @ColumnInfo(name = "entry_json") val entryJson: String,
    @ColumnInfo(name = "canonical_identity") val canonicalIdentity: String,
)

@Entity(
    tableName = "trigger_runtime",
    primaryKeys = ["pairing_id", "identity", "trigger_id"],
    foreignKeys = [
        ForeignKey(
            entity = TriggerRegistrationEntity::class,
            parentColumns = ["pairing_id", "identity", "trigger_id"],
            childColumns = ["pairing_id", "identity", "trigger_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pairing_id", "identity", "trigger_id")],
)
data class TriggerRuntimeEntity(
    @ColumnInfo(name = "pairing_id") val pairingId: String,
    @ColumnInfo(name = "identity") val identity: String,
    @ColumnInfo(name = "trigger_id") val triggerId: String,
    @ColumnInfo(name = "throttle_floor_epoch_ms") val throttleFloorEpochMs: Long?,
    @ColumnInfo(name = "one_shot_completed") val oneShotCompleted: Boolean,
    @ColumnInfo(name = "schedule_anchor_epoch_ms") val scheduleAnchorEpochMs: Long?,
    @ColumnInfo(name = "last_fire_floor_epoch_ms") val lastFireFloorEpochMs: Long?,
    @ColumnInfo(name = "boot_generation") val bootGeneration: String?,
)

data class TriggerWithRuntime(
    val pairingId: String,
    val identity: String,
    val triggerId: String,
    val authoredOrdinal: Long,
    val entryJson: String,
    val canonicalIdentity: String,
    val throttleFloorEpochMs: Long?,
    val oneShotCompleted: Boolean,
    val scheduleAnchorEpochMs: Long?,
    val lastFireFloorEpochMs: Long?,
    val bootGeneration: String?,
)
