// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.core.ebpstore

import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Generates an unpredictable 128-bit lowercase-hex row generation. */
class SecureRandomStorageGenerationSource : StorageGenerationSource {
    override suspend fun next(): String = withContext(Dispatchers.IO) {
        ByteArray(16).also(PROCESS_SECURE_RANDOM::nextBytes).toLowerHex()
    }

    private companion object {
        // SecureRandom is thread safe; one process-wide instance avoids
        // needless reseeding and construction on each injected source.
        val PROCESS_SECURE_RANDOM = SecureRandom()
    }
}

internal fun ByteArray.toLowerHex(): String = buildString(size * 2) {
    this@toLowerHex.forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        append(HEX[unsigned ushr 4])
        append(HEX[unsigned and 0x0f])
    }
}

private const val HEX = "0123456789abcdef"
