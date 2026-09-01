// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.content.Context
import com.calebc42.ebp.wire.ActivatePairingCommand
import com.calebc42.ebp.wire.ActivatePairingResult
import com.calebc42.ebp.wire.CompanionProofProvider
import com.calebc42.ebp.wire.EbpAuth
import com.calebc42.ebp.wire.EbpCommandActor
import com.calebc42.ebp.wire.PairingId
import com.calebc42.ebp.wire.activatePairing
import com.calebc42.jetpacs.core.database.AppRuntimeEntity
import com.calebc42.jetpacs.core.database.JetpacsDatabase
import com.calebc42.jetpacs.core.database.buildJetpacsDatabase
import com.calebc42.jetpacs.core.ebpstore.AndroidKeyStoreCredentialProvisioner
import com.calebc42.jetpacs.core.ebpstore.AndroidKeyStoreOutboxPayloadEnvelopeCodec
import com.calebc42.jetpacs.core.ebpstore.AndroidKeyStorePayloadKeyProvisioner
import com.calebc42.jetpacs.core.ebpstore.ProvisionedPairingAliases
import com.calebc42.jetpacs.core.ebpstore.RoomEbpDurableStore
import com.calebc42.jetpacs.core.ebpstore.SecureRandomStorageGenerationSource
import com.calebc42.jetpacs.core.ebpstore.SnapshotProvisionedPairingAliasResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/** The sole Android production composition root below [JetpacsApplication]. */
class JetpacsProcessContainer private constructor(
    val database: JetpacsDatabase,
    val durableStore: RoomEbpDurableStore,
    val pairingId: PairingId,
    val proofProvider: CompanionProofProvider,
    val commandActor: EbpCommandActor,
    val stores: CompanionStores,
) {
    private val bridgeStarted = AtomicBoolean(false)

    suspend fun backgroundBridgeEnabled(): Boolean =
        database.appRuntimeDao().get()?.backgroundBridgeEnabled == true

    suspend fun setBackgroundBridgeEnabled(enabled: Boolean) {
        database.appRuntimeDao().upsert(
            AppRuntimeEntity(backgroundBridgeEnabled = enabled),
        )
    }

    fun enableBridge(bridge: DeviceBridge): Boolean = stores.submit {
        setBackgroundBridgeEnabled(true)
        startBridgeOnActor(bridge)
    }

    fun disableBridge(bridge: DeviceBridge, afterStop: () -> Unit): Boolean = stores.submit {
        setBackgroundBridgeEnabled(false)
        stopBridgeOnActor(bridge)
        afterStop()
    }

    fun startBridge(bridge: DeviceBridge): Boolean = stores.submit {
        startBridgeOnActor(bridge)
    }

    private suspend fun startBridgeOnActor(bridge: DeviceBridge) {
        if (!bridgeStarted.compareAndSet(false, true)) return
        try {
            val firing = stores.firing()
            firing.recover()
            stores.reconcilePlatformEffects()
            stores.triggerSources().start()
            firing.armAllBaselines()
            Notifications.rearmAllReminders(bridge.context, stores)
            TriggerAlarms.reschedule(bridge.context, stores)
            bridge.start()
        } catch (failure: Throwable) {
            bridgeStarted.set(false)
            throw failure
        }
    }

    fun stopBridge(bridge: DeviceBridge): Boolean = stores.submit {
        stopBridgeOnActor(bridge)
    }

    private fun stopBridgeOnActor(bridge: DeviceBridge) {
        if (bridgeStarted.compareAndSet(true, false)) bridge.stop()
    }

    companion object {
        /**
         * Open Room and provision keys on an IO thread before any protocol
         * object can accept work. This one bounded bootstrap wait avoids a
         * half-composed process in which receivers and the socket see
         * different stores.
         */
        fun create(context: Context): JetpacsProcessContainer =
            runBlocking(Dispatchers.IO) {
                val app = context.applicationContext
                val database = buildJetpacsDatabase(app)
                val pairingId = PairingId(JETPACS_PAIRING_ID)
                check(database.revocationDao().getRevocation(pairingId.value) == null) {
                    "The fixed POC pairing was revoked and cannot be reactivated"
                }

                val aliasPolicy =
                    com.calebc42.jetpacs.core.ebpstore.VersionedAndroidPairingAliasPolicy
                val credentialProvisioner = AndroidKeyStoreCredentialProvisioner(aliasPolicy)
                val payloadProvisioner = AndroidKeyStorePayloadKeyProvisioner(aliasPolicy)
                val rawCredential = EbpAuth.decodePairingToken(JETPACS_PAIRING_TOKEN)
                val credential = try {
                    credentialProvisioner.ensureKey(pairingId, rawCredential)
                } finally {
                    rawCredential.fill(0)
                }
                val payload = payloadProvisioner.ensureKey(pairingId)
                val dummyAlias = credentialProvisioner.ensureDummyKey()
                val aliases = ProvisionedPairingAliases(
                    credentialKeyAlias = credential.alias,
                    payloadKeyAlias = payload.alias,
                )
                val durableStore = RoomEbpDurableStore(
                    database = database,
                    aliasResolver = SnapshotProvisionedPairingAliasResolver(
                        mapOf(pairingId to aliases),
                    ),
                    envelopeCodec = AndroidKeyStoreOutboxPayloadEnvelopeCodec(),
                    generationSource = SecureRandomStorageGenerationSource(),
                )
                when (
                    durableStore.activatePairing(
                        ActivatePairingCommand(
                            pairingId = pairingId,
                            createdAtMs = System.currentTimeMillis().coerceAtLeast(0),
                        ),
                    )
                ) {
                    is ActivatePairingResult.Activated,
                    is ActivatePairingResult.AlreadyActive -> Unit
                    is ActivatePairingResult.Fenced ->
                        error("The fixed POC pairing is fenced")
                }
                val actorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val actor = EbpCommandActor(actorScope, capacity = 64)
                val stores = CompanionStores(app, durableStore, pairingId, actor)
                stores.initializeProductionProjections()
                JetpacsProcessContainer(
                    database = database,
                    durableStore = durableStore,
                    pairingId = pairingId,
                    proofProvider = AndroidCompanionProofProvider(
                        pairingAliases = mapOf(pairingId.value to credential.alias),
                        dummyAlias = dummyAlias,
                    ),
                    commandActor = actor,
                    stores = stores,
                )
            }
    }
}
