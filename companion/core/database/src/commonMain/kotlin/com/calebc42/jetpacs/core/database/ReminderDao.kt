package com.calebc42.jetpacs.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE pairing_id = :pairingId ORDER BY owner, authored_ordinal")
    suspend fun getReminders(pairingId: String): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE pairing_id = :pairingId AND owner = :owner ORDER BY authored_ordinal")
    suspend fun getReminders(pairingId: String, owner: String): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE pairing_id = :pairingId ORDER BY owner, authored_ordinal")
    fun observeReminders(pairingId: String): Flow<List<ReminderEntity>>

    @Upsert suspend fun upsertReminder(reminder: ReminderEntity)
    @Upsert suspend fun upsertReminders(reminders: List<ReminderEntity>)

    @Query("DELETE FROM reminders WHERE pairing_id = :pairingId AND owner = :owner AND reminder_id = :reminderId")
    suspend fun deleteReminder(pairingId: String, owner: String, reminderId: String): Int

    @Query("DELETE FROM reminders WHERE pairing_id = :pairingId AND (:owner IS NULL OR owner = :owner)")
    suspend fun deleteReminders(pairingId: String, owner: String?): Int

    @Query("SELECT * FROM reminder_receipts WHERE pairing_id = :pairingId AND (:owner IS NULL OR owner = :owner) ORDER BY owner, reminder_id, at_epoch_ms")
    suspend fun getReceipts(pairingId: String, owner: String?): List<ReminderReceiptEntity>

    @Insert suspend fun insertReceipt(receipt: ReminderReceiptEntity)

    @Query("DELETE FROM reminder_receipts WHERE pairing_id = :pairingId AND owner = :owner AND reminder_id = :reminderId AND at_epoch_ms = :atEpochMs")
    suspend fun deleteReceipt(pairingId: String, owner: String, reminderId: String, atEpochMs: Long): Int

    @Query("DELETE FROM reminder_receipts WHERE pairing_id = :pairingId AND (:owner IS NULL OR owner = :owner)")
    suspend fun deleteReceipts(pairingId: String, owner: String?): Int
}
