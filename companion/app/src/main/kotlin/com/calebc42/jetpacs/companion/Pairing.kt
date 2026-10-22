// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import com.calebc42.jetpacs.companion.host.KnownAnswerPairing

/*
 * W4 SMOKE SCOPE: the Android app still pairs with the public SPEC 9.3
 * known-answer credential until real generated, keystore-backed pairing
 * replaces it here too (the desktop host generates its own per SPEC 9.1;
 * docs/PLAN-desktop-companion.md). Not a secret and not a deployment
 * configuration.
 */
internal const val JETPACS_PAIRING_ID = KnownAnswerPairing.PAIRING_ID
internal const val JETPACS_PAIRING_TOKEN = KnownAnswerPairing.TOKEN
