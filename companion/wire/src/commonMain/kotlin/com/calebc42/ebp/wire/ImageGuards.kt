// SPDX-License-Identifier: GPL-3.0-or-later
// SPEC 17.2 image guard predicates: the pure, JVM-testable core of the loader's
// SSRF and data-URL defenses. The app-layer loader (ImageLoader) does the
// network/decode I/O; every policy decision it makes routes through here so the
// rules can be exercised without a socket. poc-v1 used a bare image library
// with NONE of this — the whole stack is new. The InetAddress/decode half of
// these guards now lives in the jvmMain object NetGuards.
package com.calebc42.ebp.wire

object ImageGuards {

    /** SPEC 17.2 `image.data`: the raster media types the Companion decodes.
     * SVG and any active/scriptable format are deliberately excluded. */
    val SUPPORTED_MEDIA_TYPES = setOf(
        "image/png", "image/jpeg", "image/gif", "image/webp",
        "image/bmp", "image/heic", "image/heif")

    /** SPEC 17.2: a redirect MUST NOT change the URI scheme away from https. */
    fun redirectAllowed(location: String?): Boolean =
        location != null && location.trim().lowercase().startsWith("https://")

    /** Whether [url] is an HTTPS URL (the only remote form). */
    fun isHttps(url: String): Boolean = url.lowercase().startsWith("https://")

    /**
     * SPEC 17.2 (amendment #114), pinned in `contract.image_data_encoding`:
     * the standard RFC 4648 alphabet, padding REQUIRED, whitespace FORBIDDEN.
     * A Companion "MUST reject a payload containing a character outside that
     * alphabet and MUST NOT strip or normalize whitespace in place of
     * rejection" — so this is a predicate, never a sanitizer, and the caller
     * passes the payload through untouched.
     *
     * `java.util.Base64.getDecoder()` is not sufficient on its own: it accepts
     * a final quantum with the padding omitted, which this profile forbids.
     * `-` and `_` fall outside the alphabet, so base64url is refused here too.
     * The same rule governs `icon_png` (SPEC 20.3).
     */
    fun isStrictBase64(s: String): Boolean {
        if (s.isEmpty() || s.length % 4 != 0) return false
        // At most two '=', only in the final quantum, and never before data.
        val pad = when {
            s.endsWith("==") -> 2
            s.endsWith("=") -> 1
            else -> 0
        }
        val body = s.length - pad
        if (body == 0) return false
        for (i in 0 until body) {
            val c = s[i]
            val ok = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') ||
                c == '+' || c == '/'
            if (!ok) return false
        }
        return true
    }

    /** SPEC 17.2: whether [url] is a valid image URI form at all — the only
     * implicit forms are none, so it MUST be https or a well-formed data:image.
     * Content-level validation (advertisement gating is a profile concern). */
    fun isValidImageUrl(url: String): Boolean {
        if (isHttps(url)) return true
        if (!url.startsWith("data:", ignoreCase = true)) return false
        val comma = url.indexOf(',')
        if (comma < 0) return false
        val header = url.substring(5, comma).lowercase()
        val parts = header.split(';')
        if (parts.size != 2 || parts[1] != "base64" ||
            parts[0] !in SUPPORTED_MEDIA_TYPES) return false
        // SPEC 17.2: the payload is part of the URI FORM, so it is judged at
        // accept time with everything else. Checking only the header let a
        // spec through `surface.update` that could never render — Emacs was
        // told "applied", the revision floor advanced, and the failure showed
        // up as a blank image with no error to bind it to. This is a scan of
        // the payload, not a decode: no allocation, no limit enforcement
        // (SPEC 17.2's byte and pixel limits stay with the loader).
        return isStrictBase64(url.substring(comma + 1))
    }
}
