// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.util.Log
import com.calebc42.ebp.wire.CapabilityHandler
import com.calebc42.ebp.wire.CompletionNarrowing
import com.calebc42.jetpacs.companion.host.AppCapabilityBindings
import com.calebc42.jetpacs.companion.host.CompanionHostPlatform
import com.calebc42.jetpacs.companion.host.CompanionStores
import com.calebc42.jetpacs.companion.host.DEFAULT_LOOPBACK_PORT
import com.calebc42.jetpacs.companion.host.DeviceLocale
import com.calebc42.jetpacs.companion.host.HostCapabilities
import com.calebc42.jetpacs.companion.host.HostClipboard
import com.calebc42.jetpacs.companion.host.HostColors
import com.calebc42.jetpacs.companion.host.HostLocale
import com.calebc42.jetpacs.companion.host.HostLog
import com.calebc42.jetpacs.companion.host.HostPresentation
import com.calebc42.jetpacs.companion.host.HostSettings
import com.calebc42.jetpacs.companion.host.HostShare
import com.calebc42.jetpacs.companion.host.HostUriOpener
import com.calebc42.jetpacs.companion.host.HostTrace
import com.calebc42.jetpacs.companion.host.HostTriggerSources
import java.util.concurrent.Executor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The Android half of the protocol host: every platform call `DeviceBridge`,
 * `CompanionStores` and `CompanionProcess` used to make inline, unchanged,
 * behind the `CompanionHostPlatform` seam. Policy stays in the host module;
 * this class performs named effects and reports platform facts.
 */
internal class AndroidHostPlatform(context: Context) : CompanionHostPlatform {
    private val app: Context = context.applicationContext

    override val log: HostLog = object : HostLog {
        override fun e(tag: String, message: String, failure: Throwable?) {
            if (failure != null) Log.e(tag, message, failure) else Log.e(tag, message)
        }

        override fun w(tag: String, message: String, failure: Throwable?) {
            if (failure != null) Log.w(tag, message, failure) else Log.w(tag, message)
        }

        override fun d(tag: String, message: String) {
            Log.d(tag, message)
        }

        override fun isDebugEnabled(tag: String): Boolean = Log.isLoggable(tag, Log.DEBUG)
    }

    override val uiExecutor: Executor get() = app.mainExecutor

    override val trace: HostTrace = object : HostTrace {
        override fun begin(section: String) = android.os.Trace.beginSection(section)
        override fun end() = android.os.Trace.endSection()
    }

    override val settings: HostSettings = object : HostSettings {
        /** Amendment #171: user-settable, persisted; strict is the reference default. */
        override var completionNarrowing: CompletionNarrowing
            get() = if (
                app.getSharedPreferences("ebp", Context.MODE_PRIVATE)
                    .getString("completion_narrowing", "strict") == "contains"
            ) {
                CompletionNarrowing.CONTAINS
            } else {
                CompletionNarrowing.STRICT
            }
            set(value) {
                app.getSharedPreferences("ebp", Context.MODE_PRIVATE)
                    .edit().putString(
                        "completion_narrowing",
                        if (value == CompletionNarrowing.CONTAINS) "contains" else "strict",
                    ).apply()
            }

        override val designRuntimeEnabled: Boolean
            get() = ExperimentalElispDesignRuntime.isEnabled(app)

        override fun persistDesignRuntimeEnabled(enabled: Boolean): Boolean =
            ExperimentalElispDesignRuntime.persist(app, enabled)
    }

