// SPDX-License-Identifier: GPL-3.0-or-later
// The android-loopback-tcp binder (SPEC 5.2): 127.0.0.1:8765, one client,
// a new authenticated session supersedes the old. Wraps CompanionEngine;
// the store outlives connections (SPEC 10.4).
//
// W4 SMOKE SCOPE: the pairing is the SPEC 9.3 known-answer credentials so
// the desktop can drive the device before the pairing UI exists (arrives
// with onboarding). Not a secret and not a deployment configuration.
package com.calebc42.jetpacs.companion

import com.calebc42.glasspane.material3.ImageCache
import com.calebc42.glasspane.material3.MaterialRendererHost
import com.calebc42.glasspane.material3.RetainedPresentationIncarnationTracker
import com.calebc42.ebp.renderer.model.objOrNull
import com.calebc42.ebp.renderer.model.stringOr
import com.calebc42.ebp.wire.CompletionNarrowing
import com.calebc42.ebp.wire.CompletionOfferView
import com.calebc42.ebp.wire.ActionAdmissionOutcome
import com.calebc42.ebp.wire.CompanionEngine
import com.calebc42.ebp.wire.CompanionConfig
import com.calebc42.ebp.wire.CompanionProofProvider
import com.calebc42.ebp.wire.EditorSeed
import com.calebc42.ebp.wire.EditorSession
import com.calebc42.ebp.wire.InputDisplay
import com.calebc42.ebp.wire.ScalarPos
import com.calebc42.ebp.wire.SessionState
import com.calebc42.ebp.wire.SurfaceStore
import com.calebc42.ebp.wire.Utf16Pos
import com.calebc42.ebp.wire.VARIANT_SAVE_FAILURE_MESSAGE
import com.calebc42.ebp.wire.utf16PosIn
import com.calebc42.ebp.renderer.model.ActionHandoff
import com.calebc42.ebp.renderer.model.CandidateDocument
import com.calebc42.ebp.renderer.model.CompletionCandidate
import com.calebc42.ebp.renderer.model.CompletionOffer
import com.calebc42.ebp.renderer.model.EditorAnnotationState
import com.calebc42.ebp.renderer.model.EditorConnectionPhase
import com.calebc42.ebp.renderer.model.EditorEditOutcome
import com.calebc42.ebp.renderer.model.EditorMirror
import com.calebc42.ebp.renderer.model.RendererActionContext
import com.calebc42.ebp.renderer.model.RendererActionOutcome
import com.calebc42.ebp.renderer.model.RendererActionRequest
import com.calebc42.ebp.renderer.model.RendererVolatileSecret
import com.calebc42.ebp.renderer.model.parseDiagnostics
import com.calebc42.ebp.renderer.model.parseEldoc
import com.calebc42.ebp.renderer.model.parseFontify
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread

/** R5: the ONE offer-currency rule — is the published offer's epoch
 * (null when none is published) exactly EPOCH?  Used at gesture
 * admission, doc publication, and desired-pair reissue alike; pure so
 * each site's drop-the-check mutant is killable (review F11 named the
 * gesture arm, and no Compose/bridge test rig exists to kill it in
 * place). */
internal fun offerEpochCurrent(liveEpoch: Long?, epoch: Long): Boolean =
    liveEpoch != null && liveEpoch == epoch

/** CHALLENGED is only a pairing claim; authentication begins at SYNCING. */
internal fun isAuthenticatedConnectionState(state: SessionState): Boolean =
    state == SessionState.SYNCING || state == SessionState.READY

/** Project the wire session into the smaller lifecycle a renderer may trust. */
internal fun editorConnectionPhaseOf(state: SessionState?): EditorConnectionPhase =
    when (state) {
        SessionState.SYNCING -> EditorConnectionPhase.OPENING
        SessionState.READY -> EditorConnectionPhase.READY
        else -> EditorConnectionPhase.OFFLINE
    }

private const val MAX_INPUT_STATE_BYTES = 262_144L

/** A cached surface can be interactive before a replacement engine exists.
 * Every non-closed engine remains the ordering authority, including its
 * pre-auth states; only process-local absence or terminal closure falls back
 * to a direct durable draft commit. */
internal fun shouldCommitVariantOffline(state: SessionState?): Boolean =
    state == null || state == SessionState.CLOSED

/** Serializes the one critical transition for cached receiver-local state:
 * either a local commit finishes before a successor engine is activated (so
 * its welcome sees the draft), or the fully-wired successor is visible and
 * owns the dispatch/syncingDirty ordering.  A candidate is deliberately not
 * exposed by [current]/[withCurrent] while its listeners are being installed.
 * Advancing the accepted generation retires both the live engine and any
 * half-built candidate, so a delayed older serve thread cannot regain the
 * route after a newer socket has superseded it. Ordinary reads stay lock-free. */
internal class VariantEngineRoute<T : Any> {
    @Volatile private var current: T? = null
    private var acceptedGeneration = 0L
    private var candidateGeneration = 0L
    private var candidate: T? = null

    fun current(): T? = current

    fun advanceTo(generation: Long, retire: (T) -> Unit = {}): Boolean =
        synchronized(this) {
            if (generation <= acceptedGeneration) return@synchronized false
            current?.let(retire)
            candidate?.let(retire)
            current = null
            candidate = null
            candidateGeneration = 0L
            acceptedGeneration = generation
            true
        }

    /** Construct under the route monitor so a concurrent generation advance
     * either precedes construction (and rejects it) or follows construction
     * and can retire the candidate before any receiver can dispatch to it. */
    fun prepare(generation: Long, create: () -> T): T? = synchronized(this) {
        if (generation != acceptedGeneration) return@synchronized null
        create().also {
            candidate = it
            candidateGeneration = generation
        }
    }

    /** Publish only the exact candidate for the still-current generation.
     * ON_ACTIVATE runs under the same gate, before UI dispatch can observe it. */
    fun activate(generation: Long, expected: T,
                 onActivate: (T) -> Unit = {}): Boolean = synchronized(this) {
        if (generation != acceptedGeneration ||
            candidateGeneration != generation || candidate !== expected) {
            return@synchronized false
        }
        onActivate(expected)
        candidate = null
        candidateGeneration = 0L
        current = expected
        true
    }

    fun <R> withCurrent(block: (T?) -> R): R = synchronized(this) {
        block(current)
    }
}

internal sealed interface OfflineVariantSwitchResult {
    data class Applied(val id: String, val value: String) : OfflineVariantSwitchResult
    data object NoChange : OfflineVariantSwitchResult
    data object StorageFailed : OfflineVariantSwitchResult
}

/** Receiver-local `variant.switch` path for a restored cached surface. It
 * deliberately performs no wire publication: the committed draft belongs in
 * the next authenticated welcome `input_state`. */
internal fun commitOfflineVariantSwitch(
    store: SurfaceStore,
    surface: String,
    descriptor: JsonObject,
    maxInputStateBytes: Long,
): OfflineVariantSwitchResult {
    val id = descriptor["id"]?.let { (it as? JsonPrimitive)?.content }
        ?: return OfflineVariantSwitchResult.NoChange
    val requested = descriptor["value"]?.let { (it as? JsonPrimitive)?.content }
    val next = store.resolveVariantSwitch(surface, id, requested)
        ?: return OfflineVariantSwitchResult.NoChange
    if (!store.tryPutDraft(surface, id, JsonPrimitive(next), maxInputStateBytes))
        return OfflineVariantSwitchResult.StorageFailed
    return OfflineVariantSwitchResult.Applied(id, next)
}

/**
 * The process-owned connection state shown by the Companion chrome.
 *
 * Session identity matters here: a superseded connection can finish tearing
 * down after its replacement has authenticated.  Only the current session may
 * turn the indicator off, otherwise that late teardown makes a live Emacs look
 * disconnected.  The synchronized pair also keeps the session slot and the
 * published StateFlow one atomic decision.
 */
internal class AuthenticatedConnectionTracker<T : Any> {
    private var current: T? = null
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> get() = _connected

    @Synchronized
    fun authenticated(session: T) {
        current = session
        _connected.value = true
    }

    @Synchronized
    fun disconnected(session: T): Boolean {
        if (current !== session) return false
        current = null
        _connected.value = false
        return true
    }
}

/**
 * Generation-aware READY ownership. Accepting a newer transport ends the old
 * READY authority immediately, even while the replacement is still proving or
 * syncing; delayed teardown from an older socket can never end the successor.
 */
internal class ReadyConnectionTracker<T : Any> {
    private var generation: Long = 0
    private var current: T? = null

    @Synchronized
    fun supersede(newGeneration: Long): Boolean {
        if (newGeneration <= generation) return false
        val disconnected = current != null
        generation = newGeneration
        current = null
        return disconnected
    }

    @Synchronized
    fun ready(atGeneration: Long, session: T): Boolean {
        if (atGeneration != generation) return false
        current = session
        return true
    }

    @Synchronized
    fun disconnected(atGeneration: Long, session: T): Boolean {
        if (atGeneration != generation || current !== session) return false
        current = null
        return true
    }

