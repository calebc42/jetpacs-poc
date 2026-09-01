package com.calebc42.jetpacs.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "pairing_themes",
    foreignKeys = [ForeignKey(
        entity = PairingPartitionEntity::class,
        parentColumns = ["pairing_id"],
        childColumns = ["pairing_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class PairingThemeEntity(
    @PrimaryKey @ColumnInfo(name = "pairing_id") val pairingId: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "accepted_at_epoch_ms") val acceptedAtEpochMs: Long,
)

@Entity(
    tableName = "platform_effects",
    primaryKeys = ["pairing_id", "effect_id"],
    indices = [
        Index("pairing_id", "state", "created_at_epoch_ms"),
        Index("pairing_id", "dedupe_key", unique = true),
    ],
)
data class PlatformEffectEntity(
    @ColumnInfo(name = "pairing_id") val pairingId: String,
    @ColumnInfo(name = "effect_id") val effectId: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "dedupe_key") val dedupeKey: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "attempt_count") val attemptCount: Long,
    @ColumnInfo(name = "claimed_at_epoch_ms") val claimedAtEpochMs: Long?,
    @ColumnInfo(name = "completed_at_epoch_ms") val completedAtEpochMs: Long?,
)
