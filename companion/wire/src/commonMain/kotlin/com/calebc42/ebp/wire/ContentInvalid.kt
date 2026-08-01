// SPDX-License-Identifier: GPL-3.0-or-later
// RF-2c(H1) extraction from SpecValidator.kt: thrown across the validator/store
// tier, so it is extracted here to free the hoist ordering of its throwers.
// Same package — the relocation is import-invisible.
package com.calebc42.ebp.wire

class ContentInvalid(val path: String, val reason: String) :
    Exception("$reason at $path")
