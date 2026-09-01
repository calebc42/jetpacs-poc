// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.util.Base64
import com.calebc42.ebp.wire.EbpJson
import com.calebc42.ebp.wire.SpecValidator
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64 as JvmBase64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A validated shortcut occurrence, ready for the context-less EBP funnel. */
internal data class PlatformShortcutAction(
    val descriptor: JsonObject,
    val injected: JsonObject,
)

/**
 * Pairing-scoped launcher shortcut ownership and trusted launch resolution.
 *
 * A shortcut Intent carries only an opaque platform ID and a random capability
 * token. The descriptor is reloaded from app-private storage and revalidated;
 * an exported launcher Activity therefore never interprets attacker-supplied
 * action JSON or follows a nested Intent.
 */
internal object PlatformShortcuts {
    const val ACTION_INVOKE =
        "com.calebc42.jetpacs.companion.action.INVOKE_SHORTCUT"
    private const val EXTRA_PLATFORM_ID =
        "com.calebc42.jetpacs.companion.extra.SHORTCUT_ID"
    private const val EXTRA_TOKEN =
        "com.calebc42.jetpacs.companion.extra.SHORTCUT_TOKEN"
    private const val PREFS = "ebp_platform_shortcuts_v1"
    private const val ACTION_PREFIX = "action:"
    private val random = SecureRandom()

