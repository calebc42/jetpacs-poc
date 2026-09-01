// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import com.calebc42.ebp.wire.CapabilityHandler
import com.calebc42.ebp.wire.CapabilityOutcome
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Host-owned state seam shared by `state.get` and trigger gates. */
internal interface DeviceStateAccess {
    val stateTypes: Set<String>
    fun sample(type: String): JsonObject?
    fun unavailableReason(type: String): String = "unavailable"
    fun predicatesHold(predicates: JsonArray): Boolean
}

/** Runtime dependencies that keep the catalog adapter separate from app policy. */
internal data class AppCapabilityBindings(
    val pairingIdentity: String,
    val state: DeviceStateAccess,
    val fireManual: (String) -> Boolean,
    val setKeepScreenOn: (Boolean) -> Unit,
)

/**
 * Android host implementation of the complete optional SPEC 20.3 catalog.
 *
 * The wire library validates closed argument/result shapes. This object owns
 * the second, invocation-time gate: permission checks, the exact intent
 * allowlist, positive launchable-package knowledge, platform bounds, and the
 * actual Android handoff. No string from EBP is executed as code.
 */
internal object AppCapabilities {
    const val MAX_SHORTCUT_ICON_BYTES = 262_144L
    const val MAX_SHORTCUTS = 5L
    const val MAX_DEVICE_REPORT_BYTES = 8_192L
    private const val MAX_REPORTED_LAUNCHABLE_PACKAGES = 32

    private val CAPS = listOf(
        "settings.open", "intent.start", "app.launch", "apps.list",
        "shortcut.pin", "shortcuts.set", "vibrate", "tts.speak",
        "volume.set", "ringer.mode", "flashlight", "media.key",
        "clipboard.read", "screen.keep_on", "brightness.set", "dnd.set",
        "state.get", "trigger.fire",
    )

    /** Bounded, non-interactive operations accepted inside trigger `on_fire`. */
    private val TRIGGER_CAPS = listOf(
        "vibrate", "tts.speak", "ringer.mode", "flashlight",
        "media.key", "screen.keep_on", "brightness.set", "dnd.set",
    )

    val TRIGGER_TYPES = listOf(
        "time", "power", "battery.level", "screen", "headset", "airplane",
        "boot", "timezone.changed", "package", "manual", "state.edge",
        "network", "wifi.enabled", "bluetooth.enabled", "calendar.event",
        "sms.received", "call.state",
    )

    val STATE_TYPES = listOf(
        "power", "battery.level", "screen", "airplane", "network", "headset",
        "wifi.enabled", "bluetooth.enabled", "calendar.event", "call.state",
    )

    private val SETTINGS_PANELS = listOf(
        "app-details", "notifications", "battery-optimization", "accessibility",
        "wireless", "bluetooth", "location", "sound", "dnd",
    )

    private const val PERMISSION_CAMERA = "camera"
    private const val PERMISSION_WRITE_SETTINGS = "write_settings"
    private const val PERMISSION_NOTIFICATION_POLICY = "notification_policy"
    private const val PERMISSION_POST_NOTIFICATIONS = "post_notifications"
    private const val PERMISSION_EXACT_ALARMS = "exact_alarms"
    private const val PERMISSION_READ_CALENDAR = "read_calendar"
    private const val PERMISSION_RECEIVE_SMS = "receive_sms"
    private const val PERMISSION_READ_PHONE_STATE = "read_phone_state"
    private const val PERMISSION_READ_CALL_LOG = "read_call_log"
    private const val PERMISSION_BLUETOOTH_CONNECT = "bluetooth_connect"

    internal data class IntentRule(
        val action: String,
        val mode: String = "activity",
        val packageName: String? = null,
        val className: String? = null,
        val schemes: Set<String> = emptySet(),
        val authorities: Set<String> = emptySet(),
        val mimeTypes: Set<String> = emptySet(),
        val extraKeys: Set<String> = emptySet(),
        val trigger: Boolean = false,
    )

