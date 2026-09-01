// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.util.Log
import android.widget.RemoteViews
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.actionSendBroadcast
import com.calebc42.ebp.wire.dispatchSurfaceOccurrence
import com.calebc42.jetpacs.core.database.PairingRuntimeEntity
import com.calebc42.jetpacs.core.database.SurfaceRecordEntity
import com.calebc42.jetpacs.core.database.WidgetActionTokenEntity
import com.calebc42.jetpacs.core.database.WidgetBindingEntity
import com.calebc42.jetpacs.renderer.glance.GlanceActionResolver
import com.calebc42.jetpacs.renderer.glance.RenderGlanceWidget
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val WIDGET_ACTION_BROADCAST =
    "com.calebc42.jetpacs.companion.action.WIDGET_ACTION"
private const val WIDGET_ACTION_SCHEME = "jetpacs-widget-action"
private const val WIDGET_ACTION_AUTHORITY = "action"
private const val TOKEN_LIFETIME_MS = 365L * 24 * 60 * 60 * 1_000
private const val DEFAULT_WIDGET_WIDTH_DP = 180
private const val DEFAULT_WIDGET_HEIGHT_DP = 110
private const val TAG = "JetpacsWidget"

/** One generic provider for every pairing + arbitrary widget:name binding. */
class JetpacsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        async(context) { WidgetUpdateCoordinator.update(context, appWidgetIds) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        async(context) { WidgetUpdateCoordinator.update(context, intArrayOf(appWidgetId)) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        async(context) {
            val dao = context.jetpacsApp().container.database.widgetDao()
            appWidgetIds.forEach { dao.deleteBinding(it) }
        }
    }

    override fun onRestored(
        context: Context,
        oldWidgetIds: IntArray,
        newWidgetIds: IntArray,
    ) {
        // Restore is a tiny Room transaction and must finish before the host's
        // subsequent update asks the new IDs to render.
        runBlocking(Dispatchers.IO) {
            val dao = context.jetpacsApp().container.database.widgetDao()
            val now = System.currentTimeMillis().coerceAtLeast(0)
            oldWidgetIds.zip(newWidgetIds).forEach { (oldId, newId) ->
                dao.restoreBinding(oldId, newId, now)
            }
        }
        super.onRestored(context, oldWidgetIds, newWidgetIds)
    }

    private fun async(context: Context, block: suspend () -> Unit) {
        val pending = goAsync()
        WidgetUpdateCoordinator.launch {
            try {
                block()
            } catch (failure: Throwable) {
                Log.e(TAG, "Widget provider work failed", failure)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Explicit non-exported receiver; its only input is an opaque token URI. */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WIDGET_ACTION_BROADCAST) return
        val token = WidgetActionUri.parse(intent.data) ?: return
        val pending = goAsync()
        WidgetUpdateCoordinator.launch {
            try {
                when (val result = WidgetActionRouter.dispatch(context, token, confirmed = false)) {
                    is WidgetActionResult.NeedsConfirmation -> {
                        context.startActivity(
                            Intent(context, WidgetActionConfirmationActivity::class.java)
                                .setData(WidgetActionUri.create(token))
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                                ),
                        )
                    }
                    else -> Unit
                }
            } finally {
                pending.finish()
            }
        }
    }
}

internal object WidgetActionUri {
    private val tokenPattern = Regex("[0-9a-f]{32}")

    fun create(token: String): Uri = Uri.Builder()
        .scheme(WIDGET_ACTION_SCHEME)
        .authority(WIDGET_ACTION_AUTHORITY)
        .appendPath(token)
        .build()

    fun parse(uri: Uri?): String? {
        uri ?: return null
        if (uri.scheme != WIDGET_ACTION_SCHEME || uri.authority != WIDGET_ACTION_AUTHORITY) {
            return null
        }
        if (uri.query != null || uri.fragment != null || uri.userInfo != null) return null
        return uri.pathSegments.singleOrNull()?.takeIf(tokenPattern::matches)
    }
}

