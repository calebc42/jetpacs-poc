// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderNotificationTest {
    @Test
    fun platformEffectRetainsTheAcceptedReminderDocument() {
        val actions = JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "label" to JsonPrimitive("Complete"),
                        "on_tap" to JsonObject(
                            mapOf("action" to JsonPrimitive("grove.complete")),
                        ),
                    ),
                ),
            ),
        )
        val reminder = JsonObject(
            mapOf(
                "id" to JsonPrimitive("r1"),
                "title" to JsonPrimitive("Task"),
                "at_ms" to JsonPrimitive(42),
                "actions" to actions,
            ),
        )

        val effect = PlatformEffectReconciler.reminderNotification(
            owner = "grove",
            reminderId = "r1",
            atMs = 42,
            reminder = reminder,
            createdAtMs = 40,
        )

        assertEquals(PlatformEffectReconciler.REMINDER_NOTIFICATION_KIND, effect.kind)
        assertEquals(JsonPrimitive("grove"), effect.payload["owner"])
        assertEquals(reminder, effect.payload["reminder"])
        assertTrue(effect.effectId.startsWith("reminder-"))
        assertEquals(effect.dedupeKey, "reminder:${effect.effectId.removePrefix("reminder-")}")
    }

    @Test
    fun ownerAndActionCoordinatesHaveIndependentPendingIntentKeys() {
        assertNotEquals(
            Notifications.reminderKey("grove", "same"),
            Notifications.reminderKey("org-mode", "same"),
        )
        assertNotEquals(
            Notifications.reminderKey("grove", "same"),
            Notifications.reminderKey("grove", "same/action/0"),
        )
        assertNotEquals(
            Notifications.reminderKey("grove", "same/action/0"),
            Notifications.reminderKey("grove", "same/action/1"),
        )
    }
}
