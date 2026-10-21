// SPDX-License-Identifier: GPL-3.0-or-later
// LD-11: a decoded-image cache with an aggregate byte budget and in-flight
// coalescing. RenderImage used produceState(null, url), so leaving composition
// discarded the bitmap and cancelled the load; RenderLazyColumn disposes
// off-screen items, so scrolling re-ran the whole loadHttps path — fresh DNS,
// TLS, up to 8 MiB re-downloaded, full decode — and ten identical avatars were
// ten concurrent sockets. This cache holds decoded images (so it lives in
// the renderer, not wire/), under an LRU byte budget, and coalesces concurrent
// requests for one URL into a single fetch.
//
// SPEC 17.2: "any such cache MUST be app-private, scoped to the pairing
// identity, and erased on pairing revocation (Section 9.1)." So the KEY
// carries the pairing identity — never url alone — and a change of identity
// or an explicit revocation erases everything the previous identity fetched.
// Unlike Emacs's image cache, a theme.set MUST NOT invalidate it.
//
// It completes LD-12 too: ImageLoader.Semaphore(3) bounds concurrent DECODES;
// this budget bounds RETAINED decoded bytes. Eviction drops the cache's
// reference only, so an image still held by an on-screen Image is freed by GC
// when that Image leaves, never mid-draw.
package com.calebc42.jetpacs.material3

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object ImageCache {

    /** SPEC 17.2: the pairing identity is part of the key, so one identity can
     * never be served content another fetched. `null` = no session yet. */
    private data class Key(
        val identity: String?,
        val url: String,
        val limits: ImageLoader.Limits,
    )

    private val mutex = Mutex()
    // Access-order LinkedHashMap = LRU: get() moves an entry to the front.
    private val lru = object : LinkedHashMap<Key, ImageBitmap>(16, 0.75f, true) {}
    private val inFlight = HashMap<Key, Deferred<ImageBitmap?>>()
    private var usedBytes = 0L

    // Default until configure() runs; overwritten from ActivityManager's
    // per-app memory class. A soft budget on what the cache RETAINS.
    @Volatile private var budgetBytes = 32L * 1024 * 1024

    @Volatile private var identity: String? = null

    // SPEC 9.1: a load that was already in flight when an erasure happened
    // MUST NOT reinstall its bytes afterwards. Every admission carries the
    // generation it started under; a bump makes older results non-admissible.
    private var generation = 0L

    // The cache OWNS the fetch, so one caller leaving composition cancels only
    // its own await(), never the shared load the other callers still want.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set the retention budget, e.g. `memoryClass MiB / 8`. */
    fun configure(budgetBytes: Long) {
        this.budgetBytes = budgetBytes.coerceAtLeast(4L * 1024 * 1024)
    }

    /**
     * SPEC 17.2/9.1: bind the cache to the authenticated pairing identity.
     * A DIFFERENT identity erases everything the previous one fetched — the
     * §9.1 "empty state partition" a re-pairing is entitled to, enforced here
     * rather than trusted to a revocation call that may never come.
     */
    suspend fun setIdentity(pairingId: String?) {
        if (identity == pairingId) return
        identity = pairingId
        clear()
    }

    /** The decoded image for [url], from cache or a coalesced fetch; null on
     * any guard failure (the caller shows a placeholder). */
    suspend fun get(url: String, limits: ImageLoader.Limits): ImageBitmap? {
        val key = Key(identity, url, limits)
        val deferred = mutex.withLock {
            lru[key]?.let { return it }
            val started = generation
            // Both the admit and the in-flight removal happen INSIDE the
            // shared load, not in whichever caller happened to await first: a
            // caller cancelled by leaving composition (exactly what a
            // scrolling lazy_column does) must not drop the shared entry
            // while the load is still running, or the next caller starts a
            // second fetch — and must not skip the admit, or the result is
            // fetched and then thrown away.
            inFlight[key] ?: scope.async {
                val bitmap = ImageLoader.load(url, limits)
                mutex.withLock {
                    inFlight.remove(key)
                    // SPEC 9.1: a load that began before an erasure MUST NOT
                    // reinstall its bytes afterwards.
                    if (bitmap != null && started == generation) admit(key, bitmap)
                }
                bitmap
            }.also { inFlight[key] = it }
        }
        return deferred.await()
    }

    /** SPEC 9.1/17.2 (amendment #86): fetched image content is inside the
     * revocation-erasure boundary. Erases retained bytes AND fences every
     * load still in flight, so nothing fetched before the erasure can be
     * installed after it. */
    suspend fun clear() = mutex.withLock {
        lru.clear()
        usedBytes = 0
        inFlight.clear()
        generation++
    }

    /** What a decode retains: four bytes per pixel on both platforms. */
    private fun retainedBytes(image: ImageBitmap): Long =
        image.width.toLong() * image.height.toLong() * 4

    private fun admit(key: Key, bitmap: ImageBitmap) {
        if (lru.containsKey(key)) return
        val size = retainedBytes(bitmap)
        // An image larger than the whole budget is NOT admitted: evicting
        // every other entry to make room for it would empty the cache and
        // still leave usedBytes over budget. One legal 4096x4096 decodes to
        // ~64 MiB, which exceeds the budget on most devices.
        if (size > budgetBytes) return
        val it = lru.entries.iterator()
        while (usedBytes + size > budgetBytes && it.hasNext()) {
            val e = it.next()
            usedBytes -= retainedBytes(e.value)
            it.remove()
        }
        lru[key] = bitmap
        usedBytes += size
    }
}
