package com.calebc42.jetpacs.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Durable Android instance identity; a surface name is never a provider class. */
@Entity(
    tableName = "widget_bindings",
    foreignKeys = [
        ForeignKey(
            entity = PairingPartitionEntity::class,
            parentColumns = ["pairing_id"],
            childColumns = ["pairing_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(name = "index_widget_bindings_pairing_surface", value = ["pairing_id", "surface_id"]),
    ],
)
data class WidgetBindingEntity(
    @PrimaryKey
    @ColumnInfo(name = "app_widget_id")
    val appWidgetId: Int,
    @ColumnInfo(name = "pairing_id")
    val pairingId: String,
    @ColumnInfo(name = "surface_id")
    val surfaceId: String,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
) {
    init {
        require(appWidgetId > 0) { "appWidgetId must be positive" }
        require(pairingId.isNotBlank()) { "pairingId must not be blank" }
        require(surfaceId.startsWith("widget:") && surfaceId.length > "widget:".length) {
            "surfaceId must be an arbitrary non-empty widget:* surface"
        }
        require(createdAtEpochMs >= 0) { "createdAtEpochMs must be non-negative" }
        require(updatedAtEpochMs >= createdAtEpochMs) {
            "updatedAtEpochMs must not precede creation"
        }
    }
}

/** Opaque capability used by an explicit, non-exported click receiver. */
@Entity(
    tableName = "widget_action_tokens",
    foreignKeys = [
        ForeignKey(
            entity = WidgetBindingEntity::class,
            parentColumns = ["app_widget_id"],
            childColumns = ["app_widget_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(name = "index_widget_action_tokens_app_widget", value = ["app_widget_id"])],
)
data class WidgetActionTokenEntity(
    @PrimaryKey
    @ColumnInfo(name = "token")
    val token: String,
    @ColumnInfo(name = "app_widget_id")
    val appWidgetId: Int,
    @ColumnInfo(name = "pairing_id")
    val pairingId: String,
    @ColumnInfo(name = "surface_id")
    val surfaceId: String,
    @ColumnInfo(name = "revision")
    val revision: Long,
    @ColumnInfo(name = "descriptor_json")
    val descriptorJson: String,
    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
    @ColumnInfo(name = "expires_at_epoch_ms")
    val expiresAtEpochMs: Long,
) {
    init {
        require(token.matches(Regex("[0-9a-f]{32}"))) { "token must be 128-bit lowercase hex" }
        require(appWidgetId > 0) { "appWidgetId must be positive" }
        require(pairingId.isNotBlank()) { "pairingId must not be blank" }
        require(surfaceId.startsWith("widget:") && surfaceId.length > "widget:".length) {
            "surfaceId must be widget:*"
        }
        require(revision >= 0) { "revision must be non-negative" }
        require(descriptorJson.isNotBlank()) { "descriptorJson must not be blank" }
        require(createdAtEpochMs >= 0) { "createdAtEpochMs must be non-negative" }
        require(expiresAtEpochMs > createdAtEpochMs) { "token expiry must follow creation" }
    }
}
