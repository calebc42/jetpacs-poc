// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

import com.calebc42.ebp.wire.CapabilityHandler
import com.calebc42.ebp.wire.CompletionNarrowing
import java.util.concurrent.Executor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * What the protocol host rents from the platform it runs on.
 *
 * `DeviceBridge`, `CompanionStores` and `CompanionProcess` are platform
 * neutral; every operating-system call they used to make directly arrives
 * through this seam. The Android app implements it over the code that used
 * to be inline (`android.util.Log`, the main executor, `ClipboardManager`,
 * the share chooser, the locale broadcast, SharedPreferences, the
 * notification and alarm adapters); the desktop implements it over the JDK
 * and AWT. Nothing behind this seam decides application policy: a platform
 * supplies facts, executes a named effect, or reports what it offers.
 */
interface CompanionHostPlatform {
    /** Diagnostics. Nothing logged may carry token material (SPEC §9.1). */
    val log: HostLog

    /**
     * Where renderer outcomes and editor results are delivered: the UI thread
     * the composition reads from (Android's main executor, the AWT event queue).
     */
    val uiExecutor: Executor

    /** System tracing around the hot `variant.switch` path; a no-op elsewhere. */
    val trace: HostTrace

    /** Receiver-local, non-secret settings that survive a restart. */
    val settings: HostSettings

    /** The `clipboard.copy` builtin (SPEC §14.2). */
    val clipboard: HostClipboard

    /**
     * The `share.send` builtin, or null on a platform without a share sheet.
     * A null share means the host's renderer installation must not advertise
     * the builtin (see `PlatformRendererTargets.hostBuiltins`).
     */
    val share: HostShare?

    /**
     * Amendment #208 `uri.open`: hand a validated URI to the platform's
     * default viewer, or null where the platform has none.
     */
    val uriOpener: HostUriOpener?

    /** SPEC §20.1.2 locale advice and its change notification. */
    val locale: HostLocale

    /** SPEC §20.1.3 device colors and their change notification. */
    val colors: HostColors

    /** SPEC §20 and §21: the device report, the capability executor, triggers. */
    val capabilities: HostCapabilities

    /** Platform presentation beyond the window: notifications, tiles, alarms. */
    val presentation: HostPresentation

    /** SPEC §5.2: the loopback port the listener binds; 8765 unless configured. */
    val loopbackPort: Int
}

/** Tagged diagnostics in the shape every host already has. */
interface HostLog {
    fun e(tag: String, message: String, failure: Throwable? = null)
    fun w(tag: String, message: String, failure: Throwable? = null)
    fun d(tag: String, message: String)

    /** Whether [d] for [tag] goes anywhere; the stage timer is installed only then. */
    fun isDebugEnabled(tag: String): Boolean
}

/** A begin/end section marker for a platform profiler. */
interface HostTrace {
    fun begin(section: String)
    fun end()

    /** The platform without a profiler. */
    object None : HostTrace {
        override fun begin(section: String) = Unit
        override fun end() = Unit
    }
}

/**
 * Receiver-owned settings. These are presentation preferences, never EBP
 * state and never secret, so a platform keeps them wherever it keeps plain
 * preferences.
 */
interface HostSettings {
    /** Amendment #171: the active completion-narrowing predicate. */
    var completionNarrowing: CompletionNarrowing

    /** Whether the experimental Elisp design runtime is installed. */
    val designRuntimeEnabled: Boolean

    /**
     * Persist [enabled] synchronously and report whether the write committed;
     * publication and the reconnect follow only a committed write.
     */
    fun persistDesignRuntimeEnabled(enabled: Boolean): Boolean
}

/** SPEC §14.2 `clipboard.copy`: the text goes to the system clipboard, and nowhere else. */
fun interface HostClipboard {
    fun copy(label: String, text: String)
}

