package com.calebc42.jetpacs.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = "reminders",
    primaryKeys = ["pairing_id", "owner", "reminder_id"],
    foreignKeys = [
        ForeignKey(
            entity = PairingPartitionEntity::class,
            parentColumns = ["pairing_id"],
            childColumns = ["pairing_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pairing_id", "owner", "authored_ordinal")],
)
data class ReminderEntity(
    @ColumnInfo(name = "pairing_id") val pairingId: String,
    @ColumnInfo(name = "owner") val owner: String,
    @ColumnInfo(name = "reminder_id") val reminderId: String,
    @ColumnInfo(name = "at_epoch_ms") val atEpochMs: Long,
    @ColumnInfo(name = "authored_ordinal") val authoredOrdinal: Long,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
) {
    init {
        require(pairingId.isNotBlank() && owner.isNotBlank() && reminderId.isNotBlank())
        require(atEpochMs >= 0 && authoredOrdinal >= 0)
        require(payloadJson.isNotBlank())
    }
}

@Entity(
    tableName = "reminder_receipts",
    primaryKeys = ["pairing_id", "owner", "reminder_id", "at_epoch_ms"],
    foreignKeys = [
        ForeignKey(
            entity = ReminderEntity::class,
            parentColumns = ["pairing_id", "owner", "reminder_id"],
            childColumns = ["pairing_id", "owner", "reminder_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pairing_id", "owner", "reminder_id")],
)
data class ReminderReceiptEntity(
    @ColumnInfo(name = "pairing_id") val pairingId: String,
    @ColumnInfo(name = "owner") val owner: String,
    @ColumnInfo(name = "reminder_id") val reminderId: String,
    @ColumnInfo(name = "at_epoch_ms") val atEpochMs: Long,
    @ColumnInfo(name = "fired_at_epoch_ms") val firedAtEpochMs: Long,
) {
    init {
        require(pairingId.isNotBlank() && owner.isNotBlank() && reminderId.isNotBlank())
        require(atEpochMs >= 0 && firedAtEpochMs >= 0)
    }
}
