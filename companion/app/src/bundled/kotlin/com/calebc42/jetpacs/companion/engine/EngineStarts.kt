// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

import java.io.File

/**
 * The crash-loop guard's arithmetic, pure so a JVM test pins it: the engine
 * may start twice inside [WINDOW_MS]; a third start inside the window halts
 * it until the user opens the Companion again. Start times are kept in a
 * small file in the engine's HOME because the `:emacs` process and the
 * Companion process must not share SharedPreferences. Only restarts after a
 * process that died on its own count: the service's clean stop deletes the
 * file, and a launch or a visible window clears it with the halt.
 */
internal object EngineStarts {
    const val WINDOW_MS = 10L * 60 * 1000
    const val LIMIT = 3
    private const val KEEP = 8

    /** True when adding a start at [now] to [previous] reaches the limit inside the window. */
    fun shouldHalt(previous: List<Long>, now: Long): Boolean =
        previous.count { now - it in 0..WINDOW_MS } >= LIMIT - 1

    /** The list to persist after a start at [now]: the recent ones, most recent last. */
    fun remember(previous: List<Long>, now: Long): List<Long> =
        (previous.filter { now - it in 0..WINDOW_MS } + now).takeLast(KEEP)

    fun read(file: File): List<Long> =
        if (!file.isFile) emptyList()
        else file.readLines().mapNotNull { it.trim().toLongOrNull() }

    fun write(file: File, starts: List<Long>) {
        file.writeText(starts.joinToString("\n", postfix = "\n"))
    }
}
