// SPDX-License-Identifier: GPL-3.0-or-later
// RF-2c H4: JVM actuals for the Platform.kt seam — thin delegations to the
// java.security / javax.crypto / java.util primitives the pre-hoist code
// called directly.
package com.calebc42.ebp.wire

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// SecureRandom is thread-safe; one instance for the module, as before.
private val random = SecureRandom()

internal actual fun secureRandomBytes(n: Int): ByteArray =
    ByteArray(n).also(random::nextBytes)

internal actual fun hmacSha256(key: ByteArray, msg: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(msg)
    }

internal actual fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
    MessageDigest.isEqual(a, b)

internal actual fun base64UrlDecode(text: String): ByteArray =
    Base64.getUrlDecoder().decode(text)

/** Reentrant by construction: a JVM monitor is owned per-thread. */
internal actual class WireLock actual constructor() {
    private val monitor = Any()
    actual fun <T> withLock(block: () -> T): T = synchronized(monitor) { block() }
}

// A wrapper (not a typealias) so the expect's surface stays the contract —
// AtomicReference's wider API never leaks into common code.
internal actual class AtomicRef<T> actual constructor(initial: T) {
    private val ref = AtomicReference(initial)
    actual fun get(): T = ref.get()
    actual fun set(value: T) = ref.set(value)
    actual fun compareAndSet(expected: T, new: T): Boolean =
        ref.compareAndSet(expected, new)
}

internal actual fun <T> catchingStackOverflow(block: () -> T): T? =
    try { block() } catch (_: StackOverflowError) { null }
