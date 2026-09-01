// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.Manifest
import android.app.KeyguardManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import com.calebc42.ebp.wire.StatePredicateEvaluator
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Process-owned Android observation layer for the complete SPEC 21 catalog.
 *
 * Platform callbacks only produce exact state samples or bounded occurrence
 * data. TriggerRuntime remains the owner of registration matching, silent
 * baselines, gates, throttle, privacy projection, durability, and delivery.
 */
internal class TriggerSources(
    context: Context,
    private val onSample: (String, JsonObject) -> Unit,
    private val onExternal: (String, JsonObject) -> Unit,
) : DeviceStateAccess {
    private val app = context.applicationContext
    private val current = ConcurrentHashMap<String, JsonObject>()
    @Volatile private var started = false

    override val stateTypes: Set<String> = AppCapabilities.STATE_TYPES.toSet()

    private val calendar = CalendarEventSource(
        app,
        onSample = { publish("calendar.event", it) },
        onOccurrence = { onExternal("calendar.event", it) },
    )
    private val evaluator = StatePredicateEvaluator(
        now = System::currentTimeMillis,
        zone = { ZoneId.systemDefault() },
        stateProvider = ::sample,
        predicateProvider = calendar::predicateHolds,
    )

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> publishBattery(intent)
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED ->
                    // These edge broadcasts do not promise the sticky
                    // BATTERY_CHANGED extras. Re-read that authoritative
                    // snapshot instead of interpreting an absent PLUGGED as
                    // a false disconnect.
                    batteryIntent()?.let(::publishBattery)
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_USER_PRESENT -> sample("screen")?.let { publish("screen", it) }
                Intent.ACTION_AIRPLANE_MODE_CHANGED ->
                    sample("airplane")?.let { publish("airplane", it) }
                WifiManager.WIFI_STATE_CHANGED_ACTION ->
                    sample("wifi.enabled")?.let { publish("wifi.enabled", it) }
                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    sample("bluetooth.enabled")?.let { publish("bluetooth.enabled", it) }
                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> publishCall(intent)
            }
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            val event = when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED -> "added"
                Intent.ACTION_PACKAGE_REMOVED -> "removed"
                else -> return
            }
            val packageName = intent.data?.schemeSpecificPart
                ?.takeIf { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 512 }
                ?: return
            onExternal("package", buildJsonObject {
                put("event", event)
                put("package", packageName)
            })
        }
    }

    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshHeadset()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshHeadset()
        }
    }

    private val networkOccurrences = NetworkOccurrenceBook<Network>()
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkOccurrences.available(network).forEach(::emitNetworkOccurrence)
            refreshNetworkState()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val transport = primaryTransport(caps)
            networkOccurrences.capabilities(network, transport)
                .forEach(::emitNetworkOccurrence)
            refreshNetworkState()
        }

        override fun onLost(network: Network) {
            networkOccurrences.lost(network).forEach(::emitNetworkOccurrence)
            refreshNetworkState()
        }
    }

    /** Fresh current state for gates and `state.get`; failures are unavailable. */
    override fun sample(type: String): JsonObject? = runCatching {
        when (type) {
            "power" -> batteryIntent()?.let(::powerSample)
            "battery.level" -> batteryIntent()?.let(::batterySample)
            "screen" -> screenSample()
            "airplane" -> buildJsonObject {
                put("state", if (Settings.Global.getInt(
                        app.contentResolver,
                        Settings.Global.AIRPLANE_MODE_ON,
                        0,
                    ) != 0) "on" else "off")
            }
            "network" -> networkSample()
            "headset" -> headsetSample()
            "wifi.enabled" -> buildJsonObject {
                val wifi = app.getSystemService(WifiManager::class.java)
                put("enabled", wifi.isWifiEnabled)
            }
            "bluetooth.enabled" -> {
                if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) return null
                val adapter = app.getSystemService(BluetoothManager::class.java).adapter
                    ?: return null
                buildJsonObject { put("enabled", adapter.isEnabled) }
            }
            "calendar.event" -> calendar.sample()
            "call.state" -> callSample()
            else -> null
        }
    }.getOrNull()

    /** Compatibility name used by the process store composition root. */
    fun currentState(type: String): JsonObject? = sample(type)

    override fun unavailableReason(type: String): String = when (type) {
        "calendar.event" -> if (!calendar.granted()) "read_calendar" else "calendar_unavailable"
        "call.state" -> if (!granted(Manifest.permission.READ_PHONE_STATE)) {
            "read_phone_state"
        } else "telephony_unavailable"
        "bluetooth.enabled" -> if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            "bluetooth_connect"
        } else "bluetooth_unavailable"
        else -> "state_unavailable"
    }

    override fun predicatesHold(predicates: JsonArray): Boolean =
        evaluator.allHold(predicates)

    fun predicateHolds(predicate: JsonObject): Boolean? =
        if (predicate.stringOrNull("type") == "calendar.event") {
            calendar.predicateHolds(predicate)
        } else null

    @Synchronized
    fun start() {
        if (started) return
        started = true
        primeNetworkBaseline()
        // Seed every available level before callbacks are installed. Sticky
        // registrations and the initial default-network callback then compare
        // equal and cannot manufacture a transition at arm time.
        stateTypes.sorted().forEach { type -> sample(type)?.let { publish(type, it) } }
        val system = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            // Register the protected action even while permission is absent;
            // Android withholds delivery until READ_PHONE_STATE is granted,
            // after which this same exact filter can arm without a restart.
            addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        }
        app.registerReceiver(systemReceiver, system, Context.RECEIVER_EXPORTED)
        app.registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }, Context.RECEIVER_EXPORTED)
        app.getSystemService(AudioManager::class.java)
            .registerAudioDeviceCallback(audioCallback, Handler(Looper.getMainLooper()))
        app.getSystemService(ConnectivityManager::class.java)
            .registerDefaultNetworkCallback(networkCallback)
        calendar.start()
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        runCatching { app.unregisterReceiver(systemReceiver) }
        runCatching { app.unregisterReceiver(packageReceiver) }
        runCatching {
            app.getSystemService(AudioManager::class.java)
                .unregisterAudioDeviceCallback(audioCallback)
        }
        runCatching {
            app.getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(networkCallback)
        }
        calendar.stop()
        networkOccurrences.clear()
    }

    private fun publish(type: String, sample: JsonObject) {
        val prior = current.put(type, sample)
        if (prior != sample) onSample(type, sample)
    }

    private fun publishBattery(intent: Intent) {
        batterySample(intent)?.let { publish("battery.level", it) }
        publish("power", powerSample(intent))
    }

    private fun batteryIntent(): Intent? =
        app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun batterySample(intent: Intent): JsonObject? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return buildJsonObject { put("level", (level * 100 / scale).coerceIn(0, 100)) }
    }

    private fun powerSample(intent: Intent): JsonObject {
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return buildJsonObject {
            put("state", if (plugged == 0) "disconnected" else "connected")
            when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> put("plug", "ac")
                BatteryManager.BATTERY_PLUGGED_USB -> put("plug", "usb")
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> put("plug", "wireless")
            }
        }
    }

    private fun screenSample(): JsonObject {
        val power = app.getSystemService(PowerManager::class.java)
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        val state = when {
            !power.isInteractive -> "off"
            keyguard.isKeyguardLocked -> "on"
            else -> "unlocked"
        }
        return buildJsonObject { put("state", state) }
    }

    private fun headsetSample(): JsonObject {
        val wired = app.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { device -> device.type in WIRED_DEVICE_TYPES }
        return buildJsonObject {
            put("state", if (wired == null) "unplugged" else "plugged")
            wired?.productName?.toString()?.takeIf { value ->
                value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size.toLong() <=
                    CompanionStores.MAX_FIELD_BYTES
            }?.let {
                put("name", it)
            }
        }
    }

    private fun refreshHeadset() = sample("headset")?.let { publish("headset", it) }

    private fun networkSample(): JsonObject {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities)
        val transports = capabilities?.let(::transports).orEmpty()
        return buildJsonObject {
            put("connected", capabilities != null)
            put("transports", JsonArray(transports.map(::JsonPrimitive)))
        }
    }

    private fun primeNetworkBaseline() {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val active = manager.activeNetwork ?: return
        val transport = manager.getNetworkCapabilities(active)?.let(::primaryTransport)
        networkOccurrences.prime(mapOf(active to transport))
    }

    private fun refreshNetworkState() {
        runCatching(::networkSample).getOrNull()?.let { publish("network", it) }
    }

    private fun emitNetworkOccurrence(occurrence: NetworkOccurrence) {
        onExternal("network", buildJsonObject {
            put("event", occurrence.event)
            occurrence.transport?.let { put("transport", it) }
        })
    }

    private fun transports(capabilities: NetworkCapabilities): List<String> =
        TRANSPORTS.filter { (_, platform) -> capabilities.hasTransport(platform) }
            .map { it.first }

    private fun primaryTransport(capabilities: NetworkCapabilities): String? =
        transports(capabilities).firstOrNull()

    private fun callSample(): JsonObject? {
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return null
        @Suppress("DEPRECATION")
        val state = when (app.getSystemService(TelephonyManager::class.java).callState) {
            TelephonyManager.CALL_STATE_RINGING -> "ringing"
            TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
            else -> "idle"
        }
        return buildJsonObject { put("state", state) }
    }

    private fun publishCall(intent: Intent) {
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return
        val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> "ringing"
            TelephonyManager.EXTRA_STATE_OFFHOOK -> "offhook"
            TelephonyManager.EXTRA_STATE_IDLE -> "idle"
            else -> return
        }
        val sample = buildJsonObject {
            put("state", state)
            if (granted(Manifest.permission.READ_CALL_LOG)) {
                @Suppress("DEPRECATION")
                intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                    ?.takeIf { value ->
                        value.isNotEmpty() &&
                            value.toByteArray(Charsets.UTF_8).size.toLong() <=
                                CompanionStores.MAX_FIELD_BYTES
                    }
                    ?.let { put("number", it) }
            }
        }
        publish("call.state", sample)
    }

    private fun granted(permission: String): Boolean =
        app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        val WIRED_DEVICE_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        val TRANSPORTS = listOf(
            "wifi" to NetworkCapabilities.TRANSPORT_WIFI,
            "cellular" to NetworkCapabilities.TRANSPORT_CELLULAR,
            "ethernet" to NetworkCapabilities.TRANSPORT_ETHERNET,
            "vpn" to NetworkCapabilities.TRANSPORT_VPN,
            "bluetooth" to NetworkCapabilities.TRANSPORT_BLUETOOTH,
        )
    }
}

