// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host.fonts

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.calebc42.jetpacs.companion.host.HostLog
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsFontSet
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsFonts
import java.nio.file.Path
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How a platform turns a font file into a Compose [Font]: Android's
 * `Font(File)` and the desktop's `androidx.compose.ui.text.platform.Font`,
 * neither of which this platform-neutral module can name.
 */
fun interface FontFileLoader {
    fun load(file: Path, weight: FontWeight, style: FontStyle): Font
}

/** A file the platform's picker returned: its name, for the extension, and its bytes. */
class PickedFontFile(val name: String, val bytes: ByteArray)

/**
 * The user's fonts as the renderers see them: every change to the [store]
 * rebuilds the [JetpacsFontSet] through the platform's [loader] and installs
 * it in [JetpacsFonts], so the open window re-fonts at once. [catalog] is
 * what the settings screen shows. A face whose file the platform cannot
 * open is left out of its family and logged; the rest of the family stands.
 */
class UserFonts(
    val store: UserFontStore,
    private val loader: FontFileLoader,
    private val log: HostLog? = null,
) {
    private val _catalog = MutableStateFlow(UserFontStore.Catalog.Empty)
    val catalog: StateFlow<UserFontStore.Catalog> get() = _catalog

    /** Read the store and install what it holds; call once before the first composition. */
    fun install(): JetpacsFontSet {
        val catalog = store.catalog()
        val named = LinkedHashMap<String, FontFamily>()
        for (family in catalog.families) {
            val fonts = family.faces.mapNotNull { face ->
                runCatching {
                    loader.load(
                        store.fileOf(face),
                        FontWeight(face.weight),
                        if (face.italic) FontStyle.Italic else FontStyle.Normal,
                    )
                }.onFailure { log?.w(TAG, "font ${face.file} did not load: ${it.message}") }.getOrNull()
            }
            if (fonts.isNotEmpty()) named[family.slug] = FontFamily(fonts)
        }
        val set = JetpacsFontSet(
            named = named,
            sans = catalog.roles[UserFontStore.Role.SANS]?.let(named::get),
            serif = catalog.roles[UserFontStore.Role.SERIF]?.let(named::get),
            mono = catalog.roles[UserFontStore.Role.MONO]?.let(named::get),
        )
        JetpacsFonts.install(set)
        _catalog.value = catalog
        return set
    }

    /** Add a picked file; the message names the family it joined or why it was refused. */
    fun add(file: PickedFontFile): Result<UserFontStore.Family> =
        runCatching { store.add(file.bytes, file.name) }.also { install() }

    fun remove(slug: String) {
        store.remove(slug)
        install()
    }

    fun assign(role: UserFontStore.Role, slug: String?) {
        store.assign(role, slug)
        install()
    }

    private companion object {
        const val TAG = "JetpacsFonts"
    }
}