    fun pin(
        context: Context,
        pairingIdentity: String,
        args: JsonObject,
        maxIconBytes: Long,
    ): JsonObject {
        val manager = context.getSystemService(ShortcutManager::class.java)
        if (!manager.isRequestPinShortcutSupported)
            throw IllegalStateException("pin-unsupported")
        val authoredId = args.reqString("id")
        val platformId = platformId(pairingIdentity, "pin", authoredId)
        val token = token()
        val info = buildShortcut(context, platformId, token, args, maxIconBytes)
        val prior = readEnvelope(context, platformId)
        if (!writeEnvelope(context, platformId, authoredId, token, args.reqAction()))
            throw IllegalStateException("shortcut-storage-failed")
        try {
            val alreadyPinned = manager.pinnedShortcuts.any { it.id == platformId }
            val accepted = if (alreadyPinned) {
                manager.updateShortcuts(listOf(info))
            } else {
                manager.requestPinShortcut(info, null)
            }
            if (!accepted) throw IllegalStateException("shortcut-platform-refused")
            return buildJsonObject { put("updated", alreadyPinned) }
        } catch (failure: Exception) {
            try {
                restoreEnvelope(context, platformId, prior)
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    fun replaceDynamic(
        context: Context,
        pairingIdentity: String,
        args: JsonObject,
        maxIconBytes: Long,
        protocolMax: Int,
    ): JsonObject {
        val manager = context.getSystemService(ShortcutManager::class.java)
        val rows = requireNotNull(args["shortcuts"] as? kotlinx.serialization.json.JsonArray)
        val ownerPrefix = ownerPrefix(pairingIdentity, "dyn")
        val prepared = rows.map { element ->
            val row = element as JsonObject
            val authoredId = row.reqString("id")
            val platformId = "$ownerPrefix$authoredId"
            val token = token()
            PreparedShortcut(
                platformId,
                authoredId,
                token,
                row.reqAction(),
                buildShortcut(context, platformId, token, row, maxIconBytes),
            )
        }
        val hostMax = manager.maxShortcutCountPerActivity
        if (prepared.size > protocolMax || prepared.size > hostMax)
            throw IllegalStateException("shortcut-capacity")
        val preserved = manager.dynamicShortcuts.filterNot { it.id.startsWith(ownerPrefix) }
        if (preserved.size + prepared.size > hostMax)
            throw IllegalStateException("shortcut-capacity")

        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = preferences.all
            .filterKeys { it.startsWith(ACTION_PREFIX + ownerPrefix) }
            .mapValues { it.value as? String }
        val editor = preferences.edit()
        old.keys.forEach(editor::remove)
        prepared.forEach { item ->
            editor.putString(
                ACTION_PREFIX + item.platformId,
                envelope(item.authoredId, item.token, item.descriptor).toString(),
            )
        }
        if (!editor.commit()) throw IllegalStateException("shortcut-storage-failed")

        val accepted = try {
            manager.setDynamicShortcuts(preserved + prepared.map { it.info })
        } catch (failure: Exception) {
            restoreDynamicEnvelopes(preferences, prepared, old)
            throw failure
        }
        if (!accepted) {
            restoreDynamicEnvelopes(preferences, prepared, old)
            throw IllegalStateException("shortcut-platform-refused")
        }
        return buildJsonObject { put("count", prepared.size) }
    }

    /** Resolve only our exact shortcut action and a stored, constant-time token. */
    fun resolveLaunch(
        context: Context,
        pairingIdentity: String,
        intent: Intent?,
    ): PlatformShortcutAction? {
        if (intent?.action != ACTION_INVOKE) return null
        // MainActivity is exported for the launcher. Treat even the type and
        // unparcelling of its extras as hostile input; malformed Bundles are
        // inert instead of crashing the process at this trust boundary.
        val platformId = runCatching { intent.getStringExtra(EXTRA_PLATFORM_ID) }
            .getOrNull()
            ?.takeIf { it.startsWith("ebp:") && it.length <= 256 }
            ?: return null
        if (!platformId.startsWith(ownerPrefix(pairingIdentity, "pin")) &&
            !platformId.startsWith(ownerPrefix(pairingIdentity, "dyn"))) return null
        val presentedToken = runCatching { intent.getStringExtra(EXTRA_TOKEN) }
            .getOrNull()
            ?.takeIf { it.length <= 128 }
            ?: return null
        val stored = readEnvelope(context, platformId) ?: return null
        val storedToken = stored.stringOrNull("token") ?: return null
        if (!MessageDigest.isEqual(
                storedToken.toByteArray(Charsets.UTF_8),
                presentedToken.toByteArray(Charsets.UTF_8),
            )) return null
        if (stored.keys != setOf("id", "token", "action")) return null
        val authoredId = stored.stringOrNull("id") ?: return null
        val descriptor = stored.objOrNull("action") ?: return null
        return runCatching {
            SpecValidator.validateContextlessRemoteAction(
                descriptor,
                "shortcut.action",
                injectedMembers = setOf("shortcut_id"),
            )
            PlatformShortcutAction(
                descriptor,
                buildJsonObject { put("shortcut_id", authoredId) },
            )
        }.getOrNull()
    }

    private data class PreparedShortcut(
        val platformId: String,
        val authoredId: String,
        val token: String,
        val descriptor: JsonObject,
        val info: ShortcutInfo,
    )

    private fun JsonObject.reqAction(): JsonObject =
        objOrNull("action") ?: throw IllegalStateException("shortcut-action-missing")

    private fun buildShortcut(
        context: Context,
        platformId: String,
        token: String,
        row: JsonObject,
        maxIconBytes: Long,
    ): ShortcutInfo {
        val launch = Intent(ACTION_INVOKE)
            .setComponent(ComponentName(context, MainActivity::class.java))
            .putExtra(EXTRA_PLATFORM_ID, platformId)
            .putExtra(EXTRA_TOKEN, token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return ShortcutInfo.Builder(context, platformId)
            .setShortLabel(row.reqString("label"))
            .setIntent(launch)
            .also { builder ->
                row.stringOrNull("long_label")?.let(builder::setLongLabel)
                shortcutIcon(context, row.stringOrNull("icon_png"), maxIconBytes)
                    ?.let(builder::setIcon)
            }
            .build()
    }

    private fun shortcutIcon(
        context: Context,
        encoded: String?,
        maxIconBytes: Long,
    ): Icon? {
        if (encoded == null) {
            val resource = context.applicationInfo.icon
            return resource.takeIf { it != 0 }?.let { Icon.createWithResource(context, it) }
        }
        val bytes = JvmBase64.getDecoder().decode(encoded)
        if (bytes.size.toLong() > maxIconBytes)
            throw IllegalStateException("shortcut-icon-too-large")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width.toLong() * height > 1_048_576L)
            throw IllegalStateException("shortcut-icon-invalid")
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("shortcut-icon-invalid")
        val bitmap = if (maxOf(decoded.width, decoded.height) <= 512) decoded else {
            val scale = 512f / maxOf(decoded.width, decoded.height)
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }
        return Icon.createWithAdaptiveBitmap(bitmap)
    }

    private fun ownerPrefix(identity: String, kind: String): String =
        "ebp:${identityHash(identity)}:$kind:"

    private fun platformId(identity: String, kind: String, authoredId: String): String =
        ownerPrefix(identity, kind) + authoredId

    private fun identityHash(identity: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun token(): String = ByteArray(16).also(random::nextBytes).let {
        Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun envelope(id: String, token: String, action: JsonObject) = buildJsonObject {
        put("id", id)
        put("token", token)
        put("action", action)
    }

    private fun readEnvelope(context: Context, platformId: String): JsonObject? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ACTION_PREFIX + platformId, null)
            ?.takeIf { it.toByteArray(Charsets.UTF_8).size <= 262_144 }
            ?: return null
        return runCatching { EbpJson.parseJsonElement(encoded) as? JsonObject }.getOrNull()
    }

    private fun writeEnvelope(
        context: Context,
        platformId: String,
        authoredId: String,
        token: String,
        descriptor: JsonObject,
    ): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(
            ACTION_PREFIX + platformId,
            envelope(authoredId, token, descriptor).toString(),
        )
        .commit()

    private fun restoreEnvelope(
        context: Context,
        platformId: String,
        prior: JsonObject?,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (prior == null) remove(ACTION_PREFIX + platformId)
            else putString(ACTION_PREFIX + platformId, prior.toString())
        }.commit().also { restored ->
            if (!restored) throw IllegalStateException("shortcut-rollback-failed")
        }
    }

    private fun restoreDynamicEnvelopes(
        preferences: android.content.SharedPreferences,
        prepared: List<PreparedShortcut>,
        old: Map<String, String?>,
    ) {
        val rollback = preferences.edit()
        prepared.forEach { rollback.remove(ACTION_PREFIX + it.platformId) }
        old.forEach { (key, value) -> value?.let { rollback.putString(key, it) } }
        if (!rollback.commit()) throw IllegalStateException("shortcut-rollback-failed")
    }
}
