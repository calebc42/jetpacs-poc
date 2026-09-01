// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import com.calebc42.ebp.wire.CompanionHmacKeyHandle
import com.calebc42.ebp.wire.CompanionProofKeyResult
import com.calebc42.ebp.wire.CompanionProofProvider
import com.calebc42.jetpacs.core.ebpstore.AndroidKeyStoreHmacSigner

/** App-level adapter keeping the generic wire free of Android dependencies. */
internal class AndroidCompanionProofProvider(
    pairingAliases: Map<String, String>,
    private val dummyAlias: String,
) : CompanionProofProvider {
    private val aliases = pairingAliases.toMap()

    init {
        require(dummyAlias.isNotBlank())
        require(aliases.keys.none { it.isBlank() })
        require(aliases.values.none { it.isBlank() })
    }

    override fun resolve(pairingId: String): CompanionProofKeyResult =
        aliases[pairingId]?.let { alias ->
            CompanionProofKeyResult.Known(handle(alias))
        } ?: CompanionProofKeyResult.Unknown(handle(dummyAlias))

    private fun handle(alias: String) = CompanionHmacKeyHandle { message ->
        AndroidKeyStoreHmacSigner(alias).hmacSha256(message)
    }
}
