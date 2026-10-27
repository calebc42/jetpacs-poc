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

    private fun components(tag: String, manifest: String = "src/main/AndroidManifest.xml"): List<Element> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(File(manifest))
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.attr(name: String): String = getAttributeNS(android, name)

    @Test
    fun exportedActivitiesAreOnlyExactSystemEntrypoints() {
        val exported = components("activity").filter { it.attr("exported") == "true" }
        assertEquals(
            setOf(".MainActivity", ".WidgetConfigurationActivity"),
            exported.map { it.attr("name") }.toSet(),
        )

        val launcher = exported.single { it.attr("name") == ".MainActivity" }
        val filters = launcher.getElementsByTagName("intent-filter")
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

        val widgetConfig = exported.single {
            it.attr("name") == ".WidgetConfigurationActivity"
        }
        val widgetFilters = widgetConfig.getElementsByTagName("intent-filter")
        assertEquals(1, widgetFilters.length)
        val widgetActions = (widgetFilters.item(0) as Element)
            .getElementsByTagName("action")
        assertEquals(1, widgetActions.length)
        assertEquals(
            "android.appwidget.action.APPWIDGET_CONFIGURE",
            (widgetActions.item(0) as Element).attr("name"),
        )
    }

    @Test
    fun reminderInteractionActivityIsPrivateAndNoDisplay() {
        val activity = components("activity").single {
            it.attr("name") == ".ReminderInteractionActivity"
        }
        assertEquals("false", activity.attr("exported"))
        assertEquals("true", activity.attr("noHistory"))
        assertEquals("true", activity.attr("excludeFromRecents"))
        assertEquals("@android:style/Theme.NoDisplay", activity.attr("theme"))
        assertEquals(0, activity.getElementsByTagName("intent-filter").length)
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

    /**
     * docs/PLAN-bundled-emacs.md: the bundled flavor's overlay declares exactly
     * two components, both private services without intent filters: the
     * engine's, in its own process, so SPEC 5.8's same-application wake
     * target exists by construction, and the Companion-process service the
     * engine binds while in the foreground, which is never a foreground
     * service itself (one notification). No Emacs activity, provider or
     * receiver is merged into the APK from here.
     */
    @Test
    fun bundledOverlayDeclaresOnlyPrivateServices() {
        val overlay = "src/bundled/AndroidManifest.xml"
        for (tag in listOf("activity", "receiver", "provider", "activity-alias")) {
            assertEquals(tag, 0, components(tag, overlay).size)
        }
        val services = components("service", overlay).associateBy { it.attr("name") }
        assertEquals(
            setOf(".engine.JetpacsEngineService", ".engine.CompanionKeepaliveService"),
            services.keys,
        )
        for (service in services.values) {
            assertEquals("false", service.attr("exported"))
            assertEquals(0, service.getElementsByTagName("intent-filter").length)
        }
        val engine = services.getValue(".engine.JetpacsEngineService")
        assertEquals(":emacs", engine.attr("process"))
        assertEquals("specialUse", engine.attr("foregroundServiceType"))
        val keepalive = services.getValue(".engine.CompanionKeepaliveService")
        assertEquals("", keepalive.attr("process"))
        assertEquals("", keepalive.attr("foregroundServiceType"))
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
