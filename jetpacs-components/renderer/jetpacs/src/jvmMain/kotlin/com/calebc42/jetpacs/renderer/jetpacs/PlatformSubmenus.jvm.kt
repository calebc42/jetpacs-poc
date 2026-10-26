// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.runtime.Composable

/** A desktop window always has room to cascade. */
@Composable
internal actual fun platformCascadesSubmenus(): Boolean = true
