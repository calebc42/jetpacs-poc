package com.calebc42.jetpacs.core.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface AppRuntimeDao {
    @Query("SELECT * FROM app_runtime WHERE singleton_id = 0")
    suspend fun get(): AppRuntimeEntity?

    @Upsert
    suspend fun upsert(runtime: AppRuntimeEntity)
}
