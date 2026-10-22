// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

/**
 * SPEC 20.1.2 (#197): the device's presentation locale advice — a BCP 47
 * [tag], the clock cycle `h12`/`h23`, and an IANA [timeZone] — that the
 * engine mirrors in the welcome and re-sends as `locale.changed`. Facts
 * only: the Companion never formats a display string from them.
 */
data class DeviceLocale(val tag: String, val hourCycle: String, val timeZone: String)

/** The pure core of a platform's locale read: [is24Hour] maps to `h23`, else `h12`. */
fun localeAdvice(tag: String, is24Hour: Boolean, timeZone: String): DeviceLocale =
    DeviceLocale(tag, if (is24Hour) "h23" else "h12", timeZone)
