// SPDX-License-Identifier: GPL-3.0-or-later
// RF-2c H4: THE platform seam. Everything :wire needs from the host that the
// common stdlib cannot supply is one of the seven declarations below — a new
// platform target implements exactly this file (plus the File* backings) and
// nothing else. All seven live here even though WireLock/AtomicRef/
// catchingStackOverflow gain their first common callers in later H-steps:
// one stable seam beats three accreting ones.
package com.calebc42.ebp.wire

/** N bytes from the platform CSPRNG (SPEC 9.2 nonces, pairing tokens). */
internal expect fun secureRandomBytes(n: Int): ByteArray

/** HMAC-SHA256 of [msg] keyed by [key] (SPEC 9.3 proofs). */
internal expect fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray

/** Timing-safe equality: run time may depend on the lengths, never on how
 * many leading bytes matched. */
internal expect fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean

/** RFC 4648 base64url decode, padding optional; throws
 * IllegalArgumentException on any non-base64url input (SPEC 9.1 tokens). */
internal expect fun base64UrlDecode(text: String): ByteArray

/**
 * The module's mutual-exclusion primitive, replacing @Synchronized at the
 * hoist. An actual MUST be REENTRANT: the engine/queue/store call graph has
 * 15+ chains where a locked method calls another locked method on the same
 * object (e.g. dispatch -> sendRequest). A non-reentrant actual deadlocks
 * on the first nested acquisition — JVM monitors qualify; a plain
 * platform mutex does not.
 */
internal expect class WireLock() {
    fun <T> withLock(block: () -> T): T
}

/**
 * Atomic reference with compare-and-set. TriggerFiringService's `detach`
 * CAS is load-bearing (a documented lost-update race between the firing
 * thread and detach): an actual MUST provide real CAS — never demote
 * callers to a volatile read-modify-write.
 */
internal expect class AtomicRef<T>(initial: T) {
    fun get(): T
    fun set(value: T)
    fun compareAndSet(expected: T, new: T): Boolean
}

/**
 * Run [block], returning null if it overflows the call stack (the SPEC 7.1
 * serialize-reply funnel's last-resort guard). Kotlin common has no
 * StackOverflowError type, so the catch itself is platform code.
 */
internal expect fun <T> catchingStackOverflow(block: () -> T): T?
