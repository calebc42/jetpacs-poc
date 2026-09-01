// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Protected system entrypoint for `sms.received`.
 *
 * The manifest additionally requires the sender-only BROADCAST_SMS permission.
 * Message content is bounded by the platform SMS envelope, never logged, and
 * enters the same process actor and encrypted durable queue as every other
 * occurrence.
 */
class SmsTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent).toList()
        }.getOrNull()?.takeIf(List<*>::isNotEmpty) ?: return
        val sender = messages.firstNotNullOfOrNull { it.originatingAddress }.orEmpty()
        if (!fitsField(sender)) return
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
            .takeIf(::fitsField)
        val pending = goAsync()
        val app = context.applicationContext as? JetpacsApplication
        val submitted = app?.stores?.submit {
            try {
                app.stores.firing().observeExternal("sms.received", buildJsonObject {
                    put("from", sender)
                    body?.let { put("body", it) }
                })
            } finally {
                pending.finish()
            }
        } == true
        if (!submitted) pending.finish()
    }

    /** Bound sensitive text before it enters the process actor or durable queue. */
    private fun fitsField(value: String): Boolean =
        JsonPrimitive(value).toString().toByteArray(Charsets.UTF_8).size.toLong() <=
            CompanionStores.MAX_FIELD_BYTES
}
