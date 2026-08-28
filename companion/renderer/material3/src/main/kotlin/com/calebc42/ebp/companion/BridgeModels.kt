// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion

import com.calebc42.ebp.companion.render.DiagSet
import com.calebc42.ebp.companion.render.EldocLine
import com.calebc42.ebp.companion.render.FontifySet

/** Display-side shadow for one synchronized editor. */
data class EditorMirror(
    val text: String,
    val cursorU: Int,
    val selStartU: Int,
    val selEndU: Int,
    val seq: Long,
    val epoch: Long,
)

/** Latest independent annotation sets for one synchronized editor. */
data class EditorAnnotationState(
    val fontify: FontifySet? = null,
    val diags: DiagSet? = null,
    val eldoc: EldocLine? = null,
    val epoch: Long = 0,
)

/** One completion row after wire defaults have been applied. */
data class CompletionCandidate(
    val label: String,
    val annotation: String?,
    val insert: String,
    val kind: String? = null,
)

/** One completion result paired with the editor state that requested it. */
data class CompletionOffer(
    val prefix: String,
    val candidates: List<CompletionCandidate>,
    val session: String,
    val seq: Long,
    val cursor: Int,
    val epoch: Long,
)

/** Lazily fetched documentation for one row in a specific offer epoch. */
data class CandidateDoc(
    val index: Int,
    val text: String,
    val epoch: Long,
)
