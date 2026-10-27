// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import com.calebc42.ebp.wire.EbpAuth
import com.calebc42.ebp.wire.PairingId
import com.calebc42.jetpacs.companion.host.CompanionHostPlatform
import com.calebc42.jetpacs.companion.host.CompanionTransport
import com.calebc42.jetpacs.companion.host.DEFAULT_LOOPBACK_PORT
import com.calebc42.jetpacs.companion.host.LoopbackTcpTransport
import com.calebc42.jetpacs.core.database.JetpacsDatabase
import com.calebc42.jetpacs.core.ebpstore.PairingCredentials

/**
 * The standalone flavor pairs with an Emacs the user installs; it carries no
 * engine, so every process of this app is a Companion process and launching
 * the Companion starts nothing (docs/PLAN-bundled-emacs.md).
 */
internal object EngineFlavor {
    const val bundled: Boolean = false

    /** SPEC 5.2's documented default; `adb forward` recipes rely on it. */
    const val loopbackPort: Int = DEFAULT_LOOPBACK_PORT

    fun isEngineProcess(app: Application): Boolean = false

    fun prepareEngineProcess(app: Application) = Unit

    fun onCompanionLaunched(context: Context) = Unit

    /** The background bridge service owns the listener; see [enableBridge]. */
    fun onCompanionProcessCreated(app: JetpacsApplication) = Unit

    /**
     * Opt into the best-effort background bridge: a foreground service that
     * owns the listener and its durable enabled flag.
     */
    fun enableBridge(context: Context) = JetpacsBridgeService.enable(context)

    /** After a reboot or an update the bridge returns if the user left it enabled. */
    suspend fun restoreBridge(app: JetpacsApplication) {
        if (app.container.backgroundBridgeEnabled()) JetpacsBridgeService.startPersisted(app)
    }

    /**
     * W4 smoke scope, unchanged: the public SPEC 9.3 known-answer credential
     * provisioned into the KeyStore on every start until generated pairing
     * replaces it for this flavor too.
     */
    suspend fun resolvePairing(
        app: Context,
        database: JetpacsDatabase,
        credentials: PairingCredentials,
    ): ResolvedPairing {
        val pairingId = PairingId(JETPACS_PAIRING_ID)
        val rawCredential = EbpAuth.decodePairingToken(JETPACS_PAIRING_TOKEN)
        val aliases = try {
            credentials.provision(pairingId, rawCredential)
        } finally {
            rawCredential.fill(0)
        }
        return ResolvedPairing(pairingId, aliases)
    }

    /** Bring Android Emacs forward; false when it is not installed. */
    fun openEmacs(context: Context): Boolean = openAndroidEmacs(context)

    /** The one-tap recovery is Android Emacs's own launcher activity. */
    fun reconnectPrompt(context: Context): ReconnectPrompt? {
        val launch = context.packageManager
            .getLaunchIntentForPackage(ANDROID_EMACS_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return null
        return ReconnectPrompt(
            launch,
            "Emacs disconnected",
            "Tap to open Android Emacs and reconnect Jetpacs.",
        )
    }

    fun listeningDescription(endpoint: String): String =
        "Jetpacs Companion is listening on $endpoint."

    /** The user runs Emacs: the host's own wording. */
    val hostWording = com.calebc42.jetpacs.companion.host.HostWording.Emacs

    fun listeningEndpoint(context: Context): String = "127.0.0.1:$loopbackPort"

    /** SPEC 5.2 on the flavor's port. */
    fun transport(context: Context, platform: CompanionHostPlatform): CompanionTransport =
        LoopbackTcpTransport(loopbackPort, platform.log)

    /** No wake mechanism without a configured, inert target (SPEC 5.8). */
    val wakeCapabilities: Set<String> = emptySet()

    fun wakeRequester(context: Context): (() -> Unit)? = null

    fun onSessionReady(context: Context) = Unit

    fun onCompanionVisible(activity: android.app.Activity) = Unit

    fun onCompanionHidden(activity: android.app.Activity) = Unit

    fun onBootOrUpdate(context: Context) = Unit

    @Composable
    fun SettingsSection(bridge: com.calebc42.jetpacs.companion.host.DeviceBridge) = Unit

    @Composable
    fun OnboardingContent(onDone: () -> Unit, onCancel: (() -> Unit)?) {
        OnboardingFlow(onDone = onDone, onCancel = onCancel)
    }
}
