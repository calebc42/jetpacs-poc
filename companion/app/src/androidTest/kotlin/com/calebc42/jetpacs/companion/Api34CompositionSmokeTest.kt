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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
}
