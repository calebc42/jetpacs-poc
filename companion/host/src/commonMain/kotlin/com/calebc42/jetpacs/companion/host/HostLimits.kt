// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

/**
 * SPEC 4.5 limits the welcome advertises for the platform modules (§20
 * device report, shortcuts). Both hosts advertise the same values; the
 * Android adapters enforce them where the platform call happens.
 */
object HostLimits {
    const val MAX_SHORTCUT_ICON_BYTES = 262_144L
    const val MAX_SHORTCUTS = 5L
    const val MAX_DEVICE_REPORT_BYTES = 8_192L
}
