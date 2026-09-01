// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.calebc42.jetpacs.core.database.PairingRuntimeEntity
import com.calebc42.jetpacs.core.database.SurfaceRecordEntity
import com.calebc42.jetpacs.core.database.WidgetBindingEntity

internal const val WIDGET_STALE_REFRESH =
    "com.calebc42.jetpacs.companion.action.WIDGET_STALE_REFRESH"
private const val WIDGET_STALE_REQUEST_CODE = 0x575354
private const val TAG = "JetpacsWidgetLife"

internal data class WidgetStaleCandidate(
    val binding: WidgetBindingEntity,
    val runtime: PairingRuntimeEntity?,
    val surface: SurfaceRecordEntity?,
)

private fun plusSeconds(epochMs: Long, seconds: Long): Long? {
    if (epochMs < 0 || seconds <= 0 || seconds > (Long.MAX_VALUE - epochMs) / 1_000L) {
        return null
    }
    return epochMs + seconds * 1_000L
}

internal fun widgetStaleDeadline(
    runtime: PairingRuntimeEntity?,
    surface: SurfaceRecordEntity,
): Long? = plusSeconds(
    runtime?.readyDisconnectedAtEpochMs ?: return null,
    surface.staleAfterSeconds ?: return null,
)

/**
 * Return the next actual stale boundary. While READY, schedule a watchdog one
 * authored interval out: if Android kills the process, this already-installed
 * alarm revives the cold Room renderer and lets it infer the prior exit time.
 */
internal fun nextWidgetStaleRefreshAt(
    candidates: List<WidgetStaleCandidate>,
    hostWidgetIds: Set<Int>,
    readyConnected: Boolean,
    nowEpochMs: Long,
): Long? = candidates.asSequence()
    .filter { it.binding.appWidgetId in hostWidgetIds }
    .mapNotNull { candidate ->
        val surface = candidate.surface?.takeIf { it.present } ?: return@mapNotNull null
        val seconds = surface.staleAfterSeconds ?: return@mapNotNull null
        if (readyConnected) plusSeconds(nowEpochMs, seconds)
        else widgetStaleDeadline(candidate.runtime, surface)
    }
    .filter { it > nowEpochMs }
    .minOrNull()

/**
 * A present snapshot plus a null timer at cold process start means the prior
 * process died while READY. Prefer Android's self-only historical exit time;
 * the process-start instant is the conservative fallback when the OEM has no
 * exit record. A stale older exit is never applied to a newer snapshot.
 */
internal fun inferColdReadyDisconnectAt(
    currentDisconnectedAtEpochMs: Long?,
    latestAcceptedAtEpochMs: Long?,
    previousProcessExitAtEpochMs: Long?,
    processStartedAtEpochMs: Long,
): Long? {
    if (currentDisconnectedAtEpochMs != null || latestAcceptedAtEpochMs == null) return null
    val processStart = processStartedAtEpochMs.coerceAtLeast(0)
    return previousProcessExitAtEpochMs?.takeIf {
        it >= latestAcceptedAtEpochMs && it <= processStart
    } ?: processStart
}

internal object WidgetStaleScheduler {
    suspend fun reschedule(context: Context) {
        val app = context.applicationContext as JetpacsApplication
        val manager = AppWidgetManager.getInstance(app)
        val hostIds = manager.getAppWidgetIds(
            ComponentName(app, JetpacsWidgetProvider::class.java),
        ).toSet()
        val database = app.container.database
        val bindings = database.widgetDao().getBindings()
        val runtimes = mutableMapOf<String, PairingRuntimeEntity?>()
        val candidates = bindings.map { binding ->
            WidgetStaleCandidate(
                binding = binding,
                runtime = runtimes.getOrPut(binding.pairingId) {
                    database.pairingDao().getRuntime(binding.pairingId)
                },
                surface = database.surfaceDao().getRecord(
                    binding.pairingId,
                    binding.surfaceId,
                ),
            )
        }
        WidgetStaleAlarm.replace(
            app,
            nextWidgetStaleRefreshAt(
                candidates = candidates,
                hostWidgetIds = hostIds,
                readyConnected = app.bridge.hasReadySession(),
                nowEpochMs = System.currentTimeMillis().coerceAtLeast(0),
            ),
        )
    }

    suspend fun recoverAfterProcessStart(context: Context, processStartedAtEpochMs: Long) {
        val app = context.applicationContext as JetpacsApplication
        val previousExit = previousProcessExitAt(app)
        app.container.commandActor.execute {
            if (app.bridge.hasReadySession()) return@execute
            val database = app.container.database
            val pairingId = app.container.pairingId.value
            val runtime = database.pairingDao().getRuntime(pairingId) ?: return@execute
            val latestAccepted = database.surfaceDao().getRecords(pairingId)
                .asSequence()
                .filter { it.present }
                .maxOfOrNull(SurfaceRecordEntity::acceptedAtEpochMs)
            val inferred = inferColdReadyDisconnectAt(
                currentDisconnectedAtEpochMs = runtime.readyDisconnectedAtEpochMs,
                latestAcceptedAtEpochMs = latestAccepted,
                previousProcessExitAtEpochMs = previousExit,
                processStartedAtEpochMs = processStartedAtEpochMs,
            ) ?: return@execute
            database.pairingDao().setReadyDisconnectedAtIfNull(pairingId, inferred)
        }
    }

    private fun previousProcessExitAt(context: Context): Long? = runCatching {
        context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 8)
            .maxOfOrNull { it.timestamp }
    }.getOrNull()
}

private object WidgetStaleAlarm {
    fun replace(context: Context, triggerAtEpochMs: Long?) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            WIDGET_STALE_REQUEST_CODE,
            Intent(context, WidgetStaleReceiver::class.java)
                .setAction(WIDGET_STALE_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (triggerAtEpochMs == null) {
            alarmManager.cancel(pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtEpochMs,
                pendingIntent,
            )
        }
    }
}

/** Private explicit alarm endpoint; a valid delivery carries no authored data. */
class WidgetStaleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WIDGET_STALE_REFRESH) return
        val pending = goAsync()
        WidgetUpdateCoordinator.launch {
            try {
                WidgetUpdateCoordinator.updateAll(context)
            } catch (failure: Throwable) {
                Log.e(TAG, "Could not refresh stale widgets", failure)
            } finally {
                pending.finish()
            }
        }
    }
}
