// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.content.ComponentName
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calebc42.ebp.wire.PairingFence
import com.calebc42.jetpacs.core.database.JETPACS_DATABASE_NAME
import java.security.KeyStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Api34CompositionSmokeTest {
    @Test
    fun api34StartsRoomKeystoreAndPrivateSpecialUseServiceComposition() = runBlocking {
        assertEquals(34, Build.VERSION.SDK_INT)
        val app = ApplicationProvider.getApplicationContext<JetpacsApplication>()
        val snapshot = app.container.durableStore.restore(app.container.pairingId)
        assertEquals(PairingFence.ACTIVE, snapshot.pairing?.fence)
        assertTrue(app.getDatabasePath(JETPACS_DATABASE_NAME).exists())

        val partition = app.container.database.pairingDao()
            .getPartition(app.container.pairingId.value)
        assertNotNull(partition)
        val aliases = checkNotNull(partition)
        assertFalse(aliases.credentialKeyAlias == aliases.payloadKeyAlias)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(keyStore.containsAlias(aliases.credentialKeyAlias))
        assertTrue(keyStore.containsAlias(aliases.payloadKeyAlias))

        val service = app.packageManager.getServiceInfo(
            ComponentName(app, JetpacsBridgeService::class.java),
            0,
        )
        assertFalse(service.exported)
        assertTrue(
            service.foregroundServiceType and
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0,
        )
    }

    @Test
    fun revivalCatalogAndPlatformSlotsMatchTheInstalledHost() {
        val app = ApplicationProvider.getApplicationContext<JetpacsApplication>()
        val report = AppCapabilities.deviceReport(app)
        val caps = checkNotNull(report.arrOrNull("caps"))
            .map { (it as JsonPrimitive).content }
            .toSet()
        assertEquals(EXPECTED_CAPS, caps)
        val triggerCaps = checkNotNull(report.arrOrNull("trigger_caps"))
            .map { (it as JsonPrimitive).content }
            .toSet()
        assertEquals(EXPECTED_TRIGGER_CAPS, triggerCaps)
        assertTrue(caps.containsAll(triggerCaps))
        assertTrue(
            report.toString().toByteArray(Charsets.UTF_8).size <=
                AppCapabilities.MAX_DEVICE_REPORT_BYTES,
        )

        val permissions = checkNotNull(report.objOrNull("permissions"))
        checkNotNull(report.objOrNull("trigger_unavailable")).forEach { (_, blockers) ->
            (blockers as JsonArray).forEach { blocker ->
                assertFalse(permissions.boolOr((blocker as JsonPrimitive).content))
            }
        }

        TileSlots.slots.values.forEach { serviceClass ->
            val service = app.packageManager.getServiceInfo(
                ComponentName(app, serviceClass),
                0,
            )
            assertTrue(service.exported)
            assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", service.permission)
        }

        // An exported launcher action without our private stored envelope and
        // constant-time token is inert, even for the current pairing.
        assertNull(PlatformShortcuts.resolveLaunch(
            app,
            app.container.pairingId.value,
            android.content.Intent(PlatformShortcuts.ACTION_INVOKE),
        ))
    }

    private companion object {
        val EXPECTED_CAPS = setOf(
            "settings.open", "intent.start", "app.launch", "apps.list",
            "shortcut.pin", "shortcuts.set", "vibrate", "tts.speak",
            "volume.set", "ringer.mode", "flashlight", "media.key",
            "clipboard.read", "screen.keep_on", "brightness.set", "dnd.set",
            "state.get", "trigger.fire",
        )
        val EXPECTED_TRIGGER_CAPS = setOf(
            "vibrate", "tts.speak", "ringer.mode", "flashlight",
            "media.key", "screen.keep_on", "brightness.set", "dnd.set",
        )
    }
}
