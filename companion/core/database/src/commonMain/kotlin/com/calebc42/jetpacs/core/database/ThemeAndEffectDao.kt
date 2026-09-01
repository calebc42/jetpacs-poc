package com.calebc42.jetpacs.core.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ThemeAndEffectDao {
    @Query("SELECT * FROM pairing_themes WHERE pairing_id = :pairingId")
    suspend fun getTheme(pairingId: String): PairingThemeEntity?

    @Query("SELECT * FROM pairing_themes WHERE pairing_id = :pairingId")
    fun observeTheme(pairingId: String): Flow<PairingThemeEntity?>

    @Upsert suspend fun upsertTheme(theme: PairingThemeEntity)

    @Query("DELETE FROM pairing_themes WHERE pairing_id = :pairingId")
    suspend fun deleteTheme(pairingId: String): Int

    @Query("SELECT * FROM platform_effects WHERE pairing_id = :pairingId AND state IN (:states) ORDER BY created_at_epoch_ms, effect_id LIMIT :limit")
    suspend fun getEffects(pairingId: String, states: List<String>, limit: Int): List<PlatformEffectEntity>

    @Query("SELECT * FROM platform_effects WHERE pairing_id = :pairingId AND effect_id = :effectId")
    suspend fun getEffect(pairingId: String, effectId: String): PlatformEffectEntity?

    @Query("SELECT * FROM platform_effects WHERE pairing_id = :pairingId AND dedupe_key = :dedupeKey")
    suspend fun getEffectByDedupeKey(
        pairingId: String,
        dedupeKey: String,
    ): PlatformEffectEntity?

    @Upsert suspend fun upsertEffect(effect: PlatformEffectEntity)

    @Query("DELETE FROM platform_effects WHERE pairing_id = :pairingId AND effect_id = :effectId")
    suspend fun deleteEffect(pairingId: String, effectId: String): Int

    @Query("DELETE FROM platform_effects WHERE pairing_id = :pairingId")
    suspend fun deleteEffects(pairingId: String): Int
}