    // Exact, wildcard-free host policy. The same rows are projected into the
    // welcome and consulted again immediately before the Android handoff.
    private val INTENT_RULES = listOf(
        IntentRule(
            action = Intent.ACTION_SEND,
            mimeTypes = setOf("text/plain"),
            extraKeys = setOf(Intent.EXTRA_TEXT, Intent.EXTRA_TITLE),
        ),
        IntentRule(action = Intent.ACTION_DIAL, schemes = setOf("tel")),
        IntentRule(action = Intent.ACTION_VIEW, schemes = setOf("geo")),
    )

    private class Refusal(
        val code: Int,
        val reason: String,
        val permission: String? = null,
    ) : Exception(reason)

    /** Build the complete, deterministic welcome projection for this host. */
    fun deviceReport(context: Context): JsonObject {
        val permissions = permissionMap(context)
        val unavailable = buildJsonObject {
            if (!permissions.boolOr(PERMISSION_READ_CALENDAR)) {
                put("calendar.event", strings(listOf(PERMISSION_READ_CALENDAR)))
            }
            if (!permissions.boolOr(PERMISSION_RECEIVE_SMS)) {
                put("sms.received", strings(listOf(PERMISSION_RECEIVE_SMS)))
            }
            if (!permissions.boolOr(PERMISSION_READ_PHONE_STATE)) {
                put("call.state", strings(listOf(PERMISSION_READ_PHONE_STATE)))
            }
            if (!permissions.boolOr(PERMISSION_BLUETOOTH_CONNECT)) {
                put("bluetooth.enabled", strings(listOf(PERMISSION_BLUETOOTH_CONNECT)))
            }
        }
        fun report(launchablePackages: List<String>) = buildJsonObject {
                put("caps", strings(CAPS))
                put("trigger_caps", strings(TRIGGER_CAPS))
                put("permissions", permissions)
                put("settings_panels", strings(SETTINGS_PANELS))
                put("intent_allowlist", intentAllowlistJson())
                put("launchable_packages", strings(launchablePackages))
                put("trigger_types", strings(TRIGGER_TYPES))
                put("state_types", strings(STATE_TYPES))
                put("trackable_state_types", strings(STATE_TYPES))
                put("trigger_unavailable", unavailable)
            }
        var bounded = report(emptyList())
        check(encodedBytes(bounded) <= MAX_DEVICE_REPORT_BYTES) {
            "static device report exceeds max_device_report_bytes"
        }
        val included = ArrayList<String>()
        advertisedLaunchablePackages(context).forEach { packageName ->
            val candidate = report(included + packageName)
            if (encodedBytes(candidate) <= MAX_DEVICE_REPORT_BYTES) {
                included += packageName
                bounded = candidate
            }
        }
        return bounded
    }

