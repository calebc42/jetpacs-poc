// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.font.Font
import com.calebc42.jetpacs.companion.host.HostLog
import com.calebc42.jetpacs.companion.host.fonts.FontFileLoader
import com.calebc42.jetpacs.companion.host.fonts.PickedFontFile
import com.calebc42.jetpacs.companion.host.fonts.UserFontStore
import com.calebc42.jetpacs.companion.host.fonts.UserFonts
import java.io.File

/**
 * The Android half of the user's fonts: the files live under the app's
 * private files directory (`fonts/`), never on shared storage, and a file
 * becomes a Compose font through the platform's `Font(File)`, which reads it
 * lazily at first layout. The system document picker is the only way a file
 * gets in; reading it is bounded to one byte over the store's limit so the
 * store, not this code, states the refusal.
 */
internal object AndroidUserFonts {
    val loader = FontFileLoader { file, weight, style -> Font(file.toFile(), weight, style) }

    fun create(context: Context, log: HostLog): UserFonts =
        UserFonts(UserFontStore(File(context.applicationContext.filesDir, "fonts").toPath()), loader, log)

    /** The MIME types the picker offers; fonts arrive under several, and often as plain octets. */
    val pickerMimeTypes = arrayOf(
        "font/ttf", "font/otf", "font/sfnt",
        "application/x-font-ttf", "application/x-font-opentype", "application/vnd.ms-opentype",
        "application/octet-stream",
    )

    /** Read the picked document, bounded; null when it cannot be opened. */
    fun read(context: Context, uri: Uri): PickedFontFile? {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "font.ttf"
        val bytes = resolver.openInputStream(uri)?.use { it.readNBytes(UserFontStore.MAX_FILE_BYTES + 1) }
            ?: return null
        return PickedFontFile(name, bytes)
    }
}
