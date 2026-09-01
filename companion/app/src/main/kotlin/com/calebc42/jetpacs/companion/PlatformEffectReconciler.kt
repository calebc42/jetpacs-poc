// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.content.Context
import android.util.Log
import com.calebc42.ebp.wire.DurablePlatformEffect
import com.calebc42.ebp.wire.PairingId
import com.calebc42.ebp.wire.claimPlatformEffect
import com.calebc42.ebp.wire.completePlatformEffect
import com.calebc42.jetpacs.core.ebpstore.RoomEbpDurableStore
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Executes Room's idempotent platform-effect ledger outside its transaction.
 * A crash after NotificationManager accepts a reminder merely replaces the
 * same tag/id on replay; a crash before that leaves the lease reclaimable.
 */
internal class PlatformEffectReconciler(
    context: Context,
    private val durableStore: RoomEbpDurableStore,
    private val pairingId: PairingId,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val app = context.applicationContext

    suspend fun drain() {
        val durableFloor = durableStore.restore(pairingId).platformEffects.maxOfOrNull { effect ->
            maxOf(
                effect.createdAtMs,
                effect.claimedAtMs ?: 0,
                effect.completedAtMs ?: 0,
            )
        } ?: 0
        // This reconciler is actor-confined, so no second worker can own a live
        // lease. Advance one millisecond past the durable floor to reclaim work
        // even if the device wall clock moved backwards across a restart.
        var logicalNow = maxOf(
            nowMs().coerceAtLeast(0),
            durableFloor.takeIf { it < Long.MAX_VALUE }?.plus(EFFECT_LEASE_MS)
                ?: durableFloor,
        )
        repeat(MAX_EFFECTS_PER_DRAIN) {
            val claimedAt = logicalNow
            val effect = durableStore.claimPlatformEffect(
                pairingId = pairingId,
                claimedAtMs = claimedAt,
                leaseMs = EFFECT_LEASE_MS,
            ) ?: return

            val completed = runCatching { execute(effect) }
                .onFailure { failure ->
                    Log.w(TAG, "Platform effect ${effect.effectId} will be retried", failure)
                }
                .getOrDefault(false)
            if (!completed) return

            check(
                durableStore.completePlatformEffect(
                    pairingId = pairingId,
                    effectId = effect.effectId,
                    completedAtMs = maxOf(nowMs().coerceAtLeast(0), claimedAt),
                ),
            ) { "Claimed platform effect disappeared before completion" }
            logicalNow = maxOf(nowMs().coerceAtLeast(0), claimedAt)
        }
    }

    private fun execute(effect: DurablePlatformEffect): Boolean = when (effect.kind) {
        REMINDER_NOTIFICATION_KIND -> {
            val payload = effect.payload
            Notifications.postReminder(
                app,
                payload.requiredString("owner"),
                payload.requiredString("reminder_id"),
                payload.requiredString("title"),
                payload.optionalString("body"),
            )
            true
        }
        else -> {
            Log.w(TAG, "Unknown platform effect kind ${effect.kind}; retaining it for an update")
            false
        }
    }

    companion object {
        private const val TAG = "JetpacsEffects"
        private const val MAX_EFFECTS_PER_DRAIN = 256
        private const val EFFECT_LEASE_MS = 1L
        const val REMINDER_NOTIFICATION_KIND = "notification.reminder"

        fun reminderNotification(
            owner: String,
            reminderId: String,
            atMs: Long,
            title: String,
            body: String?,
            createdAtMs: Long,
        ): DurablePlatformEffect {
            val tuple = "$owner\u0000$reminderId\u0000$atMs"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(tuple.encodeToByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return DurablePlatformEffect(
                effectId = "reminder-$digest",
                kind = REMINDER_NOTIFICATION_KIND,
                payload = buildJsonObject {
                    put("owner", owner)
                    put("reminder_id", reminderId)
                    put("at_ms", atMs)
                    put("title", title)
                    body?.let { put("body", it) }
                },
                dedupeKey = "reminder:$digest",
                createdAtMs = createdAtMs,
            )
        }
    }
}

private fun JsonObject.requiredString(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IllegalArgumentException("Platform effect is missing $key")

private fun JsonObject.optionalString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
