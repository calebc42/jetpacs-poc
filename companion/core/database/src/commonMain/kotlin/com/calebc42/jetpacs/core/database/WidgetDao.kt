package com.calebc42.jetpacs.core.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class WidgetDao {
    @Query("SELECT * FROM widget_bindings WHERE app_widget_id = :appWidgetId")
    abstract suspend fun getBinding(appWidgetId: Int): WidgetBindingEntity?

    @Query("SELECT * FROM widget_bindings ORDER BY app_widget_id")
    abstract suspend fun getBindings(): List<WidgetBindingEntity>

    @Query(
        """
        SELECT * FROM widget_bindings
        WHERE pairing_id = :pairingId AND surface_id = :surfaceId
        ORDER BY app_widget_id
        """
    )
    abstract suspend fun getBindings(
        pairingId: String,
        surfaceId: String,
    ): List<WidgetBindingEntity>

    @Query("SELECT * FROM widget_bindings ORDER BY app_widget_id")
    abstract fun observeBindings(): Flow<List<WidgetBindingEntity>>

    @Upsert
    abstract suspend fun upsertBinding(binding: WidgetBindingEntity)

    @Query("DELETE FROM widget_bindings WHERE app_widget_id = :appWidgetId")
    abstract suspend fun deleteBinding(appWidgetId: Int): Int

    @Query("DELETE FROM widget_action_tokens WHERE app_widget_id = :appWidgetId")
    abstract suspend fun deleteTokens(appWidgetId: Int): Int

    @Upsert
    abstract suspend fun stageTokens(tokens: List<WidgetActionTokenEntity>)

    @Query(
        """
        DELETE FROM widget_action_tokens
        WHERE app_widget_id = :appWidgetId AND token NOT IN (:retainedTokens)
        """
    )
    abstract suspend fun deleteOtherTokens(
        appWidgetId: Int,
        retainedTokens: List<String>,
    ): Int

    @Query("SELECT * FROM widget_action_tokens WHERE token = :token")
    abstract suspend fun getToken(token: String): WidgetActionTokenEntity?

    @Query("DELETE FROM widget_action_tokens WHERE expires_at_epoch_ms <= :nowEpochMs")
    abstract suspend fun deleteExpiredTokens(nowEpochMs: Long): Int

    /** Host restore changes Android IDs; content and action tokens are regenerated. */
    @Transaction
    open suspend fun restoreBinding(
        oldAppWidgetId: Int,
        newAppWidgetId: Int,
        nowEpochMs: Long,
    ): Boolean {
        val old = getBinding(oldAppWidgetId) ?: return false
        deleteBinding(oldAppWidgetId)
        upsertBinding(
            old.copy(
                appWidgetId = newAppWidgetId,
                updatedAtEpochMs = nowEpochMs.coerceAtLeast(old.createdAtEpochMs),
            ),
        )
        return true
    }
}
