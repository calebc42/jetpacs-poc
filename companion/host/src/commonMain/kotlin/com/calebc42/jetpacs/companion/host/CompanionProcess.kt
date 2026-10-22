// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

import com.calebc42.ebp.wire.ActivatePairingCommand
import com.calebc42.ebp.wire.ActivatePairingResult
import com.calebc42.ebp.wire.EbpCommandActor
import com.calebc42.ebp.wire.PairingId
import com.calebc42.ebp.wire.activatePairing
import com.calebc42.jetpacs.core.database.AppRuntimeEntity
import com.calebc42.jetpacs.core.database.JetpacsDatabase
import com.calebc42.jetpacs.core.ebpstore.PairingCredentials
import com.calebc42.jetpacs.core.ebpstore.ProvisionedPairingAliases
import com.calebc42.jetpacs.core.ebpstore.RoomEbpDurableStore
import com.calebc42.jetpacs.core.ebpstore.SecureRandomStorageGenerationSource
import com.calebc42.jetpacs.core.ebpstore.SnapshotProvisionedPairingAliasResolver
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The platform-neutral composition root below a host's own one: one Room
 * store, one command actor, the process-wide projections, and the bridge
 * lifecycle. A host opens the database and provisions the credentials
 * first, then passes both in; this class opens nothing itself.
 */
class CompanionProcess private constructor(
    val database: JetpacsDatabase,
    val durableStore: RoomEbpDurableStore,
    val pairingId: PairingId,
    val credentials: PairingCredentials,
    val commandActor: EbpCommandActor,
    val stores: CompanionStores,
    val presentation: CompanionPresentation,
    val platform: CompanionHostPlatform,
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
            platform.presentation.rearmAfterRecovery(stores)
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
         * Build the process over an open [database] and the [aliases] the
         * host's [credentials] already provisioned for [pairingId]. The
         * pairing is activated (an already-active one is fine; a fenced one
         * is an error), the actor is created, and every projection is
         * initialized here, on the caller's dispatcher, before any protocol
         * object can accept work.
         */
        suspend fun create(
            database: JetpacsDatabase,
            pairingId: PairingId,
            aliases: ProvisionedPairingAliases,
            credentials: PairingCredentials,
            platform: CompanionHostPlatform,
            renderer: CompanionRenderer,
            actorScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            nowMs: () -> Long = System::currentTimeMillis,
        ): CompanionProcess {
            val durableStore = RoomEbpDurableStore(
                database = database,
                aliasResolver = SnapshotProvisionedPairingAliasResolver(
                    mapOf(pairingId to aliases),
                ),
                envelopeCodec = credentials.envelopeCodec,
                generationSource = SecureRandomStorageGenerationSource(),
            )
            when (
                durableStore.activatePairing(
                    ActivatePairingCommand(
                        pairingId = pairingId,
                        createdAtMs = nowMs().coerceAtLeast(0),
                    ),
                )
            ) {
                is ActivatePairingResult.Activated,
                is ActivatePairingResult.AlreadyActive -> Unit
                is ActivatePairingResult.Fenced ->
                    error("The pairing is fenced and cannot be reactivated")
            }
            val actor = EbpCommandActor(actorScope, capacity = 64)
            val presentation = CompanionPresentation()
            val stores = CompanionStores(
                platform = platform,
                renderer = renderer,
                durableStore = durableStore,
                pairingId = pairingId,
                commandActor = actor,
                setKeepScreenOn = presentation::setKeepScreenOn,
            )
            stores.initializeProductionProjections()
            return CompanionProcess(
                database = database,
                durableStore = durableStore,
                pairingId = pairingId,
                credentials = credentials,
                commandActor = actor,
                stores = stores,
                presentation = presentation,
                platform = platform,
            )
        }
    }
}
