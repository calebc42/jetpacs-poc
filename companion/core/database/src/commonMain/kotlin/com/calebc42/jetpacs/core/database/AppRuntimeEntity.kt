package com.calebc42.jetpacs.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/** Device-local Jetpacs policy, separate from pairing-authored EBP state. */
@Entity(tableName = "app_runtime")
data class AppRuntimeEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = SINGLETON_ID,
    @ColumnInfo(name = "background_bridge_enabled")
    val backgroundBridgeEnabled: Boolean,
) {
    init { require(singletonId == SINGLETON_ID) }

    companion object {
        const val SINGLETON_ID = 0
    }
}
