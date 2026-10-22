// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.content.Context
import com.calebc42.ebp.wire.EbpAuth
import com.calebc42.ebp.wire.PairingId
import com.calebc42.jetpacs.companion.host.CompanionProcess
import com.calebc42.jetpacs.core.database.buildJetpacsDatabase
import com.calebc42.jetpacs.core.ebpstore.AndroidKeyStorePairingCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The Android bootstrap below [JetpacsApplication]: it opens Room, provisions
 * the fixed POC pairing into AndroidKeyStore, and hands both to the shared
 * [CompanionProcess], which owns everything from the store up.
 */
internal object JetpacsProcessContainer {
    /**
     * Open Room and provision keys on an IO thread before any protocol
     * object can accept work. This one bounded bootstrap wait avoids a
     * half-composed process in which receivers and the socket see
     * different stores.
     */
    fun create(context: Context): CompanionProcess =
        runBlocking(Dispatchers.IO) {
            val app = context.applicationContext
            val database = buildJetpacsDatabase(app)
            val pairingId = PairingId(JETPACS_PAIRING_ID)
            check(database.revocationDao().getRevocation(pairingId.value) == null) {
                "The fixed POC pairing was revoked and cannot be reactivated"
            }
            val credentials = AndroidKeyStorePairingCredentials()
            val rawCredential = EbpAuth.decodePairingToken(JETPACS_PAIRING_TOKEN)
            val aliases = try {
                credentials.provision(pairingId, rawCredential)
            } finally {
                rawCredential.fill(0)
            }
            CompanionProcess.create(
                database = database,
                pairingId = pairingId,
                aliases = aliases,
                credentials = credentials,
                platform = AndroidHostPlatform(app),
                renderer = AndroidCompanionRenderer,
            )
        }
}
