// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.ui

import androidx.activity.ComponentActivity

/**
 * Test-only host for connected Compose semantics checks.
 *
 * Its manifest may turn on the display and show this activity above keyguard so a physical
 * receiver can run unattended. It never dismisses or weakens a secure keyguard.
 */
class SemanticsHostActivity : ComponentActivity()