/** Amendment #208 `uri.open`: open [uri] (already validated) with the platform's default viewer. */
fun interface HostUriOpener {
    fun open(uri: String)
}

/** SPEC §14.2 `share.send`: hand [text], with an optional [title], to the platform's share sheet. */
fun interface HostShare {
    fun send(text: String, title: String?)
}

/** SPEC §20.1.2: the presentation locale and when it changes. */
interface HostLocale {
    fun current(): DeviceLocale

    /**
     * Call [onChanged] whenever the locale, clock cycle or time zone changes
     * until the returned handle is closed. A platform without such a signal
     * returns a handle that closes nothing.
     */
    fun observe(onChanged: () -> Unit): AutoCloseable
}

/** SPEC §20.1.3 (#205): the device's color scheme and when it changes. */
interface HostColors {
    fun current(): DeviceColors

    /**
     * Call [onChanged] whenever the wallpaper-derived scheme or the system
     * appearance changes until the returned handle is closed. A platform
     * without such a signal returns a handle that closes nothing.
     */
    fun observe(onChanged: () -> Unit): AutoCloseable
}

/** A platform's trigger sources: the observed state and the occurrences they emit. */
interface HostTriggerSources : DeviceStateAccess {
    fun start()
    fun currentState(type: String): JsonObject?

    /** Host evaluation for a predicate whose public sample omits filter detail. */
    fun predicateHolds(predicate: JsonObject): Boolean?
}

/** SPEC §20 and §21: what this platform reports and executes. */
interface HostCapabilities {
    /** SPEC §22.1 capability names this host implements, and only those. */
    val supportedCapabilities: Set<String>

    /** True only when queued sensitive payloads rest behind a platform keystore (SPEC §21.5). */
    val sensitiveQueueEncrypted: Boolean

    /** SPEC §20.1: a fresh device report; empty offers neither `capabilities` nor `triggers`. */
    fun deviceReport(): JsonObject

    /** SPEC §20.2: the `capability.invoke` executor, or null when the report's `caps` is empty. */
    fun handler(maxFieldBytes: Long, bindings: AppCapabilityBindings): CapabilityHandler?

    /** SPEC §21.5: the device's boot generation, or null when the platform has none. */
    fun bootGeneration(): String?

    /** The platform's trigger sources, fed into the firing service through the two callbacks. */
    fun triggerSources(
        onSample: (String, JsonObject) -> Unit,
        onExternal: (String, JsonObject) -> Unit,
    ): HostTriggerSources

    /** Manifest-declared fixed tile slots, or empty where tiles do not exist. */
    val tileSurfaceIds: Set<String>
}

/**
 * Presentation the platform owns outside the window. Every call is a named
 * effect the protocol host has already decided on; the platform performs it
 * or, where the surface type does not exist, does nothing.
 */
interface HostPresentation {
    /** SPEC §18.5: post the `notification:*` surface, or cancel it when [spec] is null. */
    fun surfaceNotification(surface: String, spec: JsonObject?)

    /** A `tile:*` surface changed. */
    fun tileChanged(surface: String)

    /** SPEC §18.6: reconcile platform alarms with the accepted reminder set. */
    fun scheduleReminders(stores: CompanionStores, owner: String, newSet: JsonArray, priorSet: JsonArray)

    /** After recovery: re-arm every reminder and the time-trigger schedule. */
    fun rearmAfterRecovery(stores: CompanionStores)

    /** SPEC §21.5: the time-trigger schedule changed. */
    fun timeScheduleChanged(stores: CompanionStores)

    /** SPEC §21.4: present a trigger's `on_fire` notification. */
    fun triggerNotification(notify: JsonObject)

    /** SPEC §18.6: present one reminder from the durable effect ledger. */
    fun postReminder(owner: String, reminderId: String, atMs: Long, payload: JsonObject)

    /** Show, or cancel, the reconnect prompt for a lost authenticated session. */
    fun reconnectNotification(show: Boolean)
}
