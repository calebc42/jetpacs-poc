// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

/**
 * SPEC 20.1.3 (#205): the device's own color scheme — [dark] for the system
 * appearance the Companion follows when `theme.set` omits `dark`, and the
 * [light] and [darkScheme] role maps (the thirteen standard roles plus the
 * platform's extended ones, lowercase `#rrggbb`) — that the engine mirrors
 * in the welcome and re-sends as `colors.changed`. Facts only: the report
 * changes nothing the Companion draws.
 */
data class DeviceColors(
    val dark: Boolean,
    val light: Map<String, String>,
    val darkScheme: Map<String, String>,
)
