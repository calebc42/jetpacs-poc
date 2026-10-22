// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

import com.calebc42.ebp.wire.WireLimits
import com.calebc42.jetpacs.material3.ImageLoader
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** SPEC 4.5: the one input-state ceiling both the limits and the offline commit path use. */
const val MAX_INPUT_STATE_BYTES = 262_144L

/**
 * SPEC 4.5: the welcome `limits` object every Companion host advertises.
 * One source for both hosts, so a desktop and an Android welcome promise the
 * same bounds and the engine's constructor checks pass identically.
 *
 * C6: every value stays INTEGER-spelled. The engine's constructor reads the
 * limits with reqLong, so a `.0` spelling would not merely widen a bound,
 * it would throw at engine construction.
 */
fun companionLimits(): JsonObject = buildJsonObject {
    put("max_frame_bytes", 4_194_304)
    put("max_queued_events", 256)
    put("max_queued_bytes", 8_388_608)
    put("max_event_bytes", 262_144)
    put("max_surfaces", 64)
    put("max_surface_ids", 4096)
    put("max_field_bytes", 65_536)
    put("max_input_state_bytes", MAX_INPUT_STATE_BYTES)
    put("max_capture_fields", 64)
    put("max_dialogs", 4)
    put("max_pie_menus", 1)
    put("max_reminders", 256)
    put("max_editor_sessions", 8)
    put("max_trigger_responses", 8)
    put("max_triggers", 64)
    put("max_device_report_bytes", HostLimits.MAX_DEVICE_REPORT_BYTES)
    put("max_shortcut_icon_bytes", HostLimits.MAX_SHORTCUT_ICON_BYTES)
    put("max_shortcuts", HostLimits.MAX_SHORTCUTS)
    // SPEC 4.5/17.2: the three image limits are REQUIRED whenever image
    // is advertised — the same constants the loader enforces (no drift).
    put("max_image_bytes", ImageLoader.MAX_IMAGE_BYTES)
    put("max_decoded_image_bytes", ImageLoader.MAX_DECODED_IMAGE_BYTES)
    put("max_image_pixels", ImageLoader.MAX_IMAGE_PIXELS)
    // SPEC 4.5/17.5: REQUIRED whenever chart/canvas are advertised.
    put("max_chart_points", 4096)
    put("max_canvas_ops", 4096)
    // SPEC 4.5: REQUIRED whenever rich_text/table are advertised —
    // aggregate counts across one SurfaceSpec or dialog (LD-22).
    put("max_rich_spans", 4096)
    put("max_table_cells", 4096)
    // Retained alternatives are bounded independently of the global
    // node count so a sender can size authoring work to this host.
    put("max_variants_per_host", WireLimits.MAX_VARIANTS_PER_HOST)
    // SPEC 4.5 (amendment #84): REQUIRED when editor.sync is granted.
    // Declared at the floor: it is what keeps every editor path
    // (shadow rebuild, diff, highlight, relayout) comfortably linear.
    put("max_editor_bytes", CompanionStores.MAX_EDITOR_BYTES)
}