/**
 * Tracks platform network identities separately from aggregate connection
 * state. This preserves same-transport handovers while suppressing the
 * callback Android emits for the network that existed when observation armed.
 */
internal class NetworkOccurrenceBook<K> {
    private val observed = LinkedHashMap<K, String?>()
    private val awaitingCapabilities = LinkedHashSet<K>()

    @Synchronized
    fun prime(networks: Map<K, String?>) {
        observed.clear()
        observed.putAll(networks)
        awaitingCapabilities.clear()
    }

    /** `onAvailable` is completed by the following capabilities callback. */
    @Synchronized
    fun available(network: K): List<NetworkOccurrence> {
        if (network !in observed) {
            observed[network] = null
            awaitingCapabilities += network
        }
        return emptyList()
    }

    @Synchronized
    fun capabilities(network: K, transport: String?): List<NetworkOccurrence> {
        if (network !in observed) awaitingCapabilities += network
        observed[network] = transport
        return if (awaitingCapabilities.remove(network)) {
            listOf(NetworkOccurrence("available", transport))
        } else emptyList()
    }

    @Synchronized
    fun lost(network: K): List<NetworkOccurrence> {
        if (network !in observed) return emptyList()
        val transport = observed.remove(network)
        return if (awaitingCapabilities.remove(network)) {
            // This defensive path retains both platform occurrences if an
            // implementation violates Android's available/capabilities order.
            listOf(
                NetworkOccurrence("available", transport),
                NetworkOccurrence("lost", transport),
            )
        } else listOf(NetworkOccurrence("lost", transport))
    }

    @Synchronized
    fun clear() {
        observed.clear()
        awaitingCapabilities.clear()
    }
}

internal data class NetworkOccurrence(
    val event: String,
    val transport: String?,
)