    /** The executor installed into both direct invocation and trigger `on_fire`. */
    fun handler(
        context: Context,
        maxFieldBytes: Long,
        bindings: AppCapabilityBindings,
    ) = CapabilityHandler { cap, args ->
        try {
            val result = when (cap) {
                "settings.open" -> settingsOpen(context, args)
                "intent.start" -> intentStart(context, args)
                "app.launch" -> appLaunch(context, args)
                "apps.list" -> appsList(context, args)
                "shortcut.pin" -> PlatformShortcuts.pin(
                    context, bindings.pairingIdentity, args, MAX_SHORTCUT_ICON_BYTES)
                "shortcuts.set" -> PlatformShortcuts.replaceDynamic(
                    context, bindings.pairingIdentity, args,
                    MAX_SHORTCUT_ICON_BYTES, MAX_SHORTCUTS.toInt())
                "vibrate" -> vibrate(context, args)
                "tts.speak" -> ttsSpeak(context, args)
                "volume.set" -> volumeSet(context, args)
                "ringer.mode" -> ringerMode(context, args)
                "flashlight" -> flashlight(context, args)
                "media.key" -> mediaKey(context, args)
                "clipboard.read" -> clipboardRead(context, maxFieldBytes)
                "screen.keep_on" -> emptyResult().also {
                    bindings.setKeepScreenOn(args.boolOr("on"))
                }
                "brightness.set" -> brightnessSet(context, args)
                "dnd.set" -> dndSet(context, args)
                "state.get" -> stateGet(args, bindings.state)
                "trigger.fire" -> triggerFire(args, bindings.fireManual)
                else -> throw Refusal(1003, "unimplemented")
            }
            CapabilityOutcome.Ok(result)
        } catch (failure: Refusal) {
            CapabilityOutcome.Fail(failure.code, failure.reason, failure.permission)
        } catch (_: SecurityException) {
            CapabilityOutcome.Fail(1002, "permission-denied")
        } catch (_: ActivityNotFoundException) {
            CapabilityOutcome.Fail(1003, "activity-unavailable")
        } catch (_: Exception) {
            CapabilityOutcome.Fail(1003, "platform-error")
        }
    }

