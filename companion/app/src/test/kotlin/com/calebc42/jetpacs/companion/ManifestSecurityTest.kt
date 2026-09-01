// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ManifestSecurityTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun components(tag: String): List<Element> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.attr(name: String): String = getAttributeNS(android, name)

    @Test
    fun exportedActivityIsOnlyTheLauncherEntryPoint() {
        val exported = components("activity").filter { it.attr("exported") == "true" }
        assertEquals(listOf(".MainActivity"), exported.map { it.attr("name") })
        val filters = exported.single().getElementsByTagName("intent-filter")
        assertEquals(1, filters.length)
        val filter = filters.item(0) as Element
        val actions = filter.getElementsByTagName("action")
        assertEquals(1, actions.length)
        assertEquals(
            "android.intent.action.MAIN",
            (actions.item(0) as Element).attr("name"),
        )
        val categories = filter.getElementsByTagName("category")
        assertEquals(1, categories.length)
        assertEquals(
            "android.intent.category.LAUNCHER",
            (categories.item(0) as Element).attr("name"),
        )
    }

    @Test
    fun exportedReceiversAreOnlyProtectedSystemEntrypoints() {
        val receivers = components("receiver")
        val exported = receivers.filter { it.attr("exported") == "true" }
        assertEquals(setOf(".BootReceiver", ".SmsTriggerReceiver"),
            exported.map { it.attr("name") }.toSet())
        val sms = exported.single { it.attr("name") == ".SmsTriggerReceiver" }
        assertEquals("android.permission.BROADCAST_SMS", sms.attr("permission"))
        val smsActions = sms.getElementsByTagName("action")
        assertEquals(1, smsActions.length)
        assertEquals(
            "android.provider.Telephony.SMS_RECEIVED",
            (smsActions.item(0) as Element).attr("name"),
        )
        val boot = exported.single { it.attr("name") == ".BootReceiver" }
        val bootActions = boot.getElementsByTagName("action")
        assertEquals(
            setOf(
                "android.intent.action.BOOT_COMPLETED",
                "android.intent.action.TIMEZONE_CHANGED",
                "android.intent.action.TIME_SET",
                "android.intent.action.MY_PACKAGE_REPLACED",
            ),
            (0 until bootActions.length)
                .map { (bootActions.item(it) as Element).attr("name") }
                .toSet(),
        )
    }

    @Test
    fun onlyFixedSignatureProtectedTilesAreExportedServices() {
        val services = components("service")
        val tiles = services.filter { it.attr("exported") == "true" }
        assertEquals((1..5).map { ".EbpTile$it" }.toSet(),
            tiles.map { it.attr("name") }.toSet())
        assertTrue(tiles.all {
            it.attr("permission") == "android.permission.BIND_QUICK_SETTINGS_TILE"
        })
        val bridge = services.single { it.attr("name") == ".JetpacsBridgeService" }
        assertFalse(bridge.attr("exported").toBoolean())
    }
}
