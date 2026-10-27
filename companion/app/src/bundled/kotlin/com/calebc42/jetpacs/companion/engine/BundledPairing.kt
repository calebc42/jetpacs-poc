// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

import android.content.Context
import android.util.Log
import com.calebc42.ebp.wire.CompanionProofKeyResult
import com.calebc42.ebp.wire.EbpAuth
import com.calebc42.ebp.wire.PairingId
import com.calebc42.jetpacs.companion.ResolvedPairing
import com.calebc42.jetpacs.companion.host.KnownAnswerPairing
import com.calebc42.jetpacs.core.database.JetpacsDatabase
import com.calebc42.jetpacs.core.ebpstore.PairingCredentials
import com.calebc42.jetpacs.core.ebpstore.ProvisionedPairingAliases

/**
 * The bundled flavor's pairing (SPEC 9.1 under amendment #209): generated
 * once by the Companion, the Companion's copy under the Android KeyStore,
 * the Emacs endpoint's copy an application-private 0600 file in the
 * engine's HOME that the supervisor writes and `jetpacs-credentials.el`
 * reads. Later starts reuse the pairing the Emacs copy names while its key
 * is still in the KeyStore; anything else means a fresh pairing, since the
 * token cannot be read back out of the KeyStore.
 */
internal object BundledPairing {
    private const val TAG = "JetpacsEngine"

    suspend fun resolve(
        app: Context,
        database: JetpacsDatabase,
        credentials: PairingCredentials,
    ): ResolvedPairing {
        val home = EngineHome.of(app)
        val named = home.pairingFile.takeIf { it.isFile }?.let { EngineFiles.pairingIdOf(it.readText()) }
        if (named != null && named != KnownAnswerPairing.PAIRING_ID) {
            val partition = database.pairingDao().listActivePartitions().firstOrNull { it.pairingId == named }
            val known = credentials.proofProvider.resolve(named) is CompanionProofKeyResult.Known
            if (partition != null && known) {
                return ResolvedPairing(
                    PairingId(named),
                    ProvisionedPairingAliases(partition.credentialKeyAlias, partition.payloadKeyAlias),
                )
            }
            Log.w(TAG, "pairing $named unusable (partition=${partition != null}, key=$known); generating a new one")
        }
        val pairingId = PairingId(EbpAuth.generatePairingId())
        val token = EbpAuth.generatePairingToken()
        try {
            val aliases = credentials.provision(pairingId, token)
            home.writePairing(pairingId.value, EbpAuth.encodePairingToken(token))
            Log.i(TAG, "generated pairing ${pairingId.value}; Emacs copy at ${home.pairingFile}")
            return ResolvedPairing(pairingId, aliases)
        } finally {
            token.fill(0)
        }
    }
}
