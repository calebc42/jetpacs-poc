// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.content.Context
import android.provider.Settings
import com.calebc42.ebp.wire.DurableQueue
import com.calebc42.ebp.wire.AppSurfaceAdmission
import com.calebc42.ebp.wire.CapabilityHandler
import com.calebc42.ebp.wire.EbpActorOverloaded
import com.calebc42.ebp.wire.EbpCommandActor
import com.calebc42.ebp.wire.LiveSession
import com.calebc42.ebp.wire.PairingId
import com.calebc42.ebp.wire.ReminderStore
import com.calebc42.ebp.wire.SurfaceStore
import com.calebc42.ebp.wire.TriggerFiringService
import com.calebc42.ebp.wire.TriggerStore
import com.calebc42.ebp.wire.jsonStringSet
import com.calebc42.ebp.wire.memberSupportFromProfiles
import com.calebc42.jetpacs.core.ebpstore.RoomEbpDurableStore
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-owned compatibility projections over one Room EBP store.
 *
 * The Android application is the sole constructor. No production code opens
 * a file-backed store, and every mutation is admitted through [commandActor].
 */
class CompanionStores(
    context: Context,
    private val durableStore: RoomEbpDurableStore,
    private val pairingId: PairingId,
    val commandActor: EbpCommandActor,
) {
    private val app = context.applicationContext
    private val nowMs = System::currentTimeMillis
    private val triggerOccurrence = RoomTriggerOccurrenceCoordinator(durableStore, pairingId)
    private val platformEffects = PlatformEffectReconciler(app, durableStore, pairingId, nowMs)

    @Volatile private var queueInstance: DurableQueue? = null
    @Volatile private var surfacesInstance: SurfaceStore? = null
    @Volatile private var remindersInstance: ReminderStore? = null
    @Volatile private var triggersInstance: TriggerStore? = null
    @Volatile private var firingInstance: TriggerFiringService? = null
    @Volatile private var sourcesInstance: TriggerSources? = null
    @Volatile private var themeInstance: RoomThemeStore? = null
    @Volatile private var capabilityHandlerInstance: CapabilityHandler? = null

    private val liveSessionRef = AtomicReference<LiveSession?>()

    val liveSession: LiveSession? get() = liveSessionRef.get()

    fun setLiveSession(session: LiveSession) {
        liveSessionRef.set(session)
    }

    fun clearLiveSession(session: LiveSession): Boolean =
        liveSessionRef.compareAndSet(session, null)

    /** Eager bootstrap on the composition root's IO dispatcher. */
    internal fun initializeProductionProjections() {
        queue()
        surfaces()
        reminders()
        triggers()
        theme()
    }

    /** Submit one bounded process mutation without creating another writer. */
    fun submit(block: suspend () -> Unit): Boolean = try {
        commandActor.trySubmit(block)
        true
    } catch (_: EbpActorOverloaded) {
        false
    }

    fun queue(): DurableQueue = queueInstance ?: synchronized(this) {
        queueInstance ?: DurableQueue(
            RoomQueueStore(durableStore, pairingId, triggerOccurrence),
            256,
            8_388_608,
        ).also { queueInstance = it }
    }

    fun surfaces(): SurfaceStore = surfacesInstance ?: synchronized(this) {
        surfacesInstance ?: SurfaceStore(
            64,
            4096,
            maxCaptureFields = 64,
            maxChartPoints = 4096,
            maxCanvasOps = 4096,
            appNodeTypes = CompanionRenderer.APP_NODE_TYPES,
            notificationNodeTypes = CompanionRenderer.NOTIFICATION_NODE_TYPES,
            widgetNodeTypes = CompanionRenderer.WIDGET_NODE_TYPES,
            appBuiltins = CompanionRenderer.APP_BUILTINS,
            notificationBuiltins = CompanionRenderer.NOTIFICATION_BUILTINS,
            widgetBuiltins = CompanionRenderer.WIDGET_BUILTINS,
            appFeatures = CompanionRenderer.APP_FEATURES,
            notificationFeatures = CompanionRenderer.NOTIFICATION_FEATURES,
            widgetFeatures = CompanionRenderer.WIDGET_FEATURES,
            widgetMembers = memberSupportFromProfiles(
                CompanionRenderer.surfaceProfiles(),
                "widget",
            ),
            maxWidgetNodes = CompanionRenderer.widgetProfile.limits!!.maxNodes,
            maxWidgetLazyItems = CompanionRenderer.widgetProfile.limits!!.maxLazyItems,
            maxWidgetNodeDepth = CompanionRenderer.widgetProfile.limits!!.maxNodeDepth,
            maxWidgetSizeVariants = CompanionRenderer.widgetProfile.limits!!.maxSizeVariants,
            tileBuiltins = CompanionRenderer.TILE_BUILTINS,
            tileFeatures = CompanionRenderer.TILE_FEATURES,
            tileSurfaceIds = TileSlots.surfaceIds,
            nodeVocabulary = CompanionRenderer.NODE_VOCABULARY,
            backing = RoomSurfaceBacking(durableStore, pairingId, nowMs),
            appAdmissionProvider = {
                val installation = CompanionRenderer.installation(
                    ExperimentalElispDesignRuntime.isEnabled(app),
                )
                AppSurfaceAdmission(
                    nodeTypes = installation.appProfile.nodeTypes,
                    builtins = installation.appProfile.builtins,
                    features = installation.appProfile.features,
                    nodeVocabulary = installation.nodeVocabulary,
                )
            },
        ).also { surfacesInstance = it }
    }

    fun reminders(): ReminderStore = remindersInstance ?: synchronized(this) {
        remindersInstance ?: ReminderStore(
            RoomReminderBacking(durableStore, pairingId, nowMs),
        ).also { remindersInstance = it }
    }

    fun triggers(): TriggerStore = triggersInstance ?: synchronized(this) {
        triggersInstance ?: TriggerStore(
            RoomTriggerBacking(durableStore, pairingId, triggerOccurrence),
        ).also { triggersInstance = it }
    }

    internal fun theme(): RoomThemeStore = themeInstance ?: synchronized(this) {
        themeInstance ?: RoomThemeStore(durableStore, pairingId, nowMs)
            .also { themeInstance = it }
    }

    suspend fun reconcilePlatformEffects() = platformEffects.drain()

    fun firing(): TriggerFiringService {
        firingInstance?.let { return it }
        return synchronized(this) {
            firingInstance ?: TriggerFiringService(
                triggers(),
                queue(),
                MAX_EVENT_BYTES,
                triggerCaps = jsonStringSet(AppCapabilities.deviceReport(app), "trigger_caps"),
                capabilityHandler = capabilityHandler(),
                bootGeneration = { bootGeneration() },
                occurrenceTransaction = triggerOccurrence,
            ).also { service ->
                service.stateProvider = { type -> triggerSources().currentState(type) }
                service.statePredicateProvider = { predicate ->
                    triggerSources().predicateHolds(predicate)
                }
                service.notifyListener = { notify -> Notifications.postTrigger(app, notify) }
                service.onTimeScheduleChanged = { TriggerAlarms.reschedule(app, this) }
                firingInstance = service
            }
        }
    }

    private fun bootGeneration(): String? = runCatching {
        Settings.Global.getString(app.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrNull()

    internal fun triggerSources(): TriggerSources {
        sourcesInstance?.let { return it }
        return synchronized(this) {
            sourcesInstance ?: TriggerSources(
                app,
                onSample = { type, sample ->
                    submit { firing().observeSample(type, sample) }
                },
                onExternal = { type, data ->
                    submit { firing().observeExternal(type, data) }
                },
            ).also { sourcesInstance = it }
        }
    }

    fun capabilityHandler(): CapabilityHandler {
        capabilityHandlerInstance?.let { return it }
        return synchronized(this) {
            capabilityHandlerInstance ?: AppCapabilities.handler(
                app,
                MAX_FIELD_BYTES,
                AppCapabilityBindings(
                    pairingIdentity = pairingId.value,
                    state = triggerSources(),
                    fireManual = ::fireManualFromHost,
                    setKeepScreenOn = { enabled ->
                        (app as? JetpacsApplication)?.setKeepScreenOn(enabled)
                    },
                ),
            ).also { capabilityHandlerInstance = it }
        }
    }

    /** `trigger.fire` succeeds only for a stored manual registration. */
    internal fun fireManualFromHost(triggerId: String): Boolean {
        val registration = triggers().registration(pairingId.value, triggerId)
            ?: return false
        if (registration.entry.stringOrNull("type") != "manual") return false
        firing().fireManual(pairingId.value, triggerId, "emacs")
        return true
    }

    companion object {
        const val MAX_EVENT_BYTES = 262_144L
        const val MAX_FIELD_BYTES = 65_536L
        const val MAX_EDITOR_BYTES = 65_536L
    }
}
