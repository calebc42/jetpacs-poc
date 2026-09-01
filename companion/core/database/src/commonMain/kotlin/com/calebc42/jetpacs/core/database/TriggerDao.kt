package com.calebc42.jetpacs.core.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TriggerDao {
    @Query("""
        SELECT r.pairing_id AS pairingId, r.identity, r.trigger_id AS triggerId,
               r.authored_ordinal AS authoredOrdinal, r.entry_json AS entryJson,
               r.canonical_identity AS canonicalIdentity,
               t.throttle_floor_epoch_ms AS throttleFloorEpochMs,
               COALESCE(t.one_shot_completed, 0) AS oneShotCompleted,
               t.schedule_anchor_epoch_ms AS scheduleAnchorEpochMs,
               t.last_fire_floor_epoch_ms AS lastFireFloorEpochMs,
               t.boot_generation AS bootGeneration
        FROM trigger_registrations r
        LEFT JOIN trigger_runtime t USING (pairing_id, identity, trigger_id)
        WHERE r.pairing_id = :pairingId AND (:identity IS NULL OR r.identity = :identity)
        ORDER BY r.identity, r.authored_ordinal
    """)
    suspend fun getTriggers(pairingId: String, identity: String?): List<TriggerWithRuntime>

    @Query("SELECT * FROM trigger_registrations WHERE pairing_id = :pairingId ORDER BY identity, authored_ordinal")
    fun observeRegistrations(pairingId: String): Flow<List<TriggerRegistrationEntity>>

    @Upsert suspend fun upsertRegistration(registration: TriggerRegistrationEntity)
    @Upsert suspend fun upsertRuntime(runtime: TriggerRuntimeEntity)

    @Query("DELETE FROM trigger_registrations WHERE pairing_id = :pairingId AND identity = :identity AND trigger_id = :triggerId")
    suspend fun deleteTrigger(pairingId: String, identity: String, triggerId: String): Int

    @Query("DELETE FROM trigger_registrations WHERE pairing_id = :pairingId AND (:identity IS NULL OR identity = :identity)")
    suspend fun deleteTriggers(pairingId: String, identity: String?): Int
}
