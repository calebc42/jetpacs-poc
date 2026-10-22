// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

/** Only the loss of the authoritative authenticated session is user-visible. */
fun shouldPostReconnectNotification(
    authenticated: Boolean,
    wasCurrentSession: Boolean,
): Boolean = authenticated && wasCurrentSession
