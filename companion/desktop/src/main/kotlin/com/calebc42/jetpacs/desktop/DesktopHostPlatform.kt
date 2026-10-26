// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import com.calebc42.ebp.wire.CapabilityHandler
import com.calebc42.ebp.wire.CompletionNarrowing
import com.calebc42.jetpacs.companion.host.AppCapabilityBindings
import com.calebc42.jetpacs.companion.host.CompanionHostPlatform
import com.calebc42.jetpacs.companion.host.CompanionStores
import com.calebc42.jetpacs.companion.host.DeviceColors
import com.calebc42.jetpacs.companion.host.DeviceLocale
import com.calebc42.jetpacs.companion.host.HostColors
import com.calebc42.jetpacs.material3.baselineSchemeRoles
import com.calebc42.jetpacs.companion.host.HostCapabilities
import com.calebc42.jetpacs.companion.host.HostClipboard
import com.calebc42.jetpacs.companion.host.HostLocale
import com.calebc42.jetpacs.companion.host.HostLog
import com.calebc42.jetpacs.companion.host.HostPresentation
import com.calebc42.jetpacs.companion.host.HostSettings
import com.calebc42.jetpacs.companion.host.HostShare
import com.calebc42.jetpacs.companion.host.HostTrace
import com.calebc42.jetpacs.companion.host.HostTriggerSources
import com.calebc42.jetpacs.companion.host.localeAdvice
import com.calebc42.jetpacs.core.ebpstore.CredentialProtection
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import java.util.concurrent.Executor
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The desktop half of the protocol host: the JDK and AWT behind
 * `CompanionHostPlatform`. It advertises only what the window implements
 * (D5): no notifications, widgets, tiles, reminders, triggers or device
 * report, never `offline.wake` (SPEC §5.8), and no share sheet, so
 * `share.send` is not in its renderer targets either.
 */
class DesktopHostPlatform(
    config: DesktopConfig,
    private val protection: CredentialProtection,
    private val toasts: DesktopToasts,
    override val uiExecutor: Executor,
    override val settings: DesktopSettings,
) : CompanionHostPlatform {
    override val log: HostLog = JulHostLog

    override val trace: HostTrace = HostTrace.None

    override val clipboard: HostClipboard = HostClipboard { _, text ->
        if (!copyToSystemClipboard(text)) log.w("EbpBridge", "the system clipboard is unavailable")
    }

    override val share: HostShare? = null

    override val locale: HostLocale = object : HostLocale {
        override fun current(): DeviceLocale = currentDesktopLocale()

        /** No desktop broadcast says the locale changed; the welcome carries the start-time advice. */
        override fun observe(onChanged: () -> Unit): AutoCloseable = AutoCloseable { }
    }

    /** SPEC 20.1.3 (#205): no wallpaper scheme on the desktop; the baseline
     * Material schemes at the system appearance, read once at start. */
    override val colors: HostColors = object : HostColors {
        override fun current(): DeviceColors = DeviceColors(
            dark = desktopSystemDark(),
            light = baselineSchemeRoles(false),
            darkScheme = baselineSchemeRoles(true),
        )

        /** No desktop signal says the appearance changed; the welcome carries the start-time report. */
        override fun observe(onChanged: () -> Unit): AutoCloseable = AutoCloseable { }
    }

    override val capabilities: HostCapabilities = object : HostCapabilities {
        override val supportedCapabilities: Set<String> = DESKTOP_CAPABILITIES

        /** SPEC §21.5: only a keychain-backed payload key protects queued data at rest. */
        override val sensitiveQueueEncrypted: Boolean = protection is CredentialProtection.Keystore

        /** SPEC §20.1: empty, so neither `capabilities` nor `triggers` is offered (D7 owns them). */
        override fun deviceReport(): JsonObject = JsonObject(emptyMap())

        override fun handler(maxFieldBytes: Long, bindings: AppCapabilityBindings): CapabilityHandler? = null

        override fun bootGeneration(): String? = null

        override fun triggerSources(
            onSample: (String, JsonObject) -> Unit,
            onExternal: (String, JsonObject) -> Unit,
        ): HostTriggerSources = NoTriggerSources

        override val tileSurfaceIds: Set<String> = emptySet()
    }

    override val presentation: HostPresentation = object : HostPresentation {
        override fun surfaceNotification(surface: String, spec: JsonObject?) = Unit
        override fun tileChanged(surface: String) = Unit
        override fun scheduleReminders(stores: CompanionStores, owner: String, newSet: JsonArray, priorSet: JsonArray) = Unit
        override fun rearmAfterRecovery(stores: CompanionStores) = Unit
        override fun timeScheduleChanged(stores: CompanionStores) = Unit
        override fun triggerNotification(notify: JsonObject) = Unit
        override fun postReminder(owner: String, reminderId: String, atMs: Long, payload: JsonObject) = Unit

        /** The window is the only presentation; a lost session is a toast there. */
        override fun reconnectNotification(show: Boolean) {
            if (show) toasts.show("Emacs disconnected")
        }
    }

    override val loopbackPort: Int = config.port

    companion object {
        /** SPEC §22.1: the capabilities the desktop window implements in D5, and only those. */
        val DESKTOP_CAPABILITIES: Set<String> = setOf(
            "theme",
            "surfaces.dialog",
            "presentation.toast",
            "presentation.snackbar",
            "presentation.pie-menu",
            "editor.sync",
            // EBP 13.8 (amendment #194): the surface data document.
            "data.model",
        )
    }
}

