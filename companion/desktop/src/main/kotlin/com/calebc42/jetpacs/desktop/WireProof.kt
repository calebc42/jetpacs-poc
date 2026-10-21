// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import com.calebc42.ebp.wire.FrameDecoder
import java.io.File

/**
 * D0 wire proof: decode the EBP `01-hello.bin` golden through the same
 * `:wire` [FrameDecoder] the Android Companion uses, on the desktop JVM.
 * Returns the decoded method name, or a plain message when the `ebp.dir`
 * system property is unset or the golden is missing.
 */
fun decodeHelloGolden(ebpDir: String? = System.getProperty("ebp.dir")): String {
    if (ebpDir.isNullOrEmpty()) return "wire: ebp.dir is not set"
    val golden = File(ebpDir, "goldens/wire/01-hello.bin")
    if (!golden.isFile) return "wire: ${golden.path} not found"
    val frames = FrameDecoder().feed(golden.readBytes())
    val method = frames.singleOrNull()?.get("method")?.toString()?.trim('"')
    return "wire: decoded ${frames.size} frame, method $method"
}