internal data class WidgetRenderSource(
    val spec: JsonObject,
    val revision: Long,
    val stale: Boolean,
)

internal fun widgetIsStale(
    runtime: PairingRuntimeEntity?,
    surface: SurfaceRecordEntity,
    nowEpochMs: Long,
): Boolean {
    val disconnectedAt = runtime?.readyDisconnectedAtEpochMs ?: return false
    val seconds = surface.staleAfterSeconds ?: return false
    if (seconds > (Long.MAX_VALUE - disconnectedAt) / 1_000L) return false
    return nowEpochMs >= disconnectedAt + seconds * 1_000L
}

internal fun widgetSize(options: Bundle): DpSize = widgetSize(
    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_WIDGET_WIDTH_DP),
    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, DEFAULT_WIDGET_HEIGHT_DP),
)

internal fun widgetSize(minWidthDp: Int, minHeightDp: Int): DpSize = DpSize(
    minWidthDp.coerceAtLeast(1).dp,
    minHeightDp.coerceAtLeast(1).dp,
)

internal object RemoteViewsParcelSizer {
    fun bytes(remoteViews: RemoteViews): Int {
        val parcel = Parcel.obtain()
        return try {
            remoteViews.writeToParcel(parcel, 0)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }
}

internal fun remoteViewsFitsBudget(bytes: Int): Boolean =
    bytes in 0..CompanionRenderer.widgetProfile.limits!!.maxRemoteViewsBytes

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
internal object WidgetUpdateCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val composer = GlanceRemoteViews()
    private val json = Json { ignoreUnknownKeys = false }

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    fun enqueueAll(context: Context) {
        launch { updateAll(context) }
    }

    fun enqueueSurface(context: Context, surfaceId: String) {
        launch {
            val app = context.jetpacsApp()
            val ids = app.container.database.widgetDao()
                .getBindings(app.container.pairingId.value, surfaceId)
                .map(WidgetBindingEntity::appWidgetId)
                .toIntArray()
            update(context, ids)
        }
    }

    suspend fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, JetpacsWidgetProvider::class.java),
        )
        update(context, ids)
    }

    suspend fun update(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.distinct().forEach { updateOne(context, it) }
    }

    private suspend fun updateOne(context: Context, appWidgetId: Int) {
        val app = context.jetpacsApp()
        val database = app.container.database
        val widgetDao = database.widgetDao()
        val binding = widgetDao.getBinding(appWidgetId)
        if (binding == null) {
            showStatus(context, appWidgetId, "Configure this Jetpacs widget")
            return
        }
        val surface = database.surfaceDao().getRecord(binding.pairingId, binding.surfaceId)
        val runtime = database.pairingDao().getRuntime(binding.pairingId)
        val source = surface?.toWidgetRenderSource(runtime, System.currentTimeMillis(), json)
        if (source == null) {
            widgetDao.deleteTokens(appWidgetId)
            showStatus(context, appWidgetId, "Waiting for ${binding.surfaceId}")
            return
        }
        val manager = AppWidgetManager.getInstance(context)
        val options = manager.getAppWidgetOptions(appWidgetId)
        val size = widgetSize(options)
        val resolver = WidgetTokenActionResolver(context, binding, source.revision)
        val remoteViews = runCatching {
            composer.compose(
                context = context,
                size = size,
                appWidgetOptions = options,
            ) {
                GlanceTheme {
                    RenderGlanceWidget(
                        spec = source.spec,
                        widthDp = size.width.value,
                        heightDp = size.height.value,
                        actions = resolver,
                        stale = source.stale,
                    )
                }
            }.remoteViews
        }.getOrElse { failure ->
            Log.e(TAG, "Glance composition failed for $appWidgetId", failure)
            widgetDao.deleteTokens(appWidgetId)
            showStatus(context, appWidgetId, "Widget content unavailable")
            return
        }
        val tokens = resolver.tokens()
        if (!remoteViewsFitsBudget(RemoteViewsParcelSizer.bytes(remoteViews))) {
            widgetDao.deleteTokens(appWidgetId)
            showStatus(context, appWidgetId, "Widget content is too large")
            return
        }

        // Stage new capabilities before publishing their PendingIntents. Old
        // tokens remain valid until the binder update succeeds, closing both
        // process-death windows without ever pointing at a missing token.
        if (tokens.isNotEmpty()) widgetDao.stageTokens(tokens)
        runCatching { manager.updateAppWidget(appWidgetId, remoteViews) }
            .onSuccess {
                if (tokens.isEmpty()) widgetDao.deleteTokens(appWidgetId)
                else widgetDao.deleteOtherTokens(appWidgetId, tokens.map { it.token })
            }
            .onFailure { failure ->
                Log.e(TAG, "RemoteViews update failed for $appWidgetId", failure)
            }
        widgetDao.deleteExpiredTokens(System.currentTimeMillis().coerceAtLeast(0))
    }

    private fun showStatus(context: Context, appWidgetId: Int, text: String) {
        val views = RemoteViews(context.packageName, R.layout.jetpacs_widget_status)
        views.setTextViewText(R.id.jetpacs_widget_status_text, text)
        runCatching {
            AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, views)
        }
    }
}

