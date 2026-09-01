package com.calebc42.jetpacs.companion

import com.calebc42.jetpacs.core.database.PairingRuntimeEntity
import com.calebc42.jetpacs.core.database.SurfaceRecordEntity
import com.calebc42.jetpacs.core.database.WidgetActionTokenEntity
import com.calebc42.jetpacs.core.database.WidgetBindingEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPolicyTest {
    @Test
    fun stalenessStartsOnlyAfterReadyDisconnectDeadline() {
        val surface = surface(staleAfterSeconds = 60)
        val runtime = runtime(disconnectedAt = 1_000)
        assertFalse(widgetIsStale(runtime, surface, 60_999))
        assertTrue(widgetIsStale(runtime, surface, 61_000))
        assertFalse(widgetIsStale(runtime(disconnectedAt = null), surface, Long.MAX_VALUE))
    }

    @Test
    fun resizeUsesCurrentConservativeMinimums() {
        assertEquals(240f, widgetSize(240, 120).width.value)
        assertEquals(120f, widgetSize(240, 120).height.value)
    }

    @Test
    fun remoteViewsBudgetLeavesBinderHeadroom() {
        assertTrue(remoteViewsFitsBudget(716_800))
        assertFalse(remoteViewsFitsBudget(716_801))
        assertFalse(remoteViewsFitsBudget(-1))
    }

    @Test
    fun coldRenderSelectsPersistedStaleSpecWithoutALiveSession() {
        val source = surface(
            staleAfterSeconds = 60,
            specJson = """{"title":"live"}""",
            staleSpecJson = """{"title":"stale"}""",
        ).toWidgetRenderSource(runtime(disconnectedAt = 1_000), 61_000, Json)

        assertEquals("stale", source?.spec?.get("title")?.jsonPrimitive?.content)
        assertTrue(source?.stale == true)
        assertNull(surface(staleAfterSeconds = 60, present = false)
            .toWidgetRenderSource(runtime(disconnectedAt = 1_000), 61_000, Json))
    }

    @Test
    fun clickCapabilityMustMatchCurrentBindingSurfaceRevisionAndExpiry() {
        val binding = WidgetBindingEntity(41, "pair", "widget:agenda", 0, 0)
        val surface = surface(staleAfterSeconds = null)
        val token = WidgetActionTokenEntity(
            token = "a".repeat(32),
            appWidgetId = 41,
            pairingId = "pair",
            surfaceId = "widget:agenda",
            revision = 2,
            descriptorJson = """{"action":"agenda.open"}""",
            createdAtEpochMs = 10,
            expiresAtEpochMs = 1_000,
        )

        assertTrue(widgetTokenMatches(token, binding, surface, 999))
        assertFalse(widgetTokenMatches(token, binding, surface, 1_000))
        assertFalse(widgetTokenMatches(token.copy(revision = 1), binding, surface, 999))
        assertFalse(widgetTokenMatches(token, binding.copy(surfaceId = "widget:other"), surface, 999))
        assertFalse(widgetTokenMatches(token, binding, surface(present = false), 999))
    }

    private fun surface(
        staleAfterSeconds: Long? = null,
        specJson: String = "{}",
        staleSpecJson: String? = null,
        present: Boolean = true,
    ) = SurfaceRecordEntity(
        pairingId = "pair",
        surfaceId = "widget:agenda",
        revision = 2,
        present = present,
        specJson = specJson.takeIf { present },
        staleSpecJson = staleSpecJson.takeIf { present },
        staleAfterSeconds = staleAfterSeconds.takeIf { present },
        currentView = null,
        acceptedAtEpochMs = 0,
        firstSeenOrdinal = 1,
    )

    private fun runtime(disconnectedAt: Long?) = PairingRuntimeEntity(
        pairingId = "pair",
        readyDisconnectedAtEpochMs = disconnectedAt,
    )
}
