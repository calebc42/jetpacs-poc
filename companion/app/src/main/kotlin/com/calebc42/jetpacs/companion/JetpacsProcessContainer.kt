// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.content.Context
import com.calebc42.jetpacs.companion.host.CompanionProcess
import com.calebc42.jetpacs.core.database.buildJetpacsDatabase
import com.calebc42.jetpacs.core.ebpstore.AndroidKeyStorePairingCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The Android bootstrap below [JetpacsApplication]: it opens Room, has the
 * flavor resolve the pairing into AndroidKeyStore, and hands both to the
 * shared [CompanionProcess], which owns everything from the store up.
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
            val credentials = AndroidKeyStorePairingCredentials()
            // The flavor owns pairing policy: standalone provisions the fixed
            // known-answer credential, bundled generates and keeps its own.
            val pairing = EngineFlavor.resolvePairing(app, database, credentials)
            check(database.revocationDao().getRevocation(pairing.pairingId.value) == null) {
                "The pairing ${pairing.pairingId.value} was revoked and cannot be reactivated"
            }
            CompanionProcess.create(
                database = database,
                pairingId = pairing.pairingId,
                aliases = pairing.aliases,
                credentials = credentials,
                platform = AndroidHostPlatform(app),
                renderer = AndroidCompanionRenderer,
            )
        }
}
