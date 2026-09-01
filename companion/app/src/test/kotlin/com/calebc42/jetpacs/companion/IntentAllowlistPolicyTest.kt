// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IntentAllowlistPolicyTest {
    @Test
    fun sendRuleRequiresItsExactMimeAndExtraKeys() {
        assertNotNull(AppCapabilities.matchingIntentRule(buildJsonObject {
            put("action", "android.intent.action.SEND")
            put("mime", "text/plain")
            putJsonObject("extras") { put("android.intent.extra.TEXT", "hello") }
        }))
        assertNull(AppCapabilities.matchingIntentRule(buildJsonObject {
            put("action", "android.intent.action.SEND")
        }))
        assertNull(AppCapabilities.matchingIntentRule(buildJsonObject {
            put("action", "android.intent.action.SEND")
            put("mime", "text/plain")
            putJsonObject("extras") { put("attacker.extra", "hello") }
        }))
        assertNull(AppCapabilities.matchingIntentRule(buildJsonObject {
            put("action", "android.intent.action.SEND")
            put("mime", "text/plain")
            put("package", "example.attacker")
        }))
    }

    @Test
    fun uriRulesRequireExactSchemeAndRejectAuthorityOrUserInfo() {
        assertNotNull(AppCapabilities.matchingIntentRule(buildJsonObject {
            put("action", "android.intent.action.DIAL")
            put("data", "tel:+15551212")
        }))
        assertNull(AppCapabilities.matchingIntentRule(buildJsonObject {
            put("action", "android.intent.action.DIAL")
            put("data", "sms:+15551212")
        }))
        assertNull(AppCapabilities.matchingIntentRule(buildJsonObject {
            put("action", "android.intent.action.DIAL")
            put("data", "tel://user@example.test")
        }))
        assertNotNull(AppCapabilities.matchingIntentRule(buildJsonObject {
            put("action", "android.intent.action.VIEW")
            put("data", "geo:39.7,-104.9")
        }))
        assertNull(AppCapabilities.matchingIntentRule(buildJsonObject {
            put("action", "android.intent.action.VIEW")
            put("data", "geo://example.test/39.7,-104.9")
        }))
    }
}