/** Diagnostics through java.util.logging, one logger per tag. Nothing here may carry token material. */
object JulHostLog : HostLog {
    override fun e(tag: String, message: String, failure: Throwable?) =
        Logger.getLogger(tag).log(Level.SEVERE, message, failure)

    override fun w(tag: String, message: String, failure: Throwable?) =
        Logger.getLogger(tag).log(Level.WARNING, message, failure)

    override fun d(tag: String, message: String) = Logger.getLogger(tag).fine(message)

    /** The stage timer runs when the tag's logger admits FINE (`-Djetpacs.stages=true` turns it on). */
    override fun isDebugEnabled(tag: String): Boolean = Logger.getLogger(tag).isLoggable(Level.FINE)
}

/** A desktop observes no device state in D5. */
object NoTriggerSources : HostTriggerSources {
    override val stateTypes: Set<String> = emptySet()
    override fun sample(type: String): JsonObject? = null
    override fun predicatesHold(predicates: JsonArray): Boolean = false
    override fun start() = Unit
    override fun currentState(type: String): JsonObject? = null
    override fun predicateHolds(predicate: JsonObject): Boolean? = null
}

/**
 * SPEC §20.1.2: the JDK's view of the locale advice. The clock cycle comes
 * from the localized short time pattern: an hour-of-day letter means `h23`.
 */
/** The desktop's appearance as Skiko reads it, light when the toolkit cannot say. */
fun desktopSystemDark(): Boolean =
    runCatching { org.jetbrains.skiko.currentSystemTheme == org.jetbrains.skiko.SystemTheme.DARK }
        .getOrDefault(false)

fun currentDesktopLocale(
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault(),
): DeviceLocale {
    val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
        null,
        FormatStyle.SHORT,
        IsoChronology.INSTANCE,
        locale,
    )
    val is24Hour = 'H' in pattern || 'k' in pattern
    return localeAdvice(locale.toLanguageTag(), is24Hour, timeZone.id)
}

/** The system clipboard, or false where there is none (a headless test). */
fun copyToSystemClipboard(text: String): Boolean = runCatching {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}.isSuccess

/**
 * Receiver-owned, non-secret settings in a properties file inside the data
 * directory: the completion narrowing, the design-runtime toggle and whether
 * the pairing screen was acknowledged.
 */
class DesktopSettings(private val file: Path) : HostSettings {
    private val lock = Any()

    override var completionNarrowing: CompletionNarrowing
        get() = if (read(COMPLETION_NARROWING) == "contains") CompletionNarrowing.CONTAINS else CompletionNarrowing.STRICT
        set(value) = write(COMPLETION_NARROWING, if (value == CompletionNarrowing.CONTAINS) "contains" else "strict")

    override val designRuntimeEnabled: Boolean
        get() = read(DESIGN_RUNTIME_ENABLED) == "true"

    override fun persistDesignRuntimeEnabled(enabled: Boolean): Boolean =
        runCatching { write(DESIGN_RUNTIME_ENABLED, enabled.toString()) }.isSuccess

    /** The pairing screen was acknowledged once; the window opens on the catalog afterwards. */
    var onboardingComplete: Boolean
        get() = read(ONBOARDING_COMPLETE) == "true"
        set(value) = write(ONBOARDING_COMPLETE, value.toString())

    private fun read(key: String): String? = synchronized(lock) { load().getProperty(key) }

    private fun write(key: String, value: String) {
        synchronized(lock) {
            val properties = load()
            properties.setProperty(key, value)
            file.parent?.let(Files::createDirectories)
            val temporary = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.newOutputStream(temporary).use { properties.store(it, "Jetpacs Companion settings") }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun load(): Properties = Properties().also { properties ->
        if (Files.exists(file)) Files.newInputStream(file).use(properties::load)
    }

    private companion object {
        const val COMPLETION_NARROWING = "completion_narrowing"
        const val DESIGN_RUNTIME_ENABLED = "elisp_design_runtime_enabled"
        const val ONBOARDING_COMPLETE = "onboarding_complete"
    }
}
