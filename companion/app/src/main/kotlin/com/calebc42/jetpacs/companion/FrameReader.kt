// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import com.calebc42.ebp.wire.FrameClose
import com.calebc42.ebp.wire.FrameDecoder
import com.calebc42.ebp.wire.FrameIncomplete
import com.calebc42.ebp.wire.InvalidRequest
import com.calebc42.ebp.wire.WireParseError
import kotlinx.serialization.json.JsonObject
import java.io.InputStream

/**
 * The inbound half of one connection (PLAN-responsiveness C2a). Reads the
 * socket in 64 KiB slices and decodes EBP frames on the reader thread; the
 * engine is handed one complete, parsed frame at a time, so the engine
 * monitor and the process actor never see a chunk. A partial frame is this
 * reader's alone — a tap dispatched while a large snapshot is still arriving
 * takes the engine without waiting for the frame's remaining bytes.
 */
internal class FrameReader(
    private val input: InputStream,
    private val timing: Boolean = false,
    bufferSize: Int = 65_536,
) {
    private val decoder = FrameDecoder()
    private val buffer = ByteArray(bufferSize)

    /**
     * Read until EOF or until [open] reports the session closed. Every
     * complete frame reaches [onFrame] with its decode time in nanoseconds
     * (0 when not [timing]); every decoder fault reaches [onFault], which
     * returns whether the session survived it — SPEC 6.2's recoverable
     * faults do, and then the frames pipelined behind the bad one are
     * drained before the next read. Returns at EOF, after a closing fault,
     * or once [open] is false; a throwing callback propagates.
     */
    fun run(
        open: () -> Boolean,
        onFrame: (message: JsonObject, decodeNanos: Long) -> Unit,
        onFault: (fault: Exception) -> Boolean,
    ) {
        while (open()) {
            val n = input.read(buffer)
            if (n < 0) return
            if (!drain(n, open, onFrame, onFault)) return
        }
    }

    /** Decode the slice just read, then re-enter with no bytes after each
     * recoverable fault so the frames behind it are delivered; false once
     * the session is closed. */
    private fun drain(
        length: Int,
        open: () -> Boolean,
        onFrame: (JsonObject, Long) -> Unit,
        onFault: (Exception) -> Boolean,
    ): Boolean {
        var sourceLength = length
        while (true) {
            try {
                var mark = if (timing) System.nanoTime() else 0L
                decoder.feed(buffer, 0, sourceLength) { message ->
                    onFrame(message, if (timing) System.nanoTime() - mark else 0L)
                    // The next frame's decode is measured from here.
                    if (timing) mark = System.nanoTime()
                }
                return true
            } catch (fault: FrameClose) {
                onFault(fault)
                return false
            } catch (fault: FrameIncomplete) {
                onFault(fault)
                return false
            } catch (fault: WireParseError) {
                if (!onFault(fault) || !open()) return false
            } catch (fault: InvalidRequest) {
                if (!onFault(fault) || !open()) return false
            }
            sourceLength = 0
        }
    }
}
