// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.ceil
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesignModelPerformanceInstrumentedTest {
    @After
    fun clearCache() {
        DesignModel.clearCacheForTest()
    }

    @Test
    fun maximumCollectionScopeCompilationP95StaysWithinOneFrame() {
        repeat(WARMUP_ITERATIONS) { index ->
            DesignModel.compileScope(maximumScope(index), path = "benchmark")
        }

        val cacheHitNanos = LongArray(MEASURED_ITERATIONS)
        var fixtureBytes = 0
        val elapsedNanos = LongArray(MEASURED_ITERATIONS) { index ->
            val scope = maximumScope(index + WARMUP_ITERATIONS)
            val startedAt = SystemClock.elapsedRealtimeNanos()
            val compiled = DesignModel.compileScope(scope, path = "benchmark")
            val elapsed = SystemClock.elapsedRealtimeNanos() - startedAt
            val cacheStartedAt = SystemClock.elapsedRealtimeNanos()
            DesignModel.compileScope(scope, path = "benchmark")
            cacheHitNanos[index] = SystemClock.elapsedRealtimeNanos() - cacheStartedAt
            val bytes = compiled.configurationBytes
            fixtureBytes = bytes
            assertTrue("maximum-scope fixture is not representative: $bytes bytes", bytes >= 60 * 1024)
            assertTrue("maximum-scope fixture exceeds the wire limit: $bytes bytes", bytes <= DesignModel.MAX_CONFIGURATION_BYTES)
            elapsed
        }.sortedArray()

        val p95Index = ceil(elapsedNanos.size * 0.95).toInt() - 1
        val p95Nanos = elapsedNanos[p95Index]
        val p95Millis = p95Nanos / 1_000_000.0
        val p50Millis = elapsedNanos[elapsedNanos.size / 2] / 1_000_000.0
        val sortedCacheHits = cacheHitNanos.sortedArray()
        val cacheP50Millis = sortedCacheHits[sortedCacheHits.size / 2] / 1_000_000.0
        val cacheP95Millis = sortedCacheHits[p95Index] / 1_000_000.0
        Log.i(
            TAG,
            "maximum_scope_bytes=$fixtureBytes compile_p50_ms=$p50Millis " +
                "compile_p95_ms=$p95Millis cache_hit_p50_ms=$cacheP50Millis " +
                "cache_hit_p95_ms=$cacheP95Millis samples=${elapsedNanos.size}",
        )
        assertTrue(
            "maximum-scope compile p50/p95 was $p50Millis/$p95Millis ms " +
                "(cache hit $cacheP50Millis/$cacheP95Millis ms; $fixtureBytes bytes)",
            p95Nanos <= MAX_P95_NANOS,
        )
    }

    @Test
    fun nearWireLimitScopeCacheHitP95StaysWithinOneFrame() {
        val scope = maximumScope(sample = 0, dense = true)
        val compiled = DesignModel.compileScope(scope, path = "benchmark")
        assertTrue(compiled.configurationBytes >= 224 * 1024)
        assertTrue(compiled.configurationBytes <= DesignModel.MAX_CONFIGURATION_BYTES)
        repeat(WARMUP_ITERATIONS) { DesignModel.compileScope(scope, path = "benchmark") }

        val elapsedNanos = LongArray(MEASURED_ITERATIONS) {
            val startedAt = SystemClock.elapsedRealtimeNanos()
            DesignModel.compileScope(scope, path = "benchmark")
            SystemClock.elapsedRealtimeNanos() - startedAt
        }.sortedArray()
        val p95Index = ceil(elapsedNanos.size * 0.95).toInt() - 1
        val p95Nanos = elapsedNanos[p95Index]
        val p95Millis = p95Nanos / 1_000_000.0
        Log.i(
            TAG,
            "near_wire_limit_scope_bytes=${compiled.configurationBytes} " +
                "cache_hit_p95_ms=$p95Millis samples=${elapsedNanos.size}",
        )
        assertTrue("near-wire-limit cache-hit p95 was $p95Millis ms", p95Nanos <= MAX_P95_NANOS)
    }

    private fun maximumScope(sample: Int, dense: Boolean = false): JsonObject {
        val tokens = LinkedHashMap<String, JsonElement>(TOKEN_COUNT)
        repeat(TOKEN_COUNT) { index ->
            val color = if (index == 0) sample and 0x00ffffff else index
            tokens[id("t", index)] = JsonObject(
                mapOf(
                    "kind" to JsonPrimitive("color"),
                    "value" to JsonPrimitive("#%06x".format(color)),
                ),
            )
        }

        val motions = LinkedHashMap<String, JsonElement>(MOTION_COUNT)
        repeat(MOTION_COUNT) { index ->
            motions[id("m", index)] = JsonObject(
                mapOf(
                    "duration_ms" to JsonPrimitive(index),
                    "easing" to JsonPrimitive("ease-in-out"),
                ),
            )
        }

        val styles = LinkedHashMap<String, JsonElement>(STYLE_COUNT)
        repeat(STYLE_COUNT) { index ->
            val states = if (dense) listOf("disabled", "selected", "pressed", "hovered") else emptyList()
            val rules = states.mapIndexed { ruleIndex, state ->
                JsonObject(
                    mapOf(
                        "state" to JsonPrimitive(state),
                        "properties" to colorProperties(index + ruleIndex + 1),
                    ),
                )
            }
            styles[id("s", index)] = JsonObject(
                mapOf(
                    "properties" to colorProperties(index),
                    "rules" to JsonArray(rules),
                    "motion" to JsonPrimitive(id("m", index % MOTION_COUNT)),
                ),
            )
        }

        return JsonObject(
            mapOf(
                "tokens" to JsonObject(tokens),
                "motions" to JsonObject(motions),
                "styles" to JsonObject(styles),
                "children" to JsonArray(emptyList()),
            ),
        )
    }

    private fun colorProperties(seed: Int): JsonObject = JsonObject(
        mapOf(
            "background_color" to tokenReference(seed),
            "border_color" to tokenReference(seed + 1),
            "content_color" to tokenReference(seed + 2),
        ),
    )

    private fun tokenReference(index: Int): JsonObject = JsonObject(
        mapOf(
            "kind" to JsonPrimitive("token"),
            "value" to JsonPrimitive(id("t", index % TOKEN_COUNT)),
        ),
    )

    private fun id(prefix: String, index: Int): String = "$prefix%03d".format(index)

    private companion object {
        const val TAG = "DesignModelPerformance"
        const val TOKEN_COUNT = 256
        const val STYLE_COUNT = 256
        const val MOTION_COUNT = 64
        const val WARMUP_ITERATIONS = 20
        const val MEASURED_ITERATIONS = 40
        const val MAX_P95_NANOS = 16_000_000L
    }
}
