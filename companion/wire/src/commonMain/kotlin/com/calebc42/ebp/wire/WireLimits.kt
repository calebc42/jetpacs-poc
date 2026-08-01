// SPDX-License-Identifier: GPL-3.0-or-later
// RF-2c(H1) extraction from FrameCodec.kt: WireLimits and the SPEC 6.2 error
// taxonomy are referenced by files that hoist to commonMain ahead of the codec,
// so they lead the way here. Same package — the relocation is import-invisible.
package com.calebc42.ebp.wire

/** SPEC 4.5 fixed limits (contract.json `limits.fixed`). */
object WireLimits {
    const val MAX_HEADER_OCTETS = 8192
    const val MAX_BODY_OCTETS = 4_194_304
    const val MAX_JSON_DEPTH = 64
    const val MAX_NODES_PER_SNAPSHOT = 10_000
    const val MAX_CHILDREN_PER_NODE = 10_000
    const val MAX_IDENTIFIER_OCTETS = 128
    const val MAX_REQUEST_ID_OCTETS = 64
    const val MAX_METHOD_OCTETS = 128
    // SPEC 4.5 (amendment #108): node nesting depth in one document. Far
    // below max_json_depth because a node level costs ~2 JSON containers and
    // host ENCODERS cap lower than receivers accept — Emacs's json-serialize
    // stops at 50 containers with no override, so a document nested to the
    // JSON limit is unemittable by a conforming Emacs endpoint.
    const val MAX_NODE_DEPTH = 20
    // SPEC 4.5 (amendment #113): the header section a SENDER may rely on a
    // peer accepting. The 8,192 figure above is a receiver rejection
    // threshold, not a sender allowance.
    const val MAX_SEND_HEADER_OCTETS = 128
}

/** SPEC 6.2: conditions that force connection closure. */
class FrameClose(message: String) : Exception(message)

/** SPEC 6.2: EOF in the middle of a frame terminates the session. */
class FrameIncomplete(message: String) : Exception(message)

/** SPEC 6.2: invalid UTF-8 or invalid JSON after a complete body. */
class WireParseError(message: String) : Exception(message)

/** SPEC 6.2 / 4.1: non-object top level, batch array, duplicate members. */
class InvalidRequest(message: String) : Exception(message)
