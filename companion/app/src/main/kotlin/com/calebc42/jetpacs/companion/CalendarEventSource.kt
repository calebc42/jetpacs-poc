// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Permission-gated, event-driven `calendar.event` source.
 *
 * One Instances observer and one in-process next-boundary callback maintain a
 * global ongoing set. Registration-specific calendar/title/event filtering is
 * applied later by TriggerRuntime, so a replace-set never multiplies platform
 * observers. A bounded retry only detects permission/provider recovery;
 * process death remains an acknowledged observation gap under SPEC 21.5.
 */
internal class CalendarEventSource(
    private val context: Context,
    private val onSample: (JsonObject) -> Unit,
    private val onOccurrence: (JsonObject) -> Unit,
) {
    private data class Instance(
        val eventId: Long,
        val beginMs: Long,
        val endMs: Long,
        val title: String?,
        val calendar: String,
    ) {
        val key: String get() = "$eventId:$beginMs:$endMs"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private var ongoing: Map<String, Instance>? = null
    private val boundary = Runnable {
        if (observer == null) start() else refresh(emitEdges = true)
    }

    fun granted(): Boolean = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun start() {
        handler.removeCallbacks(boundary)
        if (observer != null) return
        if (!granted()) {
            // Permission may be restored while the process remains alive.
            // Re-arm from a silent baseline when it is; never fabricate edges
            // for the unavailable interval.
            ongoing = null
            handler.postDelayed(boundary, RETRY_MS)
            return
        }
        val next = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = refresh(emitEdges = true)
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(
                CalendarContract.Instances.CONTENT_URI,
                true,
                next,
            )
        }.isSuccess
        if (!registered) {
            // Permission may have changed between the check and registration,
            // or the provider may be temporarily unavailable. Fail closed and
            // recover from a new silent baseline.
            ongoing = null
            handler.postDelayed(boundary, RETRY_MS)
            return
        }
        observer = next
        refresh(emitEdges = false)
    }

    @Synchronized
    fun stop() {
        observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        observer = null
        handler.removeCallbacks(boundary)
        ongoing = null
    }

    /** Exact public state shape: filter detail remains inside this source. */
    @Synchronized
    fun sample(): JsonObject? {
        if (!granted()) return null
        val now = System.currentTimeMillis()
        val current = runCatching { query(now).first }.getOrNull() ?: return null
        return buildJsonObject { put("ongoing", current.isNotEmpty()) }
    }

    /** Predicate-specific query used by the shared SPEC 21.7 evaluator. */
    fun predicateHolds(predicate: JsonObject): Boolean? {
        if (predicate.stringOrNull("type") != "calendar.event") return null
        if (!granted()) return false
        val calendar = predicate.stringOrNull("calendar")
        val title = predicate.stringOrNull("title_contains")
        return runCatching {
            query(System.currentTimeMillis()).first.values.any { instance ->
                (calendar == null || calendar == instance.calendar) &&
                    (title == null || instance.title?.contains(title) == true)
            }
        }.getOrDefault(false)
    }

    @Synchronized
    private fun refresh(emitEdges: Boolean) {
        handler.removeCallbacks(boundary)
        if (!granted()) {
            ongoing = null
            handler.postDelayed(boundary, RETRY_MS)
            return
        }
        val now = System.currentTimeMillis()
        val queried = runCatching { query(now) }.getOrNull()
        if (queried == null) {
            // A transient provider failure is unevaluable. Drop the baseline
            // and retry so recovery is silent instead of manufacturing every
            // boundary that may have happened while the provider was down.
            ongoing = null
            handler.postDelayed(boundary, RETRY_MS)
            return
        }
        val (next, nextBoundary) = queried
        val prior = ongoing
        ongoing = next
        onSample(buildJsonObject { put("ongoing", next.isNotEmpty()) })
        if (emitEdges && prior != null) {
            (prior.keys - next.keys).sorted().forEach { key ->
                onOccurrence(prior.getValue(key).fireData("ended"))
            }
            (next.keys - prior.keys).sorted().forEach { key ->
                onOccurrence(next.getValue(key).fireData("started"))
            }
        }
        handler.postDelayed(boundary, (nextBoundary - now).coerceAtLeast(1_000L))
    }

    private fun query(now: Long): Pair<Map<String, Instance>, Long> {
        val windowEnd = now + LOOKAHEAD_MS
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.CALENDAR_ID,
        )
        val ongoing = LinkedHashMap<String, Instance>()
        var boundary = windowEnd
        val cursor = CalendarContract.Instances.query(
            context.contentResolver,
            projection,
            now - DAY_MS,
            windowEnd,
        ) ?: throw IllegalStateException("calendar provider returned no cursor")
        cursor.use {
            var scanned = 0
            while (it.moveToNext()) {
                if (++scanned > MAX_INSTANCES) {
                    throw IllegalStateException("calendar instance bound exceeded")
                }
                val title = it.getString(3)?.takeIf { value ->
                    value.toByteArray(Charsets.UTF_8).size.toLong() <=
                        CompanionStores.MAX_FIELD_BYTES
                }
                val instance = Instance(
                    eventId = it.getLong(0),
                    beginMs = it.getLong(1),
                    endMs = it.getLong(2),
                    title = title,
                    calendar = it.getLong(4).toString(),
                )
                if (instance.beginMs <= now && now < instance.endMs) {
                    ongoing[instance.key] = instance
                    boundary = minOf(boundary, instance.endMs)
                } else if (instance.beginMs > now) {
                    boundary = minOf(boundary, instance.beginMs)
                }
            }
        }
        return ongoing to boundary
    }

    private fun Instance.fireData(event: String) = buildJsonObject {
        put("event", event)
        // Internal matching member; TriggerRuntime consumes and strips it.
        put("calendar", calendar)
        title?.let { put("title", it) }
        put("begin_ms", beginMs)
        put("end_ms", endMs)
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1_000
        const val LOOKAHEAD_MS = 7L * DAY_MS
        const val RETRY_MS = 60_000L
        const val MAX_INSTANCES = 4_096
    }
}
