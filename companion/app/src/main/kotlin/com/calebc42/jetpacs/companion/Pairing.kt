// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import com.calebc42.ebp.wire.PairingId
import com.calebc42.jetpacs.companion.host.KnownAnswerPairing
import com.calebc42.jetpacs.core.ebpstore.ProvisionedPairingAliases

/*
 * W4 SMOKE SCOPE: the Android app still pairs with the public SPEC 9.3
 * known-answer credential until real generated, keystore-backed pairing
 * replaces it here too (the desktop host generates its own per SPEC 9.1;
 * docs/PLAN-desktop-companion.md). Not a secret and not a deployment
 * configuration.
 */
internal const val JETPACS_PAIRING_ID = KnownAnswerPairing.PAIRING_ID
internal const val JETPACS_PAIRING_TOKEN = KnownAnswerPairing.TOKEN

/**
 * The pairing this process serves, as the flavor resolved it: the standalone
 * flavor's fixed known-answer credential, or the bundled flavor's generated
 * one (docs/PLAN-bundled-emacs.md, E3).
 */
internal data class ResolvedPairing(
    val pairingId: PairingId,
    val aliases: ProvisionedPairingAliases,
)
