// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calebc42.jetpacs.companion.host.fonts.UserFontStore
import com.calebc42.jetpacs.companion.host.fonts.UserFonts
import com.calebc42.jetpacs.material3.EbpFontFamilies
import com.calebc42.jetpacs.renderer.jetpacs.JetpacsFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Material renderer's view of the installed fonts: the sans role is the
 * body family, the mono role the monospace one. Reads [JetpacsFonts] under
 * composition, so an install re-themes the window.
 */
@Composable
fun rememberEbpFontFamilies(): EbpFontFamilies {
    val fonts = JetpacsFonts.current
    return remember(fonts) { EbpFontFamilies(body = fonts.sans, mono = fonts.mono) }
}

/**
 * The Fonts section of the Jetpacs Settings screen: the three roles a user
 * font may replace device-wide, the installed families with the identifier
 * a design names them by, and the picker that adds a file. File work runs
 * off the UI thread; the last outcome is shown as a line under the button,
 * since both hosts lack a shared toast.
 */
@Composable
fun FontSettings(fonts: UserFonts, platform: HostUiPlatform) {
    val catalog by fonts.catalog.collectAsState()
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val launchPicker = platform.rememberFontFilePicker { picked ->
        if (picked == null) return@rememberFontFilePicker
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { fonts.add(picked) }
            notice = addNotice(result)
            busy = false
        }
    }

    Text("Fonts", style = MaterialTheme.typography.titleSmall)
    Text(
        "Your own font files, kept on this device. A design names a family by " +
            "its identifier; a role below replaces that family everywhere.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (role in UserFontStore.Role.entries) {
        RoleRow(
            role = role,
            catalog = catalog,
            enabled = !busy,
            onAssign = { slug ->
                busy = true
                scope.launch {
                    withContext(Dispatchers.IO) { fonts.assign(role, slug) }
                    busy = false
                }
            },
        )
    }
    for (family in catalog.families) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                // Previewed in itself, so the row shows what was installed.
                Text(
                    family.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = JetpacsFonts.current.named[family.slug] ?: FontFamily.Default,
                    ),
                )
                Text(
                    "font-family: ${family.slug} · " + family.faces.joinToString(", ") { face ->
                        buildString {
                            append(face.weight)
                            if (face.italic) append(" italic")
                            if (face.variable) append(" (variable)")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) { fonts.remove(family.slug) }
                        notice = "Removed ${family.name}"
                        busy = false
                    }
                },
            ) { Icon(Icons.Filled.Delete, contentDescription = "Remove ${family.name}") }
        }
    }
    if (launchPicker != null) {
        TextButton(enabled = !busy, onClick = launchPicker) { Text("Add a font file (.ttf or .otf)") }
    }
    notice?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The outcome of adding a picked file, as the section's notice line. */
internal fun addNotice(result: Result<UserFontStore.Family>): String = result.fold(
    { family ->
        val faces = family.faces.size
        "Added ${family.name}: ${if (faces == 1) "1 face" else "$faces faces"}. " +
            "Designs name it font-family: ${family.slug}."
    },
    { "Not added: ${it.message ?: "the file could not be read"}" },
)

@Composable
private fun RoleRow(
    role: UserFontStore.Role,
    catalog: UserFontStore.Catalog,
    enabled: Boolean,
    onAssign: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val chosen = catalog.roles[role]?.let(catalog::family)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(role.label, Modifier.weight(1f))
        Box {
            TextButton(enabled = enabled && catalog.families.isNotEmpty(), onClick = { open = true }) {
                Text(
                    chosen?.name ?: role.bundledLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = chosen?.let { JetpacsFonts.current.named[it.slug] } ?: FontFamily.Default,
                        fontWeight = FontWeight.Normal,
                        fontStyle = FontStyle.Normal,
                    ),
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(role.bundledLabel) },
                    onClick = { open = false; onAssign(null) },
                )
                for (family in catalog.families) {
                    DropdownMenuItem(
                        text = { Text(family.name) },
                        onClick = { open = false; onAssign(family.slug) },
                    )
                }
            }
        }
    }
}