    private fun settingsOpen(context: Context, args: JsonObject): JsonObject {
        val intent = when (args.stringOr("panel")) {
            "app-details" -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"))
            "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            "battery-optimization" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "wireless" -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "dnd" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            else -> throw Refusal(1003, "settings-panel-denied")
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return emptyResult()
    }

    private fun intentStart(context: Context, args: JsonObject): JsonObject {
        val rule = matchingIntentRule(args) ?: throw Refusal(1003, "intent-denied")
        val data = args.stringOrNull("data")
        val mime = args.stringOrNull("mime")
        val intent = Intent(args.stringOr("action"))
        when {
            data != null && mime != null -> intent.setDataAndType(Uri.parse(data), mime)
            data != null -> intent.data = Uri.parse(data)
            mime != null -> intent.type = mime
        }
        when {
            rule.className != null -> intent.component = ComponentName(
                requireNotNull(rule.packageName), rule.className)
            rule.packageName != null -> intent.setPackage(rule.packageName)
        }
        args.objOrNull("extras")?.forEach { (key, value) ->
            putPlainExtra(intent, key, value)
        }
        when (rule.mode) {
            "activity" -> context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "broadcast" -> context.sendBroadcast(intent)
            "service" -> if (context.startService(intent) == null) {
                throw Refusal(1003, "service-unavailable")
            }
            else -> throw Refusal(1003, "intent-denied")
        }
        return emptyResult()
    }

    /** Pure exact-match policy, separated so host tests can pin every absence rule. */
    internal fun matchingIntentRule(args: JsonObject): IntentRule? {
        val action = args.stringOrNull("action") ?: return null
        val mode = args.stringOr("mode", "activity")
        val packageName = args.stringOrNull("package")
        val className = args.stringOrNull("class_name")
        val mime = args.stringOrNull("mime")
        val uri = args.stringOrNull("data")?.let {
            runCatching { URI(it) }.getOrNull() ?: return null
        }
        if (uri != null && (!uri.isAbsolute || uri.rawUserInfo != null)) return null
        val extraKeys = args.objOrNull("extras")?.keys.orEmpty()
        return INTENT_RULES.firstOrNull { rule ->
            rule.action == action && rule.mode == mode &&
                rule.packageName == packageName && rule.className == className &&
                (uri == null || uri.scheme in rule.schemes) &&
                (uri?.rawAuthority == null || uri.rawAuthority in rule.authorities) &&
                (mime == null || mime in rule.mimeTypes) &&
                extraKeys.all(rule.extraKeys::contains) &&
                (uri != null || rule.schemes.isEmpty()) &&
                (mime != null || rule.mimeTypes.isEmpty())
        }
    }

    private fun putPlainExtra(intent: Intent, key: String, value: JsonElement) {
        if (value !is JsonArray) {
            putScalarExtra(intent, key, value)
            return
        }
        val values = value.map { element ->
            element as? JsonPrimitive ?: throw Refusal(1003, "intent-extra-invalid")
        }
        // Never cross the Android boundary through Serializable or Parcelable.
        // These arrays are constructed from wire-validated scalar primitives;
        // a heterogeneous array has no safe native Bundle representation and
        // therefore fails as a platform operation instead of being serialized.
        when {
            values.isEmpty() || values.all { it.isString } ->
                intent.putExtra(key, values.map { it.content }.toTypedArray())
            values.all { !it.isString && it.content.toBooleanStrictOrNull() != null } ->
                intent.putExtra(
                    key,
                    values.map { it.content.toBooleanStrict() }.toBooleanArray(),
                )
            values.all { !it.isString && it.content.toLongOrNull() != null } ->
                intent.putExtra(key, values.map { it.content.toLong() }.toLongArray())
            values.all {
                !it.isString && it.content.toDoubleOrNull()?.isFinite() == true
            } -> intent.putExtra(
                key,
                values.map { it.content.toDouble() }.toDoubleArray(),
            )
            else -> throw Refusal(1003, "intent-extra-invalid")
        }
    }

    private fun putScalarExtra(intent: Intent, key: String, value: JsonElement) {
        val primitive = value as? JsonPrimitive
            ?: throw Refusal(1003, "intent-extra-invalid")
        when {
            primitive.isString -> intent.putExtra(key, primitive.content)
            primitive.content.toBooleanStrictOrNull() != null ->
                intent.putExtra(key, primitive.content.toBooleanStrict())
            primitive.content.toLongOrNull() != null ->
                intent.putExtra(key, primitive.content.toLong())
            primitive.content.toDoubleOrNull()?.isFinite() == true ->
                intent.putExtra(key, primitive.content.toDouble())
            else -> throw Refusal(1003, "intent-extra-invalid")
        }
    }

    private fun appLaunch(context: Context, args: JsonObject): JsonObject {
        val packageName = args.stringOr("package")
        if (packageName !in advertisedLaunchablePackages(context))
            throw Refusal(1003, "package-denied")
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw Refusal(1003, "package-unavailable")
        context.startActivity(launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        return emptyResult()
    }

    private data class LaunchableApp(val label: String, val packageName: String)

    private fun launchableApps(context: Context): List<LaunchableApp> {
        val manager = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return manager.queryIntentActivities(
            launcher, PackageManager.ResolveInfoFlags.of(0))
            .groupBy { it.activityInfo.packageName }
            .map { (packageName, rows) ->
                val label = rows.asSequence()
                    .map { it.loadLabel(manager).toString() }
                    .filter(String::isNotEmpty)
                    .firstOrNull { jcsStringBytes(it) <= 1024 }
                    ?: packageName
                LaunchableApp(label, packageName)
            }
            .sortedBy(LaunchableApp::packageName)
    }

    private fun advertisedLaunchablePackages(context: Context): List<String> =
        launchableApps(context).asSequence()
            .map(LaunchableApp::packageName)
            .take(MAX_REPORTED_LAUNCHABLE_PACKAGES)
            .toList()

    private fun appsList(context: Context, args: JsonObject): JsonObject {
        val cursor = args.stringOrNull("cursor")
        val limit = integralLongOrNull(args["limit"])?.toInt() ?: 100
        val remaining = launchableApps(context).filter { app ->
            cursor == null || app.packageName > cursor
        }
        val page = remaining.take(limit)
        return buildJsonObject {
            put("apps", buildJsonArray {
                page.forEach { app -> add(buildJsonObject {
                    put("label", app.label)
                    put("package", app.packageName)
                }) }
            })
            if (remaining.size > page.size) put("next_cursor", page.last().packageName)
        }
    }

    private fun vibrate(context: Context, args: JsonObject): JsonObject {
        val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
        if (!vibrator.hasVibrator()) throw Refusal(1003, "vibrator-unavailable")
        if ("ms" in args) {
            vibrator.vibrate(VibrationEffect.createOneShot(
                requireNotNull(integralLongOrNull(args["ms"])),
                VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            val pattern = requireNotNull(args.arrOrNull("pattern"))
                .map { requireNotNull(integralLongOrNull(it)) }.toLongArray()
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
        return emptyResult()
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = mutableListOf<SpeechRequest>()

    /** One bounded admission waiting on Android's asynchronous TTS setup. */
    private class SpeechRequest(val args: JsonObject) {
        private val latch = CountDownLatch(1)
        private var state = 0 // 0 pending, 1 completed, 2 cancelled
        private var accepted = false

        @Synchronized
        fun execute(operation: () -> Boolean) {
            if (state != 0) return
            accepted = operation()
            state = 1
            latch.countDown()
        }

        fun await(timeout: Long, unit: TimeUnit): Boolean? {
            if (latch.await(timeout, unit)) return synchronized(this) { accepted }
            return synchronized(this) {
                if (state == 1) accepted else {
                    state = 2
                    null
                }
            }
        }
    }

    private fun ttsSpeak(context: Context, args: JsonObject): JsonObject {
        // The EBP command actor is off-main. Blocking main here would prevent
        // TextToSpeech.onInit from ever concluding the admission.
        if (Looper.myLooper() == Looper.getMainLooper())
            throw Refusal(1003, "tts-main-thread")
        val request = SpeechRequest(args)
        if (!mainHandler.post { ttsSpeakOnMain(context.applicationContext, request) })
            throw Refusal(1003, "tts-unavailable")
        val accepted = request.await(5, TimeUnit.SECONDS)
        if (accepted != true) {
            // A timed-out request is cancelled before it can reach speak().
            mainHandler.post { pendingSpeech.remove(request) }
            throw Refusal(1003, if (accepted == null) "tts-timeout" else "tts-rejected")
        }
        return emptyResult()
    }

    private fun ttsSpeakOnMain(context: Context, request: SpeechRequest) {
        val current = tts
        when {
            current != null && ttsReady ->
                request.execute { speakNow(current, request.args) }
            current != null -> pendingSpeech.add(request)
            else -> {
                pendingSpeech.add(request)
                tts = TextToSpeech(context) { status -> mainHandler.post {
                    val ready = status == TextToSpeech.SUCCESS
                    ttsReady = ready
                    if (ready) tts?.let { engine ->
                        pendingSpeech.forEach { pending ->
                            pending.execute { speakNow(engine, pending.args) }
                        }
                    } else {
                        pendingSpeech.forEach { pending -> pending.execute { false } }
                        tts?.shutdown()
                        tts = null
                    }
                    pendingSpeech.clear()
                } }
            }
        }
    }

    private fun speakNow(engine: TextToSpeech, args: JsonObject): Boolean =
        engine.setPitch(
            args["pitch"]?.let { (it as JsonPrimitive).content.toFloat() } ?: 1f,
        ) == TextToSpeech.SUCCESS &&
            engine.setSpeechRate(
                args["rate"]?.let { (it as JsonPrimitive).content.toFloat() } ?: 1f,
            ) == TextToSpeech.SUCCESS &&
            engine.speak(
                args.stringOr("text"),
                TextToSpeech.QUEUE_ADD,
                null,
                "jetpacs-${System.nanoTime()}",
            ) == TextToSpeech.SUCCESS

    private fun volumeSet(context: Context, args: JsonObject): JsonObject {
        val audio = context.getSystemService(AudioManager::class.java)
        val stream = when (args.stringOr("stream")) {
            "music" -> AudioManager.STREAM_MUSIC
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call" -> AudioManager.STREAM_VOICE_CALL
            "system" -> AudioManager.STREAM_SYSTEM
            else -> throw Refusal(1003, "stream-unavailable")
        }
        val max = audio.getStreamMaxVolume(stream)
        val level = requireNotNull(integralLongOrNull(args["level"]))
            .coerceIn(0, max.toLong()).toInt()
        try {
            audio.setStreamVolume(stream, level, 0)
        } catch (_: SecurityException) {
            throw Refusal(1002, "permission-denied", PERMISSION_NOTIFICATION_POLICY)
        }
        return buildJsonObject { put("max", max) }
    }

    private fun ringerMode(context: Context, args: JsonObject): JsonObject {
        val manager = context.getSystemService(AudioManager::class.java)
        val mode = when (args.stringOr("mode")) {
            "normal" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            else -> throw Refusal(1003, "ringer-mode-unavailable")
        }
        if (mode == AudioManager.RINGER_MODE_SILENT &&
            !context.getSystemService(NotificationManager::class.java)
                .isNotificationPolicyAccessGranted) {
            throw Refusal(1002, "permission-denied", PERMISSION_NOTIFICATION_POLICY)
        }
        manager.ringerMode = mode
        return emptyResult()
    }

    private fun flashlight(context: Context, args: JsonObject): JsonObject {
        if (!granted(context, Manifest.permission.CAMERA))
            throw Refusal(1002, "permission-denied", PERMISSION_CAMERA)
        val manager = context.getSystemService(CameraManager::class.java)
        val camera = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw Refusal(1003, "flashlight-unavailable")
        manager.setTorchMode(camera, args.boolOr("on"))
        return emptyResult()
    }

    private fun mediaKey(context: Context, args: JsonObject): JsonObject {
        val key = when (args.stringOr("key")) {
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            "fast_forward" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            "rewind" -> KeyEvent.KEYCODE_MEDIA_REWIND
            else -> throw Refusal(1003, "media-key-unavailable")
        }
        context.getSystemService(AudioManager::class.java).apply {
            dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        }
        return emptyResult()
    }

    private fun clipboardRead(context: Context, maxFieldBytes: Long): JsonObject =
        onMainBlocking("clipboard.read") {
            val manager = context.getSystemService(ClipboardManager::class.java)
            val clip = manager.primaryClip
            val text = if (clip == null || clip.itemCount == 0) ""
            else clip.getItemAt(0).text?.toString()
                ?: throw Refusal(1003, "clipboard-too-large")
            if (jcsStringBytes(text) > maxFieldBytes)
                throw Refusal(1003, "clipboard-too-large")
            buildJsonObject { put("text", text) }
        }

    private fun brightnessSet(context: Context, args: JsonObject): JsonObject {
        if (!Settings.System.canWrite(context))
            throw Refusal(1002, "permission-denied", PERMISSION_WRITE_SETTINGS)
        val level = requireNotNull(integralLongOrNull(args["level"])).toInt()
        Settings.System.putInt(context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        if (!Settings.System.putInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, level)) {
            throw Refusal(1003, "brightness-write-failed")
        }
        return emptyResult()
    }

    private fun dndSet(context: Context, args: JsonObject): JsonObject {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.isNotificationPolicyAccessGranted)
            throw Refusal(1002, "permission-denied", PERMISSION_NOTIFICATION_POLICY)
        val filter = when (args.stringOr("mode")) {
            "on" -> NotificationManager.INTERRUPTION_FILTER_NONE
            "off" -> NotificationManager.INTERRUPTION_FILTER_ALL
            "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else -> throw Refusal(1003, "dnd-mode-unavailable")
        }
        manager.setInterruptionFilter(filter)
        return emptyResult()
    }

    private fun stateGet(args: JsonObject, state: DeviceStateAccess): JsonObject {
        val requested = args.arrOrNull("types")?.map { (it as JsonPrimitive).content }
            ?: state.stateTypes.sorted()
        val unavailable = LinkedHashMap<String, String>()
        val samples = LinkedHashMap<String, JsonObject>()
        requested.forEach { type ->
            state.sample(type)?.let { samples[type] = it }
                ?: run { unavailable[type] = state.unavailableReason(type) }
        }
        return buildJsonObject {
            put("states", JsonObject(samples))
            if (unavailable.isNotEmpty()) put("unavailable", buildJsonObject {
                unavailable.forEach { (type, reason) -> put(type, reason) }
            })
            args.arrOrNull("when")?.let { put("holds", state.predicatesHold(it)) }
        }
    }

    private fun triggerFire(args: JsonObject, fire: (String) -> Boolean): JsonObject {
        if (!fire(args.stringOr("id"))) throw Refusal(1003, "trigger-unavailable")
        return emptyResult()
    }

    private fun permissionMap(context: Context): JsonObject = buildJsonObject {
        put(PERMISSION_CAMERA, granted(context, Manifest.permission.CAMERA))
        put(PERMISSION_WRITE_SETTINGS, Settings.System.canWrite(context))
        put(PERMISSION_NOTIFICATION_POLICY,
            context.getSystemService(NotificationManager::class.java)
                .isNotificationPolicyAccessGranted)
        put(PERMISSION_POST_NOTIFICATIONS,
            granted(context, Manifest.permission.POST_NOTIFICATIONS))
        put(PERMISSION_EXACT_ALARMS,
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
        put(PERMISSION_READ_CALENDAR, granted(context, Manifest.permission.READ_CALENDAR))
        put(PERMISSION_RECEIVE_SMS, granted(context, Manifest.permission.RECEIVE_SMS))
        put(PERMISSION_READ_PHONE_STATE, granted(context, Manifest.permission.READ_PHONE_STATE))
        put(PERMISSION_READ_CALL_LOG, granted(context, Manifest.permission.READ_CALL_LOG))
        put(PERMISSION_BLUETOOTH_CONNECT,
            granted(context, Manifest.permission.BLUETOOTH_CONNECT))
    }

    private fun intentAllowlistJson(): JsonArray = buildJsonArray {
        INTENT_RULES.forEach { rule -> add(buildJsonObject {
            put("action", rule.action)
            put("mode", rule.mode)
            rule.packageName?.let { put("package", it) }
            rule.className?.let { put("class_name", it) }
            if (rule.schemes.isNotEmpty()) put("schemes", strings(rule.schemes.sorted()))
            if (rule.authorities.isNotEmpty())
                put("authorities", strings(rule.authorities.sorted()))
            if (rule.mimeTypes.isNotEmpty())
                put("mime_types", strings(rule.mimeTypes.sorted()))
            if (rule.extraKeys.isNotEmpty())
                put("extra_keys", strings(rule.extraKeys.sorted()))
            if (rule.trigger) put("trigger", true)
        }) }
    }

    private fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun strings(values: Iterable<String>): JsonArray =
        JsonArray(values.map(::JsonPrimitive))

    private fun jcsStringBytes(value: String): Long =
        JsonPrimitive(value).toString().toByteArray(Charsets.UTF_8).size.toLong()

    // Object member order does not affect encoded length; kotlinx uses the
    // same JSON string escaping as the wire serializer for these scalars.
    private fun encodedBytes(value: JsonObject): Long =
        value.toString().toByteArray(Charsets.UTF_8).size.toLong()

    private fun emptyResult() = JsonObject(emptyMap())

    private fun onMainBlocking(capability: String, block: () -> JsonObject): JsonObject {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result: JsonObject? = null
        var failure: Throwable? = null
        mainHandler.post {
            try {
                result = block()
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(2, TimeUnit.SECONDS))
            throw Refusal(1003, "$capability-timeout")
        failure?.let { throw it }
        return result ?: throw Refusal(1003, "$capability-no-result")
    }
}
