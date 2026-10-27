// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

import com.calebc42.ebp.wire.AppSurfaceAdmission
import com.calebc42.ebp.wire.CapabilityHandler
import com.calebc42.ebp.wire.DurableQueue
import com.calebc42.ebp.wire.EbpActorOverloaded
import com.calebc42.ebp.wire.EbpCommandActor
import com.calebc42.ebp.wire.LiveSession
import com.calebc42.ebp.wire.MAX_WIDGET_LAZY_ITEMS
import com.calebc42.ebp.wire.MAX_WIDGET_NODES
import com.calebc42.ebp.wire.MAX_WIDGET_NODE_DEPTH
import com.calebc42.ebp.wire.MAX_WIDGET_SIZE_VARIANTS
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
 * A composition root is the sole constructor. No production code opens a
 * file-backed store, and every mutation is admitted through [commandActor].
 * Everything platform-specific (the device report, trigger sources, the
 * alarm and notification adapters) arrives through [platform]; the renderer
 * installation through [renderer].
 */
class CompanionStores(
    private val platform: CompanionHostPlatform,
    private val renderer: CompanionRenderer,
    private val durableStore: RoomEbpDurableStore,
    private val pairingId: PairingId,
    val commandActor: EbpCommandActor,
    /** `screen.keep_on`: the host's window keeps the screen awake, or not. */
    private val setKeepScreenOn: (Boolean) -> Unit,
) {
    private val nowMs = System::currentTimeMillis
    private val triggerOccurrence = RoomTriggerOccurrenceCoordinator(durableStore, pairingId)
    private val platformEffects = PlatformEffectReconciler(platform, durableStore, pairingId, nowMs)

    @Volatile private var queueInstance: DurableQueue? = null
    @Volatile private var surfacesInstance: SurfaceStore? = null
    @Volatile private var remindersInstance: ReminderStore? = null
    @Volatile private var triggersInstance: TriggerStore? = null
    @Volatile private var firingInstance: TriggerFiringService? = null
    @Volatile private var sourcesInstance: HostTriggerSources? = null
    @Volatile private var themeInstance: RoomThemeStore? = null
    private val capabilityHandlerInstance: Lazy<CapabilityHandler?> = lazy {
        platform.capabilities.handler(
            MAX_FIELD_BYTES,
            AppCapabilityBindings(
                pairingIdentity = pairingId.value,
                state = triggerSources(),
                fireManual = ::fireManualFromHost,
                setKeepScreenOn = setKeepScreenOn,
            ),
        )
    }

    private val liveSessionRef = AtomicReference<LiveSession?>()

    /**
     * SPEC 5.8 (docs/PLAN-bundled-emacs.md, E4): the platform's wake signal,
     * invoked after a cold durable `wake` admission while no engine exists.
     * Null where the platform defines no wake mechanism, and then the cold
     * senders see no live session at all, as before.
     */
    @Volatile var wakeRequester: (() -> Unit)? = null

    /**
     * What cold senders get in place of a missing engine when a wake exists:
     * a `drop` is lost exactly as with no session (SPEC 15.1), and a durable
     * `wake` admission asks the platform to wake the Emacs host. The engine
     * applies the same 60 s bound to its own wakes; the platform's wake
     * applies it to these.
     */
    private val coldWakeSession = object : LiveSession {
        override fun deliverLiveDrop(
            params: kotlinx.serialization.json.JsonObject,
            callback: ((String?, kotlinx.serialization.json.JsonObject?) -> Unit)?,
        ) = Unit

        override fun onDurableAdmitted(policy: String) {
            if (policy == "wake") wakeRequester?.invoke()
        }
    }

    val liveSession: LiveSession? get() = liveSessionRef.get() ?: wakeRequester?.let { coldWakeSession }

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

    /**
     * Submit what the user just did on the actor's action lane
     * (PLAN-responsiveness C2b): taken ahead of queued process work and
     * never saturated, so a tap, a keystroke or a dialog answer neither
     * waits behind an inbound frame's successors nor surfaces as an
     * overload toast. Same single writer; false only once the actor closed.
     */
    fun submitAction(block: suspend () -> Unit): Boolean = try {
        commandActor.submitAction(block)
        true
    } catch (_: IllegalStateException) {
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
            appNodeTypes = renderer.APP_NODE_TYPES,
            notificationNodeTypes = renderer.NOTIFICATION_NODE_TYPES,
            widgetNodeTypes = renderer.WIDGET_NODE_TYPES,
            appBuiltins = renderer.APP_BUILTINS,
            notificationBuiltins = renderer.NOTIFICATION_BUILTINS,
            widgetBuiltins = renderer.WIDGET_BUILTINS,
            appFeatures = renderer.APP_FEATURES,
            notificationFeatures = renderer.NOTIFICATION_FEATURES,
            widgetFeatures = renderer.WIDGET_FEATURES,
            widgetMembers = memberSupportFromProfiles(
                renderer.surfaceProfiles(),
                "widget",
            ),
            // A platform without a widget target keeps wire's own widget
            // ceilings; nothing can reach them, since no widget is admitted.
            maxWidgetNodes = renderer.widgetProfile?.limits?.maxNodes ?: MAX_WIDGET_NODES,
            maxWidgetLazyItems = renderer.widgetProfile?.limits?.maxLazyItems ?: MAX_WIDGET_LAZY_ITEMS,
            maxWidgetNodeDepth = renderer.widgetProfile?.limits?.maxNodeDepth ?: MAX_WIDGET_NODE_DEPTH,
            maxWidgetSizeVariants =
                renderer.widgetProfile?.limits?.maxSizeVariants ?: MAX_WIDGET_SIZE_VARIANTS,
            tileBuiltins = renderer.TILE_BUILTINS,
            tileFeatures = renderer.TILE_FEATURES,
            tileSurfaceIds = platform.capabilities.tileSurfaceIds,
            nodeVocabulary = renderer.NODE_VOCABULARY,
            backing = RoomSurfaceBacking(durableStore, pairingId, nowMs),
            appAdmissionProvider = {
                val installation = renderer.installation(platform.settings.designRuntimeEnabled)
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
                triggerCaps = jsonStringSet(platform.capabilities.deviceReport(), "trigger_caps"),
                capabilityHandler = capabilityHandler(),
                bootGeneration = { platform.capabilities.bootGeneration() },
                occurrenceTransaction = triggerOccurrence,
            ).also { service ->
                service.stateProvider = { type -> triggerSources().currentState(type) }
                service.statePredicateProvider = { predicate ->
                    triggerSources().predicateHolds(predicate)
                }
                service.notifyListener = { notify -> platform.presentation.triggerNotification(notify) }
                service.onTimeScheduleChanged = { platform.presentation.timeScheduleChanged(this) }
                firingInstance = service
            }
        }
    }

    internal fun triggerSources(): HostTriggerSources {
        sourcesInstance?.let { return it }
        return synchronized(this) {
            sourcesInstance ?: platform.capabilities.triggerSources(
                onSample = { type, sample ->
                    submit { firing().observeSample(type, sample) }
                },
                onExternal = { type, data ->
                    submit { firing().observeExternal(type, data) }
                },
            ).also { sourcesInstance = it }
        }
    }

    /** SPEC 20.2: the platform's capability executor, or null where it offers none. */
    fun capabilityHandler(): CapabilityHandler? = capabilityHandlerInstance.value

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
