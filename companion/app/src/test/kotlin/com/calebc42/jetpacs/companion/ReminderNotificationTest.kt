// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import com.calebc42.jetpacs.companion.host.PlatformEffectReconciler
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

    @Test
    fun eachAppletGetsItsOwnGroupChannelAndShadeGroup() {
        assertEquals("applet:orgzly", Notifications.appletGroupId("orgzly"))
        assertEquals("reminders:orgzly", Notifications.reminderChannelId("orgzly"))
        assertNotEquals(
            Notifications.reminderChannelId("orgzly"),
            Notifications.reminderChannelId("bloom"),
        )
        assertEquals("reminders:bloom", Notifications.reminderGroupKey("bloom"))
        // The summary can never collide with a reminder of the same owner.
        assertNotEquals(
            Notifications.reminderSummaryKey("orgzly"),
            Notifications.reminderKey("orgzly", "summary"),
        )
        assertNotEquals(
            Notifications.reminderSummaryKey("orgzly"),
            Notifications.reminderSummaryKey("bloom"),
        )
    }

    @Test
    fun appletLabelsAndSummaryTextReadAsWords() {
        assertEquals("Orgzly", Notifications.appletLabel("orgzly"))
        assertEquals("Org Mode", Notifications.appletLabel("org-mode"))
        assertEquals("Jetpacs Files", Notifications.appletLabel("jetpacs.files"))
        assertEquals("1 reminder", Notifications.reminderSummaryText(1))
        assertEquals("3 reminders", Notifications.reminderSummaryText(3))
    }
}