    override val clipboard: HostClipboard = HostClipboard { label, text ->
        app.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, text))
        // SPEC 14.2: no additional private copy, no logging.
    }

    override val share: HostShare = HostShare { text, title ->
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .also { title?.let { t -> it.putExtra(Intent.EXTRA_TITLE, t) } }
        app.startActivity(
            Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Amendment #208: ACTION_VIEW, as Orgzly Revived's links open — the
     * dialer for tel:, Messages for sms:, mail for mailto:, the browser. */
    override val uriOpener: HostUriOpener = HostUriOpener { uri ->
        val view = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(view)
        } catch (e: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(app, "No app can open this link", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override val locale: HostLocale = object : HostLocale {
        override fun current(): DeviceLocale = AppCapabilities.deviceLocale(app)

        /** SPEC 20.1.2 (#197): the platform says when the locale, the clock cycle or the time zone changed. */
        override fun observe(onChanged: () -> Unit): AutoCloseable {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    onChanged()
                }
            }
            app.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_LOCALE_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(Intent.ACTION_TIME_CHANGED)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )
            return AutoCloseable { runCatching { app.unregisterReceiver(receiver) } }
        }
    }

    /** SPEC 20.1.3 (#205): the device's own colors, re-read on every change. */
    override val colors: HostColors = AndroidHostColors(app)

    override val capabilities: HostCapabilities = object : HostCapabilities {
        override val supportedCapabilities: Set<String> = setOf(
            "theme", "surfaces.dialog", "presentation.toast",
            "presentation.snackbar",
            "presentation.pie-menu", "reminders.owner", "reminders.actions",
            "surfaces.notification",
            "surfaces.widget", "surfaces.tile",
            "editor.sync", "capabilities", "triggers",
            // EBP 13.8 (amendment #194): the surface data document.
            "data.model",
        ) + EngineFlavor.wakeCapabilities
        // Standalone: `offline.wake` is deliberately absent until the user
        // configures an exact, inert OS-local Emacs wake target and the host
        // rechecks it at every send (SPEC 5.8). A durable queue alone is not
        // a wake grant. Bundled: the target is the engine's own service, fixed
        // by the application (amendment #209), so the flavor grants it.

        // Room encrypts every queued payload with an identity-scoped Android
        // KeyStore key, so queued sms/call fire data satisfies SPEC 21.5.
        override val sensitiveQueueEncrypted: Boolean = true

        override fun deviceReport(): JsonObject = AppCapabilities.deviceReport(app)

        override fun handler(maxFieldBytes: Long, bindings: AppCapabilityBindings): CapabilityHandler =
            AppCapabilities.handler(app, maxFieldBytes, bindings)

        override fun bootGeneration(): String? = runCatching {
            Settings.Global.getString(app.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrNull()

        override fun triggerSources(
            onSample: (String, JsonObject) -> Unit,
            onExternal: (String, JsonObject) -> Unit,
        ): HostTriggerSources = TriggerSources(app, onSample, onExternal)

        override val tileSurfaceIds: Set<String> get() = TileSlots.surfaceIds
    }

    override val presentation: HostPresentation = object : HostPresentation {
        override fun surfaceNotification(surface: String, spec: JsonObject?) {
            if (spec != null) Notifications.postSurface(app, surface, spec)
            else Notifications.cancelSurface(app, surface)
        }

        override fun tileChanged(surface: String) = TileSlots.refresh(app, surface)

        override fun scheduleReminders(
            stores: CompanionStores,
            owner: String,
            newSet: JsonArray,
            priorSet: JsonArray,
        ) = Notifications.scheduleReminders(app, stores, owner, newSet, priorSet)

        override fun rearmAfterRecovery(stores: CompanionStores) {
            Notifications.rearmAllReminders(app, stores)
            TriggerAlarms.reschedule(app, stores)
        }

        override fun timeScheduleChanged(stores: CompanionStores) = TriggerAlarms.reschedule(app, stores)

        override fun triggerNotification(notify: JsonObject) = Notifications.postTrigger(app, notify)

        override fun postReminder(owner: String, reminderId: String, atMs: Long, payload: JsonObject) =
            Notifications.postReminder(app, owner, reminderId, atMs, payload)

        override fun reconnectNotification(show: Boolean) {
            if (show) Notifications.postReconnect(app) else Notifications.cancelReconnect(app)
        }
    }

    // The flavor owns the port: standalone keeps SPEC 5.2's default, bundled
    // listens beside it (docs/PLAN-bundled-emacs.md).
    override val loopbackPort: Int = EngineFlavor.loopbackPort
}