internal fun SurfaceRecordEntity.toWidgetRenderSource(
    runtime: PairingRuntimeEntity?,
    nowEpochMs: Long,
    json: Json,
): WidgetRenderSource? {
    if (!present) return null
    val liveSpec = specJson ?: return null
    val stale = widgetIsStale(runtime, this, nowEpochMs)
    val selected = if (stale) staleSpecJson ?: liveSpec else liveSpec
    val spec = runCatching { json.parseToJsonElement(selected) as? JsonObject }.getOrNull()
        ?: return null
    return WidgetRenderSource(spec, revision, stale)
}

internal fun widgetTokenMatches(
    row: WidgetActionTokenEntity,
    binding: WidgetBindingEntity?,
    surface: SurfaceRecordEntity?,
    nowEpochMs: Long,
): Boolean =
    row.expiresAtEpochMs > nowEpochMs &&
        binding != null &&
        binding.pairingId == row.pairingId &&
        binding.surfaceId == row.surfaceId &&
        surface != null &&
        surface.present &&
        surface.pairingId == row.pairingId &&
        surface.surfaceId == row.surfaceId &&
        surface.revision == row.revision

private class WidgetTokenActionResolver(
    private val context: Context,
    private val binding: WidgetBindingEntity,
    private val revision: Long,
) : GlanceActionResolver {
    private val random = SecureRandom()
    private val byOccurrence = linkedMapOf<String, WidgetActionTokenEntity>()
    private val createdAt = System.currentTimeMillis().coerceAtLeast(0)

    override fun resolve(path: String, descriptor: JsonObject): Action {
        val occurrence = "$path\u0000$descriptor"
        val row = byOccurrence.getOrPut(occurrence) {
            WidgetActionTokenEntity(
                token = randomToken(),
                appWidgetId = binding.appWidgetId,
                pairingId = binding.pairingId,
                surfaceId = binding.surfaceId,
                revision = revision,
                descriptorJson = descriptor.toString(),
                createdAtEpochMs = createdAt,
                expiresAtEpochMs = createdAt + TOKEN_LIFETIME_MS,
            )
        }
        return actionSendBroadcast(
            Intent(context, WidgetActionReceiver::class.java)
                .setAction(WIDGET_ACTION_BROADCAST)
                .setData(WidgetActionUri.create(row.token)),
        )
    }

    fun tokens(): List<WidgetActionTokenEntity> = byOccurrence.values.toList()

    private fun randomToken(): String = ByteArray(16)
        .also(random::nextBytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal data class WidgetConfirmation(
    val text: String,
    val title: String? = null,
    val icon: String? = null,
    val confirmLabel: String? = null,
    val dismissLabel: String? = null,
)

internal sealed interface WidgetActionResult {
    data object Invalid : WidgetActionResult
    data object Dispatched : WidgetActionResult
    data class NeedsConfirmation(val face: WidgetConfirmation) : WidgetActionResult
}

private data class ValidWidgetAction(
    val token: WidgetActionTokenEntity,
    val descriptor: JsonObject,
)

internal object WidgetActionRouter {
    private val json = Json { ignoreUnknownKeys = false }

    suspend fun dispatch(
        context: Context,
        opaqueToken: String,
        confirmed: Boolean,
    ): WidgetActionResult {
        val app = context.jetpacsApp()
        val action = load(app, opaqueToken) ?: return WidgetActionResult.Invalid
        confirmation(action.descriptor)?.let { face ->
            if (!confirmed) return WidgetActionResult.NeedsConfirmation(face)
        }
        val descriptor = action.descriptor
        if ("builtin" in descriptor) {
            if (descriptor.string("builtin") != "surface.open") return WidgetActionResult.Invalid
            openSurface(context, descriptor.string("surface"))
            return WidgetActionResult.Dispatched
        }
        if ("action" !in descriptor) return WidgetActionResult.Invalid
        var safelyAdmitted = false
        app.container.commandActor.execute {
            dispatchSurfaceOccurrence(
                queue = app.stores.queue(),
                maxEventBytes = CompanionStores.MAX_EVENT_BYTES,
                descriptor = descriptor,
                surface = action.token.surfaceId,
                revisionSeen = action.token.revision,
                live = app.stores.liveSession,
            ) { status, _ -> safelyAdmitted = status != null }
        }
        descriptor.string("open_surface").takeIf(String::isNotBlank)?.let {
            openSurface(context, it)
        }
        if (safelyAdmitted && descriptor.string("when_offline") == "wake") {
            JetpacsBridgeService.enable(context)
            openAndroidEmacs(context)
        }
        return WidgetActionResult.Dispatched
    }

    suspend fun confirmation(context: Context, opaqueToken: String): WidgetConfirmation? =
        load(context.jetpacsApp(), opaqueToken)?.descriptor?.let(::confirmation)

    private suspend fun load(app: JetpacsApplication, opaqueToken: String): ValidWidgetAction? {
        val database = app.container.database
        val row = database.widgetDao().getToken(opaqueToken) ?: return null
        val now = System.currentTimeMillis().coerceAtLeast(0)
        val binding = database.widgetDao().getBinding(row.appWidgetId)
        val surface = database.surfaceDao().getRecord(row.pairingId, row.surfaceId)
        if (!widgetTokenMatches(row, binding, surface, now)) return null
        val descriptor = runCatching {
            json.parseToJsonElement(row.descriptorJson) as? JsonObject
        }.getOrNull() ?: return null
        return ValidWidgetAction(row, descriptor)
    }

    private fun confirmation(descriptor: JsonObject): WidgetConfirmation? {
        val raw = descriptor["confirm"] ?: return null
        if (raw is JsonPrimitive && raw.isString) {
            return WidgetConfirmation(text = raw.content)
        }
        val face = raw as? JsonObject ?: return null
        return WidgetConfirmation(
            text = face.string("text"),
            title = face.string("title").takeIf(String::isNotBlank),
            icon = face.string("icon").takeIf(String::isNotBlank),
            confirmLabel = face.string("confirm_label").takeIf(String::isNotBlank),
            dismissLabel = face.string("dismiss_label").takeIf(String::isNotBlank),
        )
    }

    private fun openSurface(context: Context, surface: String) {
        if (!Regex("(app|companion):[A-Za-z0-9][A-Za-z0-9._:/-]*").matches(surface)) return
        val app = context.jetpacsApp()
        app.requestSurfaceOpenFromPlatform(surface)
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }
}

private fun JsonObject.string(member: String): String =
    (this[member] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

private fun Context.jetpacsApp(): JetpacsApplication =
    applicationContext as JetpacsApplication
