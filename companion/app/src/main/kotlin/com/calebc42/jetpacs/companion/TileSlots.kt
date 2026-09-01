// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Five manifest-declared host slots addressed as `tile:custom1`..`custom5`. */
object TileSlots {
    val slots: Map<String, Class<out EbpTileService>> = linkedMapOf(
        "tile:custom1" to EbpTile1::class.java,
        "tile:custom2" to EbpTile2::class.java,
        "tile:custom3" to EbpTile3::class.java,
        "tile:custom4" to EbpTile4::class.java,
        "tile:custom5" to EbpTile5::class.java,
    )
    val surfaceIds: Set<String> get() = slots.keys

    /** Ask System UI to re-read one accepted/tombstoned slot. */
    fun refresh(context: Context, surface: String) {
        val service = slots[surface] ?: return
        TileService.requestListeningState(context, ComponentName(context, service))
    }
}

/** System-owned Quick Settings presentation of one accepted EBP tile spec. */
abstract class EbpTileService : TileService() {
    protected abstract val surface: String

    override fun onStartListening() {
        val app = application as? JetpacsApplication ?: return
        val spec = app.stores.surfaces().spec(surface)
        qsTile?.apply {
            icon = Icon.createWithResource(this@EbpTileService, R.drawable.ic_qs_jetpacs)
            if (spec == null) {
                state = Tile.STATE_UNAVAILABLE
                subtitle = null
            } else {
                label = spec.stringOr("label")
                subtitle = spec.stringOr("subtitle").takeIf(String::isNotEmpty)
                state = if (spec.boolOr("active")) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }

    override fun onClick() {
        val app = application as? JetpacsApplication ?: return
        val descriptor = app.stores.surfaces().spec(surface)?.objOrNull("on_tap") ?: return
        val opensHost = "confirm" in descriptor || descriptor.stringOr("builtin") in setOf(
            "surface.open",
            "companion.settings.open",
        )
        val dispatch = {
            app.bridge.dispatchPlatformAction(
                descriptor,
                buildJsonObject { put("tile", surface.substringAfter(':')) },
            )
            if (opensHost) openAppHost()
        }
        if (isLocked && opensHost) unlockAndRun(dispatch) else dispatch()
    }

    private fun openAppHost() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivityAndCollapse(PendingIntent.getActivity(
            this,
            surface.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ))
    }
}

/** Manifest entrypoint for the fixed `tile:custom1` host slot. */
class EbpTile1 : EbpTileService() { override val surface = "tile:custom1" }

/** Manifest entrypoint for the fixed `tile:custom2` host slot. */
class EbpTile2 : EbpTileService() { override val surface = "tile:custom2" }

/** Manifest entrypoint for the fixed `tile:custom3` host slot. */
class EbpTile3 : EbpTileService() { override val surface = "tile:custom3" }

/** Manifest entrypoint for the fixed `tile:custom4` host slot. */
class EbpTile4 : EbpTileService() { override val surface = "tile:custom4" }

/** Manifest entrypoint for the fixed `tile:custom5` host slot. */
class EbpTile5 : EbpTileService() { override val surface = "tile:custom5" }