    @Synchronized
    fun connected(): Boolean = current != null
}

class DeviceBridge(
    private val appContext: android.content.Context,
    private val stores: CompanionStores,
    private val proofProvider: CompanionProofProvider,
    /** SPEC 14.4: the shown surface's ID travels with its spec, so an
     * event names the surface the action actually occurred in. */
    private val onSurfaceChanged: (String, JsonObject?) -> Unit,
    /** A committed widget update/removal fans out to every bound host ID. */
    private val onWidgetSurfaceChanged: (String) -> Unit = {},
    /**
     * SPEC 13.5: null after a session reaches READY; otherwise the durable
     * wall-clock instant at which the current READY authority ended.
     */
    private val onReadyConnectionChanged: suspend (Long?) -> Unit = {},
    /** The durable app-surface cache has finished its initial projection.
     * Nav3 must not invalidate restored Surface keys before this barrier. */
    private val onAppSurfaceCacheLoaded: () -> Unit = {},
    /** SPEC 15.1: storage failure and queue exhaustion MUST reach the
     * user as a visible diagnostic. */
    private val onQueueProblem: (String) -> Unit = {},
    /** SPEC 18.1: (dialog_id, spec) to present; (dialog_id, null) to
     * dismiss. */
    private val onDialogChanged: (String?, JsonObject?) -> Unit = { _, _ -> },
    /** SPEC 18.2: best-effort toast text. */
    private val onToast: (String) -> Unit = {},
    /** SPEC 18.4: the accepted theme payload (`{dark, colors, syntax}`) to
     * mirror, or null for the native scheme. Persisted, so a cached theme is
     * delivered once at start before any session. */
    private val onTheme: (JsonObject?) -> Unit = {},
    /** SPEC 18.3: (menu_id, spec) to present; (menu_id, null) to dismiss. */
    private val onPieMenuChanged: (String, JsonObject?) -> Unit = { _, _ -> },
    /** SPEC 14.2 `surface.open`: select a present app surface in the
     * receiver-owned application shell. */
    private val onOpenSurface: (String) -> Unit = {},
    /** SPEC 14.2 `companion.settings.open`: present the Companion's own
     * settings (R4: the completion-narrowing row is its first tenant). */
    private val onOpenSettings: () -> Unit = {},
) : MaterialRendererHost {

    internal val context: android.content.Context get() = appContext

    // SPEC 13.1/15.1/18.6: the durable stores are process-wide singletons
    // (CompanionStores), shared with cold-started manifest receivers.
    // The composition root initializes these projections from Room on its IO
    // dispatcher before exposing this bridge, so these lookups cannot turn
    // Application.onCreate into a main-thread database read.
    val store by lazy { stores.surfaces() }
    val queue = stores.queue()
    private val reminders = stores.reminders()
    private val triggers = stores.triggers()
    // SPEC 21: the device-lifetime firing service (process-wide). This engine
    // attaches to it as the LiveSession in its constructor; cold receivers and
    // the FGS-owned sources feed it independently of any connection.
    private val firing = stores.firing()
    @Volatile private var current: Socket? = null
    /** Exact engine-to-socket ownership used by password deadline aborts. */
    private val engineTransports = ConcurrentHashMap<CompanionEngine, Socket>()
    private val acceptedConnectionGeneration = AtomicLong(0)
    // Unlike CompanionStores.liveSession (which preserves the existing
    // accept-time routing behavior), this tracker advances only after proof
    // verification. It makes both reconnect prompting and the UI indicator
    // immune to an unauthenticated probe displacing the live-session route.
    private val authenticatedConnection =
        AuthenticatedConnectionTracker<CompanionEngine>()
    private val readyConnection = ReadyConnectionTracker<CompanionEngine>()
    val connected: StateFlow<Boolean> get() = authenticatedConnection.connected
    private val _editorConnectionPhase = MutableStateFlow(EditorConnectionPhase.OFFLINE)
    override val editorConnectionPhase: StateFlow<EditorConnectionPhase>
        get() = _editorConnectionPhase

    private val config = CompanionConfig(
        serverName = "jetpacs-companion",
        serverVersion = "0.1.0-w4",
        pairings = emptyMap(),
        proofProvider = proofProvider,
        supportedCapabilities = setOf("theme", "surfaces.dialog", "presentation.toast",
            "presentation.snackbar",
            "presentation.pie-menu", "reminders.owner", "surfaces.notification",
            "surfaces.widget",
            "editor.sync", "capabilities", "triggers"),
        // SPEC 10.2: what this build's renderer actually honors — derived from
        // the render/NodeSupport registry (the pin test holds the renderer's
        // dispatch to the same sets), never hand-kept here.
        surfaceProfiles = CompanionRenderer.surfaceProfiles(),
        nodeVocabulary = CompanionRenderer.NODE_VOCABULARY,
        // C6: every value below stays INTEGER-spelled. The engine's constructor
        // reads the limits with reqLong, so a `.0` spelling would not merely
        // widen a bound — it would throw at engine construction.
        limits = buildJsonObject {
            put("max_frame_bytes", 4_194_304)
            put("max_queued_events", 256)
            put("max_queued_bytes", 8_388_608)
            put("max_event_bytes", 262_144)
            put("max_surfaces", 64)
            put("max_surface_ids", 4096)
            put("max_field_bytes", 65_536)
            put("max_input_state_bytes", MAX_INPUT_STATE_BYTES)
            put("max_capture_fields", 64)
            put("max_dialogs", 4)
            put("max_pie_menus", 1)
            put("max_reminders", 256)
            put("max_editor_sessions", 8)
            put("max_trigger_responses", 8)
            put("max_triggers", 64)
            put("max_device_report_bytes", 8192)
            // SPEC 4.5/17.2: the three image limits are REQUIRED whenever image
            // is advertised — the same constants the loader enforces (no drift).
            put("max_image_bytes",
                com.calebc42.glasspane.material3.ImageLoader.MAX_IMAGE_BYTES)
            put("max_decoded_image_bytes",
                com.calebc42.glasspane.material3.ImageLoader.MAX_DECODED_IMAGE_BYTES)
            put("max_image_pixels",
                com.calebc42.glasspane.material3.ImageLoader.MAX_IMAGE_PIXELS)
            // SPEC 4.5/17.5: REQUIRED whenever chart/canvas are advertised.
            put("max_chart_points", 4096)
            put("max_canvas_ops", 4096)
            // SPEC 4.5: REQUIRED whenever rich_text/table are advertised —
            // aggregate counts across one SurfaceSpec or dialog (LD-22).
            put("max_rich_spans", 4096)
            put("max_table_cells", 4096)
            // Retained alternatives are bounded independently of the global
            // node count so a sender can size authoring work to this host.
            put("max_variants_per_host",
                com.calebc42.ebp.wire.WireLimits.MAX_VARIANTS_PER_HOST)
            // SPEC 4.5 (amendment #84): REQUIRED when editor.sync is granted.
            // Declared at the floor: it is what keeps every editor path
            // (shadow rebuild, diff, highlight, relayout) comfortably linear.
            put("max_editor_bytes", CompanionStores.MAX_EDITOR_BYTES)
        },
        // SPEC 20.1/20.2: advertise the device report and the platform executor.
        deviceReport = AppCapabilities.deviceReport(),
        capabilityHandler = AppCapabilities.handler(appContext, 65_536),
    )

    private fun loadTheme(): JsonObject? = stores.theme().load()

    private fun saveTheme(payload: JsonObject) = stores.theme().replace(payload)

    internal fun hasReadySession(): Boolean = readyConnection.connected()

    private fun persistReadyConnectionChange(disconnectedAtEpochMs: Long?) {
        try {
            kotlinx.coroutines.runBlocking {
                stores.commandActor.execute {
                    onReadyConnectionChanged(disconnectedAtEpochMs)
                }
            }
        } catch (failure: Throwable) {
            android.util.Log.e(
                "EbpBridge",
                "Could not persist READY connection lifecycle",
                failure,
            )
        }
    }

    private val running = AtomicBoolean(false)
    @Volatile private var listenerServer: ServerSocket? = null
    @Volatile private var listenerThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        listenerThread = thread(name = "ebp-bridge", isDaemon = true) {
        // Deliver the cached theme before any session so a reconnecting device
        // renders in the mirrored palette immediately (§18.4 persistence).
        runCatching { loadTheme()?.let { onTheme(it) } }
        // SPEC 13.5: accepted app surfaces remain present while Emacs is
        // disconnected and across process death. Seed the receiver-owned
        // navigation catalog from the durable store before accepting a new
        // session; a surface.update is not required merely to rediscover it.
        try {
            // Retained presentation choices are ordinary durable drafts, but
            // they have their own narrow UI channel: seed it before projecting
            // cached surfaces so the first composition selects the saved
            // branch rather than flashing the authored default.
            _variantSelections.value = store.variantSelections()
            store.presentSurfaces()
                .asSequence()
                .filter { it.startsWith("app:") }
                .forEach {
                    publishPresentationIncarnations(it)
                    onSurfaceChanged(it, resolveView(it))
                }
        } catch (e: Exception) {
            android.util.Log.e("EbpBridge", "Could not restore cached surfaces", e)
        } finally {
            onAppSurfaceCacheLoaded()
        }

        // The listener is process-lifetime state.  The old implementation
        // wrapped this whole block in one catch: one SocketException from
        // accept() permanently ended `ebp-bridge`, while its unclosed server
        // FD kept port 8765 looking healthy.  New Emacs connections then sat
        // in the kernel backlog forever (awaiting-nonce on the client) with no
        // `ebp-conn` thread to service them.  Own each listener with `use` and
        // recreate it after a transient bind/accept failure instead.
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                ServerSocket().use { server ->
                    listenerServer = server
                    server.reuseAddress = true
                    // SPEC 5.2: bind only a loopback interface.
                    server.bind(InetSocketAddress("127.0.0.1", 8765))
                    while (running.get()) {
                        val socket = server.accept()
                        val generation = acceptedConnectionGeneration.incrementAndGet()
                        if (readyConnection.supersede(generation)) {
                            persistReadyConnectionChange(
                                System.currentTimeMillis().coerceAtLeast(0),
                            )
                        }
                        // SPEC 5.2: one session at a time; the newcomer
                        // supersedes the prior transport immediately. Advance
                        // the receiver-local route first: this closes any
                        // installed engine or half-built candidate under the
                        // same monitor used by offline variant commits.
                        variantEngineRoute.advanceTo(generation) { prior ->
                            prior.close("superseded by newer connection")
                        }
                        // Newest-wins ends the prior READY authority at
                        // accept time, before the replacement authenticates.
                        // Freeze synchronized fields across that entire gap.
                        _editorConnectionPhase.value = EditorConnectionPhase.OFFLINE
                        current?.runCatching { close() }
                        current = socket
                        // Neither may the CONNECTION thread. `serve` sets up
                        // a whole engine before it ever reads, and every line
                        // of that setup runs on a socket a newcomer may already
                        // have closed.
                        thread(name = "ebp-conn", isDaemon = true) {
                            runCatching { serve(socket, generation) }
                                .onFailure { failure ->
                                    if (failure !is SocketException) {
                                        android.util.Log.e(
                                            "EbpBridge",
                                            "Connection setup failed",
                                            failure,
                                        )
                                    }
                                }
                            socket.runCatching { close() }
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@thread
            } catch (e: Exception) {
                if (!running.get()) return@thread
                // Closing the owned ServerSocket above prevents an orphaned
                // bound port. A short retry keeps process startup and a
                // transient accept failure from making reconnect impossible.
                android.util.Log.w("EbpBridge", "Listener restarting", e)
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@thread
                }
            } finally {
                listenerServer = null
            }
        }
        }
    }

    /** Stop the listener and current transport; the FGS owns restart policy. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        listenerServer?.runCatching { close() }
        current?.runCatching { close() }
        listenerThread?.interrupt()
        val generation = acceptedConnectionGeneration.incrementAndGet()
        variantEngineRoute.advanceTo(generation) { engine ->
            engine.close("background bridge disabled")
        }
        listenerThread = null
    }

    private val variantEngineRoute = VariantEngineRoute<CompanionEngine>()
    private val engine: CompanionEngine? get() = variantEngineRoute.current()

    // Renderer, receiver, trigger, and socket entry points all share the same
    // bounded process actor. The Executor facade keeps the renderer host API
    // synchronous without creating a second writer.
    private val dispatchExecutor = Executor { command ->
        if (!stores.submit { command.run() }) {
            onQueueProblem("command actor overloaded")
        }
    }


    // SPEC 14.1 `confirm`: the descriptor asks the Companion to have the user
    // confirm BEFORE the event is created.  Emacs relies on that: an action
    // carrying `confirm` never prompts on its side, because prompting inside
    // the dispatch extent is forbidden — so an unimplemented `confirm` turns
    // a guarded destructive verb into an immediate one.  (The JA-6 device
    // gate found exactly that: the member lived in the vocabulary and the
    // validator, and no chrome ever presented it, so a Delete tap deleted.)
    // The dispatch is PARKED here; `resolveConfirm' releases or drops it.
    data class PendingConfirm(
        val prompt: String, val surface: String, val descriptor: JsonObject,
        val value: JsonElement?, val injected: JsonObject?, val fields: JsonObject?,
        val secret: RendererVolatileSecret?,
        val sourceId: String?,
        val onOutcome: (RendererActionOutcome) -> Unit,
        /** Identity only; keeps the internal deadline type out of UI state. */
        val secretAttemptToken: Any? = null,
        // §14.1: the object form's optional face — a bare-string confirm
        // leaves all four null and the host draws what it always drew.
        val title: String? = null, val icon: String? = null,
        val confirmLabel: String? = null, val dismissLabel: String? = null)

    private val _pendingConfirm = MutableStateFlow<PendingConfirm?>(null)
    val pendingConfirm: StateFlow<PendingConfirm?> get() = _pendingConfirm

    /** Release (RUN true) or drop the parked confirmation. */
    fun resolveConfirm(run: Boolean) {
        val p = _pendingConfirm.value ?: return
        _pendingConfirm.value = null
        if (run) dispatch(
            p.surface, p.descriptor, p.value, p.injected, p.fields,
            p.secret, p.sourceId,
            p.secretAttemptToken as? SecretActionAttempt,
            p.onOutcome,
        ) else p.onOutcome(RendererActionOutcome.NotAdmitted(
            com.calebc42.ebp.wire.UnsafeAdmissionReason.Cancelled))
    }

    /** Park when DESCRIPTOR carries `confirm`; true when parked. */
    private fun parkIfConfirmed(surface: String, descriptor: JsonObject,
                                value: JsonElement?, injected: JsonObject?,
                                fields: JsonObject?, secret: RendererVolatileSecret?,
                                sourceId: String?, secretAttempt: SecretActionAttempt?,
                                onOutcome: (RendererActionOutcome) -> Unit): Boolean {
        // §14.1: `confirm` is a bare string, or the object form
        // {text, title?, icon?, confirm_label?, dismiss_label?}.
        val obj = descriptor.objOrNull("confirm")
        val prompt = obj?.stringOr("text") ?: descriptor.stringOr("confirm")
        if (prompt.isEmpty()) return false
        // Preserve the first parked occurrence. Accessibility or a synthetic
        // input source can still race the modal even when touch cannot; such a
        // second handoff must conclude rather than overwrite the first one's
        // only terminal callback.
        if (_pendingConfirm.value != null) {
            onOutcome(
                RendererActionOutcome.NotAdmitted(
                    com.calebc42.ebp.wire.UnsafeAdmissionReason.Overloaded,
                ),
            )
            return true
        }
        _pendingConfirm.value = PendingConfirm(
            prompt, surface, descriptor, value, injected, fields, secret,
            sourceId, onOutcome, secretAttempt,
            title = obj?.stringOr("title")?.takeIf { it.isNotEmpty() },
            icon = obj?.stringOr("icon")?.takeIf { it.isNotEmpty() },
            confirmLabel = obj?.stringOr("confirm_label")?.takeIf { it.isNotEmpty() },
            dismissLabel = obj?.stringOr("dismiss_label")?.takeIf { it.isNotEmpty() })
        return true
    }

    private fun dispatch(surface: String, descriptor: JsonObject, value: JsonElement?,
                         injected: JsonObject?, fields: JsonObject?,
                         secret: RendererVolatileSecret?, sourceId: String?,
                         secretAttempt: SecretActionAttempt?,
                         onOutcome: (RendererActionOutcome) -> Unit) {
        dispatchExecutor.execute {
            if (secret != null) {
                if (secretAttempt?.isPending() != true) return@execute
                val captured = secret.fieldsOrNull() ?: return@execute
                val activeEngine = secretAttempt.engine
                try {
                    if (surface.startsWith("dialog:")) {
                        activeEngine?.dispatchDialogSecretAction(
                            surface.removePrefix("dialog:"),
                            descriptor,
                            captured,
                            secret.secretIds,
                            sourceId,
                        ) { outcome -> onOutcome(renderOutcome(outcome)) }
                            ?: onOutcome(rendererNotReadyOutcome())
                    } else {
                        activeEngine?.dispatchSecretAction(
                            surface,
                            descriptor,
                            captured,
                            secret.secretIds,
                            sourceId,
                        ) { outcome -> onOutcome(renderOutcome(outcome)) }
                            ?: onOutcome(rendererNotReadyOutcome())
                    }
                } finally {
                    secretAttempt.releaseAfterDispatch()
                }
                return@execute
            }
            val traceVariant = descriptor.stringOr("builtin") == "variant.switch"
            if (traceVariant) android.os.Trace.beginSection("EBP variant.switch")
            try {
                if (traceVariant && !surface.startsWith("dialog:")) {
                    variantEngineRoute.withCurrent { activeEngine ->
                        // Reading CLOSED under the engine monitor also makes
                        // the old reader's terminal work happen-before the
                        // direct SurfaceStore mutation.
                        val activeState = activeEngine?.let {
                            synchronized(it) { it.state }
                        }
                        if (shouldCommitVariantOffline(activeState)) {
                            when (val result = commitOfflineVariantSwitch(
                                store, surface, descriptor,
                                MAX_INPUT_STATE_BYTES)) {
                                is OfflineVariantSwitchResult.Applied ->
                                    _variantSelections.update { selections ->
                                        selections +
                                            ((surface to result.id) to result.value)
                                    }.also {
                                        deliverOutcome(onOutcome,
                                            RendererActionOutcome.LocallyCompleted)
                                    }
                                OfflineVariantSwitchResult.NoChange ->
                                    deliverOutcome(onOutcome,
                                        RendererActionOutcome.LocallyCompleted)
                                OfflineVariantSwitchResult.StorageFailed -> {
                                    onQueueProblem(VARIANT_SAVE_FAILURE_MESSAGE)
                                    deliverOutcome(onOutcome,
                                        RendererActionOutcome.NotAdmitted(
                                            com.calebc42.ebp.wire.UnsafeAdmissionReason.StorageFailed))
                                }
                            }
                        } else {
                            // CONNECTED/CHALLENGED/SYNCING/READY all use the
                            // engine so a pre-READY occurrence is recorded in
                            // syncingDirty and READY ordering stays intact.
                            activeEngine?.dispatchAction(
                                surface, descriptor, value, injected, sourceId,
                            ) { outcome -> completeOutcome(onOutcome, outcome) }
                                ?: deliverOutcome(onOutcome,
                                    rendererNotReadyOutcome())
                        }
                    }
                    return@execute
                }
                val activeEngine = engine
                // SPEC 18.1/14.4: a `dialog:` context dispatches in DIALOG
                // context — dialog_id, no surface/revision.  The generic path
                // resolved a revision for the pseudo-surface, got null, and
                // silently dropped every remote descriptor inside a dialog
                // (found by the JA-5 device gate).  Routed HERE so a parked
                // confirmation resumes through the same fork.
                if (surface.startsWith("dialog:"))
                    activeEngine?.dispatchDialogAction(
                        surface.removePrefix("dialog:"), descriptor, value,
                        fields, sourceId,
                    ) { outcome -> completeOutcome(onOutcome, outcome) }
                        ?: deliverOutcome(onOutcome, rendererNotReadyOutcome())
                else activeEngine?.dispatchAction(
                    surface, descriptor, value, injected, sourceId,
                ) { outcome -> completeOutcome(onOutcome, outcome) }
                    ?: deliverOutcome(onOutcome, rendererNotReadyOutcome())
            } finally {
                if (traceVariant) android.os.Trace.endSection()
            }
        }
    }

    /** Renderer occurrence -> the one confirmation/admission/delivery path. */
    override fun dispatch(
        request: RendererActionRequest,
        onOutcome: (RendererActionOutcome) -> Unit,
    ): ActionHandoff {
        val surface = when (val context = request.context) {
            is RendererActionContext.Surface -> context.surface
            is RendererActionContext.Dialog -> "dialog:${context.dialogId}"
        }
        val secret = request.secret
        val secretAttempt = secret?.let { capture ->
            val boundEngine = engine
            SecretActionAttempt(
                engine = boundEngine,
                capture = capture,
                scheduler = SystemSecretDeadlineScheduler,
                abortTransport = ::abortSecretTransport,
                beforeTimeout = { attempt ->
                    if (_pendingConfirm.value?.secretAttemptToken === attempt) {
                        _pendingConfirm.value = null
                    }
                },
                terminal = { outcome ->
                    deliverOutcome(
                        callback = { delivered ->
                            capture.erase()
                            onOutcome(delivered)
                        },
                        outcome = outcome,
                    )
                },
            )
        }
        val oneShot: (RendererActionOutcome) -> Unit = if (secretAttempt != null) {
            secretAttempt::complete
        } else {
            RendererOutcomeGate(onOutcome)::complete
        }
        val invalidUntypedFields =
            request.context is RendererActionContext.Surface &&
                request.fields?.isNotEmpty() == true
        if (invalidUntypedFields || (request.fields != null && secret != null)) {
            val invalid = RendererActionOutcome.NotAdmitted(
                com.calebc42.ebp.wire.UnsafeAdmissionReason.ContentInvalid,
            )
            if (secretAttempt != null) oneShot(invalid)
            else deliverOutcome(oneShot, invalid)
            return ActionHandoff.HandedOff
        }
        if (parkIfConfirmed(
                surface, request.descriptor, request.value, request.injected,
                request.fields, secret, request.sourceId, secretAttempt, oneShot,
            )) return ActionHandoff.HandedOff
        dispatch(
            surface, request.descriptor, request.value, request.injected,
            request.fields, secret, request.sourceId, secretAttempt, oneShot,
        )
        return ActionHandoff.HandedOff
    }

    /** Close only the transport that owned the expiring secret occurrence. */
    private fun abortSecretTransport(boundEngine: CompanionEngine?) {
        if (boundEngine == null) return
        boundEngine.runCatching { close("password submission deadline") }
        engineTransports.remove(boundEngine)?.runCatching { close() }
    }

    /** SPEC 14.6: renderer edit -> draft + state.changed publication. */
    override fun publishState(surface: String, id: String, value: JsonElement?, caret: Int?) {
        dispatchExecutor.execute { engine?.publishState(surface, id, value, caret) }
    }

    private fun completeOutcome(
        callback: (RendererActionOutcome) -> Unit,
        outcome: ActionAdmissionOutcome,
    ) {
        deliverOutcome(callback, renderOutcome(outcome))
    }

    /** Convert at the session thread so a pre-deadline result cancels on time. */
    private fun renderOutcome(outcome: ActionAdmissionOutcome): RendererActionOutcome {
        val rendered = when (outcome) {
            is ActionAdmissionOutcome.SafelyAdmitted ->
                RendererActionOutcome.SafelyAdmitted(outcome.evidence)
            is ActionAdmissionOutcome.NotAdmitted ->
                RendererActionOutcome.NotAdmitted(outcome.reason, outcome.error)
            ActionAdmissionOutcome.LocallyCompleted ->
                RendererActionOutcome.LocallyCompleted
        }
        if (rendered is RendererActionOutcome.NotAdmitted)
            rendered.error?.let {
                onQueueProblem(it.stringOr("message", "queue error"))
            }
        return rendered
    }

    private fun deliverOutcome(
        callback: (RendererActionOutcome) -> Unit,
        outcome: RendererActionOutcome,
    ) {
        appContext.mainExecutor.execute { callback(outcome) }
    }

    private fun rendererNotReadyOutcome() = RendererActionOutcome.NotAdmitted(
        com.calebc42.ebp.wire.UnsafeAdmissionReason.NotReady,
    )

    // A tiny scope for the fire-and-forget cache bind (SPEC 17.2 identity
    // scoping); the cache owns its own IO scope for the fetches themselves.
    private val cacheScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    // T3/LD-2: display generations for stateful nodes, keyed (surface, id).
    // Republished with every accepted snapshot; a widget's remember key
    // carries its epoch, so a value the SNAPSHOT decided reseeds the widget
    // while a value the user is still editing does not.
    private val _inputDisplays =
        MutableStateFlow<Map<Pair<String, String>, InputDisplay>>(emptyMap())
    override val inputDisplays: StateFlow<Map<Pair<String, String>, InputDisplay>>
        get() = _inputDisplays

    override val maxFieldBytes: Int = CompanionStores.MAX_FIELD_BYTES.toInt()
    override val maxEditorBytes: Int = CompanionStores.MAX_EDITOR_BYTES.toInt()

    // A variant switch changes which already-authored branch is placed. Keep
    // it out of the root inputDisplays subscription: invalidating the whole
    // surface would defeat the retained-presentation fast path.
    private val _variantSelections =
        MutableStateFlow<Map<Pair<String, String>, String>>(emptyMap())
    override val variantSelections: StateFlow<Map<Pair<String, String>, String>>
        get() = _variantSelections

    // Acceptance-time presentation incarnations. SurfaceSpec projection is a
    // conflated StateFlow, so remove -> byte-identical re-add may skip the
    // null on the UI thread. These latest-value tokens still change and force
    // both the surface root and re-added retained owners to start fresh.
    private val retainedPresentationTracker =
        RetainedPresentationIncarnationTracker()
    private val _surfacePresentationIncarnations =
        MutableStateFlow<Map<String, Any>>(emptyMap())
    val surfacePresentationIncarnations: StateFlow<Map<String, Any>>
        get() = _surfacePresentationIncarnations
    private val _retainedPresentationIncarnations =
        MutableStateFlow<Map<Pair<String, String>, Long>>(emptyMap())
    override val retainedPresentationIncarnations:
        StateFlow<Map<Pair<String, String>, Long>>
        get() = _retainedPresentationIncarnations

    /** Project from the complete stored spec, not resolveView(current): a
     * local view switch must not retire presentation identities that remain
     * authored in another view of the same accepted surface. */
    private fun publishPresentationIncarnations(surface: String) {
        val projection = retainedPresentationTracker.accept(
            surface,
            store.revisionOf(surface),
            store.spec(surface),
        )
        _surfacePresentationIncarnations.value = projection.surfaceRoots
        _retainedPresentationIncarnations.value = projection.saveableOwners
    }

    // T2/LD-5: the editor mirrors, keyed (document, editor_id). RenderEditor
    // collects this and adopts on epoch change; see EditorMirror above.
    private val mirrorEpoch = AtomicLong(0)
    private val _editorMirrors =
        MutableStateFlow<Map<Pair<String, String>, EditorMirror>>(emptyMap())
    override val editorMirrors: StateFlow<Map<Pair<String, String>, EditorMirror>>
        get() = _editorMirrors
    private val retainedEditorSeeds =
        ConcurrentHashMap<Pair<String, String>, EditorSeed>()

    /** Copy one serialized session into the process-volatile reconnect seed. */
    private fun rememberEditorSeed(s: EditorSession) {
        retainedEditorSeeds[s.document to s.editorId] = EditorSeed(
            s.shadow,
            ScalarPos(s.cursor),
            ScalarPos(s.selStart),
            ScalarPos(s.selEnd),
        )
    }

    /** Publish S as the display's text authority. Caret converts scalar ->
     * UTF-16 against the shadow through the one named conversion pair.
     * Called under the engine monitor (editorListener) or inside a
     * withEditor block — session reads are never torn. */
    private fun publishMirror(s: EditorSession) {
        val text = s.shadow
        rememberEditorSeed(s)
        val m = EditorMirror(
            text,
            utf16PosIn(text, ScalarPos(s.cursor)).v,
            utf16PosIn(text, ScalarPos(s.selStart)).v,
            utf16PosIn(text, ScalarPos(s.selEnd)).v,
            s.seq, mirrorEpoch.incrementAndGet())
        _editorMirrors.value =
            _editorMirrors.value + ((s.document to s.editorId) to m)
    }

    /** Return the latest surviving displayed session seed for reconnect. */
    private fun retainedEditorSeed(document: String, editorId: String): EditorSeed? =
        retainedEditorSeeds[document to editorId]

    /** Prune volatile display state that no accepted editor still owns. */
    private fun retainLiveEditors(keys: Set<Pair<String, String>>) {
        _editorMirrors.update { mirrors -> mirrors.filterKeys(keys::contains) }
        _editorAnnotations.update { values -> values.filterKeys(keys::contains) }
        _completionOffers.update { values -> values.filterKeys(keys::contains) }
        _offerViews.update { values -> values.filterKeys(keys::contains) }
        _candidateDocs.update { values -> values.filterKeys(keys::contains) }
        retainedEditorSeeds.keys.filter { it !in keys }.forEach(retainedEditorSeeds::remove)
        candidateDocSlots.keys.filter { it !in keys }.forEach { key ->
            candidateDocSlots.remove(key)?.retire()
        }
    }

    // SPEC 19.5: annotations, keyed (document, editor_id) exactly like the
    // mirrors beside them — which is why the wire listener carries the
    // document at all. RenderEditor collects this and rebuilds its
    // transformation on the epoch.
    private val annotationEpoch = AtomicLong(0)
    private val _editorAnnotations =
        MutableStateFlow<Map<Pair<String, String>, EditorAnnotationState>>(emptyMap())
    override val editorAnnotations: StateFlow<Map<Pair<String, String>, EditorAnnotationState>>
        get() = _editorAnnotations

    /**
     * Absorb one validated §19.5 batch. The engine has already refused
     * anything whose session or seq does not match the live editor and
     * range-checked every entry, so the remaining work is the domain change:
     * scalar offsets to UTF-16 against the SHADOW those offsets index.
     *
     * The shadow is read from the engine rather than from the mirror map,
     * because a mirror is only published when the text CHANGES — an editor
     * that has been open and idle has no mirror entry at all, and that is
     * exactly the state a first fontify push arrives in.
     */
    private fun absorbAnnotation(
        method: String, document: String, editorId: String, params: JsonObject,
    ) {
        val e = engine ?: return
        val text = e.withEditor(document, editorId) { it.shadow } ?: return
        val key = document to editorId
        val prior = _editorAnnotations.value[key] ?: EditorAnnotationState()
        val next = when (method) {
            "fontify.show" -> prior.copy(fontify = parseFontify(params, text))
            "diagnostics.show" -> prior.copy(
                diagnostics = parseDiagnostics(params, text),
            )
            "eldoc.show" -> prior.copy(eldoc = parseEldoc(params))
            else -> return
        }
        _editorAnnotations.value = _editorAnnotations.value +
            (key to next.copy(epoch = annotationEpoch.incrementAndGet()))
    }

    /** Drop every display-side trace of one editor. The engine's close hook is
     * the only event that ends a session short of transport loss. */
    private fun forgetEditor(document: String, editorId: String) {
        val key = document to editorId
        _editorMirrors.update { it - key }
        _completionOffers.update { it - key }
        _offerViews.update { it - key }
        _editorAnnotations.update { it - key }
        retainedEditorSeeds.remove(key)
        // The editor is gone for good: REMOVE the slot instance (the
        // in-place retire is for offer death under a live editor). A
        // conclusion still in flight lands on its captured instance,
        // now orphaned — harmless by construction.
        candidateDocSlots.remove(key)?.retire()
        _candidateDocs.update { it - key }
    }

    // SPEC 19.3 (JC-4b): completion offers, keyed (document, editor_id).
    // RenderEditor collects this and shows a dropdown; an offer is REPLACED
    // by the next one and cleared when its editor's text moves, so a stale
    // candidate list can never be tapped.
    private val offerEpoch = AtomicLong(0)
    private val _completionOffers =
        MutableStateFlow<Map<Pair<String, String>, CompletionOffer>>(emptyMap())
    override val completionOffers: StateFlow<Map<Pair<String, String>, CompletionOffer>>
        get() = _completionOffers

    /** SPEC 19.3: ask Emacs to complete at the caret. The (session, seq,
     * cursor) the request was issued against ride along so a later selection
     * is validated against THAT state, not against whatever the engine holds
     * when the user finally taps. */
    override fun requestEditorCompletion(document: String, editorId: String) {
        dispatchExecutor.execute {
            engine?.requestCompletion(document, editorId) {
                prefix, cands, session, seq, cursor ->
                val list = cands.mapNotNull { e ->
                    (e as? JsonObject)?.let { c ->
                        CompletionCandidate(
                            c.stringOr("label"),
                            c.stringOr("annotation").takeIf { it.isNotEmpty() },
                            // SPEC 19.3: `insert` defaults to `label` on
                            // ABSENCE only — an explicit "" is a selection
                            // that DELETES the prefix, and rewriting it to
                            // the label emitted a wrong edit on accept.
                            if ("insert" in c) c.stringOr("insert")
                            else c.stringOr("label"),
                            c.stringOr("kind").takeIf { it.isNotEmpty() })
                    }
                }
                // The engine invokes this only when the tracker armed, so
                // candidates and view publish as ONE event: rows shown are
                // rows the tracker will validate.
                _completionOffers.update {
                    it + ((document to editorId) to CompletionOffer(
                        prefix, list, session, seq, cursor,
                        offerEpoch.incrementAndGet()))
                }
                _offerViews.update {
                    it + ((document to editorId) to
                        CompletionOfferView(prefix, "", true))
                }
                // R5: a fresh offer is a new epoch — any published doc and
                // any in-flight fetch belong to the one it replaced.
                retireCandidateDoc(document to editorId)
            }
        }
    }

    /** SPEC 19.3: accept a candidate — a local edit replacing the prefix.
     * The engine refuses a selection whose session/seq/cursor have moved,
     * so a stale tap is a no-op rather than a wrong edit (SPEC 19.2). */
    override fun selectEditorCompletion(
        document: String,
        editorId: String,
        label: String,
        insert: String,
    ) {
        clearCompletions(document, editorId)
        dispatchExecutor.execute {
            val e = engine ?: return@execute
            // Amendments #170/#171: the engine validates membership and the
            // emission-time re-proof against the ACTIVE predicate, then
            // emits the accept-stamped delta through the funnel.
            e.selectCompletion(document, editorId, label, insert,
                completionNarrowing)
            // The mirror republishes on BOTH arms (R5 device gate finding,
            // pre-existing since JC-4b): a successful accept's splice
            // originates in the ENGINE, not the field — localEditorEdit
            // fires no display listener for local edits — so without this
            // the field keeps the PRE-TAP text until the next keystroke
            // bounces off the amendment-#100 stale-base gate and snaps
            // back, swallowing that keystroke. The engine caret already
            // follows the insertion (EditorSession.splice, Emacs's
            // SET_PT), so the adopted TextFieldValue lands complete.
            e.withEditor(document, editorId) { publishMirror(it) }
            publishOfferViewFor(document, editorId)
        }
    }

    /** Amendment #171: the ACTIVE narrowing predicate - receiver-local
     * presentation, user-settable, persisted; strict is the reference
     * default. No wire member anywhere: Emacs cannot tell the policies
     * apart, which is what made this a setting instead of a schism. */
    override var completionNarrowing: CompletionNarrowing =
        if (appContext.getSharedPreferences("ebp", android.content.Context.MODE_PRIVATE)
                .getString("completion_narrowing", "strict") == "contains")
            CompletionNarrowing.CONTAINS else CompletionNarrowing.STRICT
        set(value) {
            field = value
            appContext.getSharedPreferences("ebp", android.content.Context.MODE_PRIVATE)
                .edit().putString("completion_narrowing",
                    if (value == CompletionNarrowing.CONTAINS) "contains"
                    else "strict").apply()
        }

    // Amendment #171 (R4 review): the tracker's render-facing view as
    // OBSERVED state, keyed like the offers beside it. The composition
    // reads THIS — never the engine — for two structural reasons: the
    // engine monitor is held across blocking socket writes, so a
    // per-keystroke or per-recomposition @Synchronized read can stall the
    // main thread behind the transport; and a synchronous read races the
    // dispatch executor, showing pre-splice tracker state (a killed
    // offer's dropdown ghosting until the next reply). Published at every
    // tracker mutation: armed in the completion callback, refreshed after
    // every local splice ON the executor (ordered after the splice), and
    // by the editor listener for engine-internal advances (edit.apply,
    // edit.resync).
    private val _offerViews =
        MutableStateFlow<Map<Pair<String, String>, CompletionOfferView>>(emptyMap())
    override val completionOfferViews:
        StateFlow<Map<Pair<String, String>, CompletionOfferView>>
        get() = _offerViews

    /** Publish VIEW for KEY; a dead tracker retires the candidates with
     * it — the reconciliation that used to run synchronously in the
     * renderer, now ordered after the mutation it reflects. */
    private fun publishOfferView(key: Pair<String, String>,
                                 view: CompletionOfferView?) {
        if (view == null || !view.active) {
            _offerViews.update { it - key }
            _completionOffers.update { it - key }
            retireCandidateDoc(key)
        } else _offerViews.update { it + (key to view) }
    }

    /** Read the live tracker (off the main thread) and publish it. */
    private fun publishOfferViewFor(document: String, editorId: String) {
        publishOfferView(document to editorId,
            engine?.completionOfferView(document, editorId))
    }

    /** Drop any offer for this editor: the caret moved, or one was taken. */
    fun clearCompletions(document: String, editorId: String) {
        _completionOffers.update { it - (document to editorId) }
        _offerViews.update { it - (document to editorId) }
        retireCandidateDoc(document to editorId)
    }

    // SPEC 19.3 (amendment #172, R5): lazy candidate documentation. The
    // published doc is OBSERVED state like the offer views beside it;
    // the composition never reads the engine. One outstanding request
    // per editor (the SPEC's SHOULD) via CandidateDocSlot's latest-wins
    // arm; the frozen (session, seq) comparand comes from the PUBLISHED
    // offer — the engine tracker has no session field and its
    // expectedSeq mutates per qualifying splice, so only the offer holds
    // the retained reply's pair.
    private val candidateDocSlots =
        ConcurrentHashMap<Pair<String, String>, CandidateDocSlot>()
    private val _candidateDocs =
        MutableStateFlow<Map<Pair<String, String>, CandidateDocument>>(emptyMap())
    override val candidateDocuments:
        StateFlow<Map<Pair<String, String>, CandidateDocument>>
        get() = _candidateDocs

    /** A row was long-pressed. EPOCH is the epoch of the offer the row
     * was COMPOSED from, not whatever is published when the executor
     * runs: offer publication happens on the reader thread under the
     * engine monitor, so a fresh reply can land between the gesture and
     * this task — pairing the NEW offer's frozen (session, seq) with the
     * OLD index would pass every staleness gate on both endpoints and
     * document a candidate the user never highlighted (R5 review F11). */
    override fun requestCandidateDocument(
        document: String,
        editorId: String,
        index: Int,
        epoch: Long,
    ) {
        dispatchExecutor.execute {
            val key = document to editorId
            val offer = _completionOffers.value[key] ?: return@execute
            if (!offerEpochCurrent(offer.epoch, epoch)) return@execute
            val slot = candidateDocSlots.getOrPut(key) { CandidateDocSlot() }
            // Latest wins: null means the desired pair was recorded
            // behind the in-flight one and issues at its conclusion.
            val flight = slot.request(epoch, index) ?: return@execute
            issueCandidateDoc(key, offer, slot, flight)
        }
    }

    /** Issue one flight against SLOT — the INSTANCE the flight was
     * minted from, threaded through rather than re-resolved by key at
     * conclusion time (R5 review): per-instance tickets restart at 1,
     * so a stale conclusion for a discarded instance would otherwise
     * match a FRESH instance's first flight and disown it mid-air.
     * The conclusion (reader thread) publishes into a LIVE offer only —
     * re-checked at publish time, not just slot identity. */
    private fun issueCandidateDoc(key: Pair<String, String>,
                                  offer: CompletionOffer,
                                  slot: CandidateDocSlot,
                                  flight: CandidateDocSlot.Flight) {
        val e = engine
        val sent = e != null && e.requestCandidateDoc(
            key.first, key.second, offer.session, offer.sequence, flight.index) { doc ->
            // EVERY conclusion lands here — a doc, an explicit "", or
            // null for error/malformed/discarded (the engine split
            // conclusion from publication so a single 1201 can never
            // wedge this slot).
            if (doc != null) {
                val live = _completionOffers.value[key]
                if (offerEpochCurrent(live?.epoch, flight.epoch))
                    _candidateDocs.update {
                        it + (key to CandidateDocument(flight.index, doc,
                            flight.epoch))
                    }
            }
            concludeAndPump(key, slot, flight.ticket)
        }
        // Gate refusal (no engine, editor not OPEN, session not READY):
        // no conclusion will ever arrive for this flight — conclude it
        // here so the slot frees and any desired pair drains.
        if (!sent) concludeAndPump(key, slot, flight.ticket)
    }

    /** Conclude TICKET on SLOT, then issue whatever desired pair still
     * names the live offer — freeing dead ones as they surface, so no
     * armed flight is ever left unowned. */
    private fun concludeAndPump(key: Pair<String, String>,
                                slot: CandidateDocSlot, ticket: Long) {
        var next = slot.concluded(ticket)
        while (next != null) {
            val live = _completionOffers.value[key]
            if (live != null && offerEpochCurrent(live.epoch, next.epoch)) {
                issueCandidateDoc(key, live, slot, next)
                return
            }
            next = slot.concluded(next.ticket)
        }
    }

    /** Docs retire wherever the offer dies. The slot retires IN PLACE —
     * the instance stays in the map so an in-flight request keeps
     * occupying it and a long-press on the fresh offer QUEUES behind
     * the old flight's conclusion instead of double-issuing (the
     * one-outstanding SHOULD, R5 review); only [forgetEditor] and the
     * serve() teardown remove instances. */
    private fun retireCandidateDoc(key: Pair<String, String>) {
        candidateDocSlots[key]?.retire()
        _candidateDocs.update { it - key }
    }

    /** SPEC 19.3: a synchronized editor's local edit -> shadow + edit.delta.
     * BASE is the view's pre-edit text: the engine refuses a splice derived
     * from a superseded document (amendment #100), which is what makes a
     * keystroke racing an inbound edit.apply safe. Any refusal — stale base,
     * no OPEN session in READY, or past max_editor_bytes ("as if the editor
     * were read-only", SPEC 19.4) — publishes the unchanged shadow so the
     * field snaps back instead of silently diverging. */
    override fun publishEditorEdit(
        document: String,
        editorId: String,
        start: ScalarPos,
        deletedScalars: Int,
        inserted: String,
        base: String,
        onOutcome: (EditorEditOutcome) -> Unit,
    ) {
        dispatchExecutor.execute {
            val outcome = runCatching {
                val e = engine ?: return@runCatching EditorEditOutcome.CLOSED
                if (e.localEditorEdit(
                        document,
                        editorId,
                        start,
                        deletedScalars,
                        inserted,
                        base,
                    )
                ) {
                    e.withEditor(document, editorId, ::rememberEditorSeed)
                    EditorEditOutcome.ACCEPTED
                } else if (e.withEditor(document, editorId) { publishMirror(it) } != null) {
                    EditorEditOutcome.RECONCILE
                } else {
                    EditorEditOutcome.CLOSED
                }
            }.getOrDefault(EditorEditOutcome.CLOSED)
            if (outcome != EditorEditOutcome.CLOSED) {
                // Amendment #171: the splice extended or killed the tracker;
                // publish what it decided on the same serial executor.
                publishOfferViewFor(document, editorId)
            }
            appContext.mainExecutor.execute { onOutcome(outcome) }
        }
    }

    override fun publishEditorComposition(
        document: String,
        editorId: String,
        active: Boolean,
    ) {
        dispatchExecutor.execute {
            engine?.setEditorComposing(document, editorId, active)
        }
    }

    // SPEC 19.3: the last caret REPORTED per editor, so a repeat costs
    // nothing. The spec puts throttling on the Companion, at the source, and
    // says an intermediate position never emitted is not §22.2 conflation —
    // a position already emitted is not a new one at all.
    private val lastCaret = ConcurrentHashMap<Pair<String, String>, Triple<Int, Int, Int>>()

    /** SPEC 19.3: report the caret for a synchronized editor. Best-effort
     * presentation context — never a text change — and the one signal that
     * lets an Emacs-side rider answer a POSITION rather than an edit: without
     * it every caret-keyed feature (eldoc, diagnostics targeting, completion
     * context) sees offset 0 for the life of the session. Positions are
     * Compose UTF-16; the engine converts against the shadow (LD-4). */
    override fun publishEditorCaret(
        document: String,
        editorId: String,
        cursorUtf16: Int,
        selectionStartUtf16: Int,
        selectionEndUtf16: Int,
    ) {
        val key = document to editorId
        val next = Triple(cursorUtf16, selectionStartUtf16, selectionEndUtf16)
        if (lastCaret.put(key, next) == next) return
        dispatchExecutor.execute {
            val e = engine ?: return@execute
            e.localEditorCaret(
                document,
                editorId,
                Utf16Pos(cursorUtf16),
                Utf16Pos(selectionStartUtf16),
                Utf16Pos(selectionEndUtf16),
            )
            e.withEditor(document, editorId, ::rememberEditorSeed)
        }
    }

    /** SPEC 17.7: a toolbar `command` -> non-durable edit.command
     * event.action. Positions are Compose UTF-16; the engine converts to
     * scalars against the shadow (LD-4). */
    override fun dispatchEditorCommand(
        surface: String,
        document: String,
        editorId: String,
        command: String,
        cursorUtf16: Int,
        selectionStartUtf16: Int,
        selectionEndUtf16: Int,
    ) {
        dispatchExecutor.execute {
            engine?.editorCommand(surface, document, editorId, command,
                Utf16Pos(cursorUtf16), Utf16Pos(selectionStartUtf16),
                Utf16Pos(selectionEndUtf16))
        }
    }

    /** SPEC 18.1: conclude only after the dialog response reaches the sink. */
    override fun submitDialog(
        dialogId: String,
        value: JsonElement?,
        fields: JsonObject,
        secret: RendererVolatileSecret?,
        onOutcome: (RendererActionOutcome) -> Unit,
    ): ActionHandoff {
        val boundEngine = engine
        val secretAttempt = secret?.let { capture ->
            SecretActionAttempt(
                engine = boundEngine,
                capture = capture,
                scheduler = SystemSecretDeadlineScheduler,
                abortTransport = ::abortSecretTransport,
                terminal = { outcome ->
                    deliverOutcome(
                        callback = { delivered ->
                            capture.erase()
                            onOutcome(delivered)
                        },
                        outcome = outcome,
                    )
                },
            )
        }
        val oneShot: (RendererActionOutcome) -> Unit = secretAttempt?.let {
            it::complete
        } ?: RendererOutcomeGate(onOutcome)::complete
        dispatchExecutor.execute {
            if (secret != null) {
                if (secretAttempt?.isPending() != true) return@execute
                val captured = secret.fieldsOrNull() ?: return@execute
                try {
                    boundEngine?.completeDialogSecretSubmit(
                        dialogId,
                        value,
                        captured,
                        secret.secretIds,
                    ) { outcome -> oneShot(renderOutcome(outcome)) }
                        ?: oneShot(rendererNotReadyOutcome())
                } finally {
                    secretAttempt.releaseAfterDispatch()
                }
            } else {
                boundEngine?.completeDialogSubmit(dialogId, value, fields) { outcome ->
                    completeOutcome(oneShot, outcome)
                } ?: deliverOutcome(oneShot, rendererNotReadyOutcome())
            }
        }
        return ActionHandoff.HandedOff
    }

    /** SPEC 14.1/18.1 (T3/LD-3): the authored values the engine computed for
     * this dialog's stateful nodes, so an untouched field captures its
     * logical value. Read once when the dialog is presented. */
    override fun dialogDefaults(dialogId: String): JsonObject? =
        engine?.dialogDefaults(dialogId)

    /** SPEC 18.1: dialog.dismiss builtin / platform dismissal. */
    override fun dismissDialog(dialogId: String) {
        dispatchExecutor.execute { engine?.completeDialogDismiss(dialogId) }
    }

    /** SPEC 18.3: a radial selection with its zero-based indices. */
    override fun pieMenuSelect(menuId: String, categoryIndex: Int, itemIndex: Int?) {
        dispatchExecutor.execute { engine?.selectPieMenu(menuId, categoryIndex, itemIndex) }
    }

    /** SPEC 18.3: dismiss the pie menu on an outside tap. */
    override fun pieMenuDismiss(menuId: String) {
        dispatchExecutor.execute {
            engine?.let { e ->
                // C6: the envelope comes from the wire's own SPEC 7.1 builder,
                // which emits `jsonrpc` itself — one spelling of the envelope
                // instead of a second hand-rolled one here.
                e.feed(com.calebc42.ebp.wire.encodeFrame(
                    com.calebc42.ebp.wire.notification("pie_menu.dismiss",
                        buildJsonObject { put("menu_id", menuId) }).toString()))
            }
        }
    }

    // SPEC 20.1.1: the last geometry the Activity reported, for seeding
    // sessions that begin before (or after) a recomposition.
    private var lastWindow: Pair<Int, Int>? = null

    /** SPEC 20.1.1: the Activity reports window geometry here on first
     * composition and every configuration change. The caller is the COMPOSE
     * UI thread and windowChanged may emit a notification — a socket write —
     * so the engine call marshals through the dispatch executor exactly like
     * every other UI-originated engine call (NetworkOnMainThreadException
     * killed the whole process on rotation before it did). */
    fun windowChanged(widthDp: Int, heightDp: Int) {
        lastWindow = widthDp to heightDp
        dispatchExecutor.execute { engine?.windowChanged(widthDp, heightDp) }
    }

    /** SPEC 13.4/14.2: resolve a multi-view spec to the view being shown;
     * a single-view spec passes through. */
    private fun resolveView(surface: String): JsonObject? {
        val spec = store.spec(surface) ?: return null
        val views = spec.objOrNull("views") ?: return spec
        val name = store.currentView(surface) ?: spec.stringOr("initial_view")
        return views.objOrNull(name)
    }

    private fun serve(socket: Socket, generation: Long) {
        val out = socket.getOutputStream()
        val engine = variantEngineRoute.prepare(generation) {
            CompanionEngine(config, store, queue, reminders, triggers, firing) { bytes ->
                // The sink runs on whatever thread emits — the reader, the pump,
                // or the UI dispatch executor. A peer that went away mid-write
                // MUST NOT crash that thread (and with it the app): close the
                // socket so the reader loop unwinds through engine.close().
                try {
                    out.write(bytes)
                    out.flush()
                } catch (failure: java.io.IOException) {
                    socket.runCatching { close() }
                    throw failure
                }
            }
        } ?: return
        // Installed before activation, so every renderer-visible engine has
        // an exact transport the hard password deadline can abort.
        engineTransports[engine] = socket
        // SPEC 20.1.1: seed the new session with the last-known geometry so
        // the welcome can mirror it before the Activity recomposes.
        lastWindow?.let { (w, h) -> engine.windowChanged(w, h) }
        // SPEC 18.2.1: the event-driven snackbar routes to whichever
        // scaffold host is on screen.
        engine.snackbarListener = { message, action, duration, respond ->
            // The consumer answers from the COMPOSE UI thread when the
            // snackbar leaves the screen; the reply is a socket write, so it
            // marshals through the dispatch executor like every other
            // UI-originated engine call.
            com.calebc42.glasspane.material3.SnackbarRaises.flow.value =
                com.calebc42.glasspane.material3.SnackbarRaises.Raise(
                    message, action, duration) { outcome ->
                    dispatchExecutor.execute { respond(outcome) }
                }
        }
        engine.surfaceListener = { surface ->
            // SPEC 17.2/9.1: bind the image cache to THIS session's
            // authenticated pairing identity before any image can render.
            // A different identity erases what the previous one fetched —
            // the §9.1 empty state partition, enforced rather than trusted
            // to a revocation call that may never arrive.
            engine.pairingIdentity?.let { id ->
                cacheScope.launch { ImageCache.setIdentity(id) }
            }
            // T3/LD-2: publish display generations before the spec, so the
            // recomposition this push triggers already sees the new epoch.
            _inputDisplays.value = store.inputDisplays()
            _variantSelections.value = store.variantSelections()
            if (surface.startsWith("app:")) {
                // Lead the spec with its presentation identity just like the
                // stateful display generations above.
                publishPresentationIncarnations(surface)
            }
            when {
                // SPEC 18.5: a notification:* surface is a system notification;
                // its removal (tombstone -> null spec) cancels it.
                surface.startsWith("notification:") -> {
                    val spec = store.spec(surface)
                    if (spec != null) Notifications.postSurface(appContext, surface, spec)
                    else Notifications.cancelSurface(appContext, surface)
                }
                surface.startsWith("widget:") -> onWidgetSurfaceChanged(surface)
                surface.startsWith("app:") ->
                    onSurfaceChanged(surface, resolveView(surface))
            }
        }
        engine.variantListener = { surface, id, value ->
            // CompanionEngine invokes this only after the draft passed the
            // input-state size gate and committed durably. This publication
            // therefore can never expose a selection reconnect would lose.
            _variantSelections.update { selections ->
                selections + ((surface to id) to value)
            }
        }
        engine.localStateProblemListener = onQueueProblem
        // SPEC 14.2: host-platform builtins. Settings is a stub until the app
        // shell lands (deferred scope) — visible, honest, no silent drop.
        engine.hostBuiltinListener = { builtin, descriptor ->
            when (builtin) {
                "clipboard.copy" -> {
                    val clip = android.content.ClipData.newPlainText(
                        "EBP", descriptor.stringOr("text"))
                    appContext.getSystemService(
                        android.content.ClipboardManager::class.java)
                        .setPrimaryClip(clip)
                    // SPEC 14.2: no additional private copy, no logging.
                }
                "share.send" -> {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT,
                            descriptor.stringOr("text"))
                        .also {
                            descriptor.stringOr("title").takeIf { t -> t.isNotEmpty() }
                                ?.let { t -> it.putExtra(
                                    android.content.Intent.EXTRA_TITLE, t) }
                        }
                    appContext.startActivity(
                        android.content.Intent.createChooser(send, null)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                "surface.open" -> onOpenSurface(descriptor.stringOr("surface"))
                "companion.settings.open" -> onOpenSettings()
            }
        }
        // SPEC 18.6: reconcile platform alarms with the accepted set (cancel
        // removed, arm new/changed non-fired).
        engine.reminderListener = { owner, newSet, priorSet ->
            Notifications.scheduleReminders(appContext, stores, owner, newSet, priorSet)
        }
        engine.dialogListener = { id, spec -> onDialogChanged(id, spec) }
        // SPEC 19.4 (T2/LD-5): every inbound edit.apply republishes the
        // shadow, so the on-screen text follows it — before this, s.shadow
        // moved and the display did not, and the next keystroke diffed
        // against a stale base. Fires under the engine monitor. The offer
        // view rides along (amendment #171): apply and resync are foreign
        // advances the executor never sees, and this is their one signal.
        engine.editorListener = { s ->
            publishMirror(s)
            publishOfferView(s.document to s.editorId,
                CompletionOfferView(s.offer.extendedPrefix(), s.offer.ext,
                    s.offer.active))
        }
        // A cached synchronized field remains visible across transport loss,
        // but never writable. If the reconnect carries no replacement
        // snapshot, its displayed process-volatile text and selection seed the
        // new §19 session; an explicit SYNCING snapshot wins in the engine.
        engine.editorSeedProvider = ::retainedEditorSeed
        engine.editorSetListener = ::retainLiveEditors
        // SPEC 19.5: diagnostics/fontify/eldoc have been validated, accepted
        // and then dropped into a null listener for the life of this tree —
        // no error, no log, no user-visible signal. They land here now.
        engine.annotationListener = { method, document, editorId, params ->
            absorbAnnotation(method, document, editorId, params)
        }
        // SPEC 19: the session ended (node removed, document changed, identity
        // changed). Drop the display's copy with it — a reopened editor must
        // not inherit the last one's text, offers, or squiggles.
        engine.editorClosedListener = { document, editorId ->
            forgetEditor(document, editorId)
        }
        // SPEC 18.1: an oversized submit keeps the dialog up; tell the user to
        // shorten the input (password erasure in the renderer is a follow-on).
        engine.dialogOverflowListener = { onToast("Input too large — please shorten it") }
        engine.toastListener = { text, _ -> onToast(text) }
        // SPEC 18.4: persist the accepted theme and mirror it. currentTheme()
        // is the normalized `{dark, colors, syntax}` payload after the merge.
        engine.themeListener = { _, _, _ ->
            val payload = engine.currentTheme()
            saveTheme(payload)
            onTheme(payload)
        }
        engine.pieMenuListener = { id, spec -> onPieMenuChanged(id, spec) }
        // Make the engine receiver-visible only after every callback is wired.
        // Activation shares the offline-commit gate, and generation equality
        // rejects a delayed serve() whose socket has already been superseded.
        // Install the cold-receiver live-session route inside that same gate,
        // before UI dispatch can observe this engine and before any welcome
        // bytes can be read from the peer.
        if (!variantEngineRoute.activate(generation, engine) {
                stores.setLiveSession(it)
            }) {
            engineTransports.remove(engine)
            engine.close("superseded before activation")
            return
        }
        var authenticated = false
        var reachedReady = false
        try {
            // INSIDE the try: SPEC 5.2's newest-wins supersession closes this
            // socket from the accept loop the instant a newcomer arrives, and
            // that can land before this thread ever reaches getInputStream().
            // Outside the try it threw SocketException("Socket is closed") out
            // of the thread and killed the process — which is what a retrying
            // `jetpacs-start' produced every time, since its second dial
            // superseded the first mid-setup.
            val input = socket.getInputStream()
            val buffer = ByteArray(8192)
            while (engine.state != SessionState.CLOSED) {
                val n = input.read(buffer)
                if (n < 0) break
                kotlinx.coroutines.runBlocking {
                    stores.commandActor.execute { engine.feed(buffer, 0, n) }
                }
                if (engine === variantEngineRoute.current()) {
                    _editorConnectionPhase.value = editorConnectionPhaseOf(engine.state)
                }
                if (!authenticated && isAuthenticatedConnectionState(engine.state)) {
                    authenticated = true
                    authenticatedConnection.authenticated(engine)
                    Notifications.cancelReconnect(appContext)
                }
                if (!reachedReady &&
                    engine.state == SessionState.READY &&
                    readyConnection.ready(generation, engine)
                ) {
                    reachedReady = true
                    persistReadyConnectionChange(null)
                }
            }
        } catch (_: Exception) {
            // transport loss: SPEC 10.1, any state may close
        } finally {
            engineTransports.remove(engine)
            // SPEC 15.3: the engine releases its in-flight marker so the
            // next session's replay is never wedged (review P0). LD-18:
            // best-effort — a throw here must not skip clearLiveSession and
            // socket.close(), which would leak the FD and park a dead engine.
            runCatching {
                kotlinx.coroutines.runBlocking {
                    stores.commandActor.execute { engine.close("transport closed") }
                }
            }
            // Atomic compare-and-clear FIRST: only if a newer connection
            // has not already superseded this one in the slot (SPEC 5.2
            // newest-wins) — and the shared display maps are wiped ONLY
            // on that same verdict (R5 review: they are process-wide,
            // so a superseded connection's delayed teardown was erasing
            // the successor session's mirrors, offers, and docs).
            val wasCurrentSession = stores.clearLiveSession(engine)
            if (wasCurrentSession) {
                // SPEC 19 closes the WIRE sessions. The rendered snapshot is
                // deliberately retained only in process memory so a cached
                // field neither blanks nor regresses to its authored seed on
                // reconnect; editorConnectionPhase makes it read-only before
                // any interaction can publish against the dead session.
                _editorConnectionPhase.value = EditorConnectionPhase.OFFLINE
                _editorAnnotations.value = emptyMap()
                // R5, a named pre-existing-gap fix: offers and their views
                // were NOT cleared here, so a dropdown could ghost across a
                // reconnect over state no session backs — its taps refused,
                // its rows a lie. Docs and slots die with the offers they
                // were fetched for.
                _completionOffers.value = emptyMap()
                _offerViews.value = emptyMap()
                _candidateDocs.value = emptyMap()
                candidateDocSlots.values.forEach { it.retire() }
                candidateDocSlots.clear()
                lastCaret.clear()
            }
            val wasCurrentAuthenticatedSession = authenticated &&
                authenticatedConnection.disconnected(engine)
            if (readyConnection.disconnected(generation, engine)) {
                persistReadyConnectionChange(
                    System.currentTimeMillis().coerceAtLeast(0),
                )
            }
            if (shouldPostReconnectNotification(
                    authenticated,
                    wasCurrentAuthenticatedSession,
                )) {
                Notifications.postReconnect(appContext)
            }
            socket.runCatching { close() }
        }
    }
}
