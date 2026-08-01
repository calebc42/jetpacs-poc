// SPDX-License-Identifier: GPL-3.0-or-later
// The java.io half of TriggerBacking.kt, split out at RF-2c(H6.a).
package com.calebc42.ebp.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

class FileTriggerBacking(private val file: File) : TriggerBacking {

    override fun load(): TriggerState {
        if (!file.exists()) return TriggerState(emptyMap())
        val text = file.readText(Charsets.UTF_8)
        if (text.isBlank()) return TriggerState(emptyMap())
        // PERSISTED text is read by kotlinx's lenient parser, NEVER by
        // EbpJson.parse: that one is the strict WIRE parser, whose frame rules
        // (SPEC 4.5's 64-container depth cap above all) are not the store's —
        // a stored entry sits deeper than the frame that delivered it, under
        // this file's own {"identities":{…:[…]}} wrapper. A strict re-parse
        // would turn a perfectly legal store file into a boot crash-loop.
        // Pinned by PersistenceCompatTest.strictParserRejectsWhatTheStoreMustAccept.
        val root = Json.parseToJsonElement(text).jsonObject
        val idsJson = root.reqObj("identities")
        val identities = LinkedHashMap<String, List<PersistedRegistration>>()
        for (identity in idsJson.keys) {
            val arr = idsJson.reqArr(identity)
            identities[identity] = arr.map { e ->
                val o = e.jsonObject
                PersistedRegistration(
                    entry = o.reqObj("entry"),
                    throttleFloorMs = o.optLongOrNull("throttle_floor_ms"),
                    oneShotCompleted = o.boolOr("one_shot_completed"),
                    scheduleAnchorMs = o.optLongOrNull("schedule_anchor_ms"),
                    lastFireFloorMs = o.optLongOrNull("last_fire_floor_ms"),
                    bootGeneration = if ("boot_generation" in o) o.reqString("boot_generation") else null,
                )
            }
        }
        return TriggerState(identities)
    }

    override fun replace(state: TriggerState) {
        val idsJson = buildJsonObject {
            for ((identity, regs) in state.identities) {
                put(identity, buildJsonArray {
                    for (r in regs) add(buildJsonObject {
                        put("entry", r.entry)
                        put("one_shot_completed", r.oneShotCompleted)
                        // The `?.let` guards are load-bearing under kotlinx and
                        // were not under org.json: `put(k, null)` REMOVED the
                        // member there, but writes a JSON null here. An unset
                        // record must stay ABSENT — a written null would read
                        // back as a present-but-unreadable member below.
                        r.throttleFloorMs?.let { put("throttle_floor_ms", it) }
                        r.scheduleAnchorMs?.let { put("schedule_anchor_ms", it) }
                        r.lastFireFloorMs?.let { put("last_fire_floor_ms", it) }
                        r.bootGeneration?.let { put("boot_generation", it) }
                    })
                })
            }
        }
        val root = buildJsonObject { put("identities", idsJson) }
        writeJsonAtomic(file, root)
    }
}

/**
 * A runtime record that is either present as an integer or absent — and absent
 * must never decay to 0, since a zero floor reads as "fire immediately"
 * (PersistenceCompatTest.triggerAbsentRuntimeFieldsStayAbsentNotZero).
 *
 * Present-but-unreadable THROWS rather than folding to absent: a corrupt floor
 * must not silently read as "no floor" and re-arm a throttled trigger. The
 * integral-double arm keeps that strictness from being a regression —
 * org.json's `getLong` truncated a `9000.0` spelling, and this store's current
 * writer is not the only thing that has ever written the file. Same tolerance
 * and same reasoning as [ReminderStore]'s `at_ms` reader; the two runtime-state
 * readers must not disagree about legacy spellings.
 *
 * The JSON-STRING coercion org.json also had is deliberately NOT restored:
 * dropping it is the module-wide policy stated in JsonAccess.kt, and every
 * writer of this file emits integer literals.
 */
private fun JsonObject.optLongOrNull(key: String): Long? =
    if (key in this)
        integralLongOrNull(this[key])
            ?: this[key]?.asDoubleOrNull()?.toLong()
            ?: throw NoSuchElementException(key)
    else null
