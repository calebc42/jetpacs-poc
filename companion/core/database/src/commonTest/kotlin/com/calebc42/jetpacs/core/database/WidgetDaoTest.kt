package com.calebc42.jetpacs.core.database

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WidgetDaoTest {
    private lateinit var database: JetpacsDatabase

    @BeforeTest
    fun setUp() {
        database = inMemoryTestDatabase()
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    @Test
    fun multipleInstancesCanBindToArbitrarySurfaces() = runTest {
        database.insertTestPairing()
        val dao = database.widgetDao()
        dao.upsertBinding(binding(41, "widget:capture"))
        dao.upsertBinding(binding(42, "widget:agenda"))
        dao.upsertBinding(binding(43, "widget:agenda"))

        assertEquals(listOf(42, 43), dao.getBindings(TEST_PAIRING_A, "widget:agenda").map { it.appWidgetId })
        assertEquals(3, dao.getBindings().size)
    }

    @Test
    fun rerenderStagesBeforePublishThenPrunesAndRemovalCascades() = runTest {
        database.insertTestPairing()
        val dao = database.widgetDao()
        dao.upsertBinding(binding(41, "widget:agenda"))
        val first = token("0".repeat(32), 41, revision = 1)
        val second = token("1".repeat(32), 41, revision = 2)
        dao.stageTokens(listOf(first))
        dao.stageTokens(listOf(second))

        // Both generations remain valid during the process-death-safe window.
        assertEquals(first, dao.getToken(first.token))
        assertEquals(second, dao.getToken(second.token))
        dao.deleteOtherTokens(41, listOf(second.token))

        assertNull(dao.getToken(first.token))
        assertEquals(second, dao.getToken(second.token))
        assertEquals(1, dao.deleteBinding(41))
        assertNull(dao.getToken(second.token))
    }

    @Test
    fun restoreRekeysBindingAndInvalidatesOldTokens() = runTest {
        database.insertTestPairing()
        val dao = database.widgetDao()
        dao.upsertBinding(binding(41, "widget:capture"))
        val token = token("a".repeat(32), 41)
        dao.stageTokens(listOf(token))

        assertTrue(dao.restoreBinding(41, 99, 30))
        assertNull(dao.getBinding(41))
        assertEquals("widget:capture", dao.getBinding(99)?.surfaceId)
        assertNull(dao.getToken(token.token))
    }

    @Test
    fun restoreSnapshotsAnOverlappingIdPermutationAtomically() = runTest {
        database.insertTestPairing()
        val dao = database.widgetDao()
        dao.upsertBinding(binding(41, "widget:capture"))
        dao.upsertBinding(binding(42, "widget:agenda"))
        dao.stageTokens(
            listOf(
                token("a".repeat(32), 41),
                token("b".repeat(32), 42),
            ),
        )

        assertEquals(2, dao.restoreBindings(listOf(41, 42), listOf(42, 99), 30))
        assertNull(dao.getBinding(41))
        assertEquals("widget:capture", dao.getBinding(42)?.surfaceId)
        assertEquals("widget:agenda", dao.getBinding(99)?.surfaceId)
        assertEquals(30, dao.getBinding(42)?.updatedAtEpochMs)
        assertNull(dao.getToken("a".repeat(32)))
        assertNull(dao.getToken("b".repeat(32)))
    }

    @Test
    fun invalidRestoreMapsLeaveEveryBindingUntouched() = runTest {
        database.insertTestPairing()
        val dao = database.widgetDao()
        dao.upsertBinding(binding(41, "widget:capture"))
        dao.upsertBinding(binding(42, "widget:agenda"))

        assertEquals(0, dao.restoreBindings(listOf(41, 42), listOf(99), 30))
        assertEquals(0, dao.restoreBindings(listOf(41, 42), listOf(99, 99), 30))
        assertEquals(listOf(41, 42), dao.getBindings().map { it.appWidgetId })
    }

    @Test
    fun readyDisconnectMarkerClearsAndColdRecoveryIsCompareAndSet() = runTest {
        database.insertTestPairing()
        val dao = database.pairingDao()

        assertEquals(1, dao.setReadyDisconnectedAtIfNull(TEST_PAIRING_A, 50))
        assertEquals(50, dao.getRuntime(TEST_PAIRING_A)?.readyDisconnectedAtEpochMs)
        assertEquals(0, dao.setReadyDisconnectedAtIfNull(TEST_PAIRING_A, 60))
        assertEquals(50, dao.getRuntime(TEST_PAIRING_A)?.readyDisconnectedAtEpochMs)

        assertEquals(1, dao.setReadyDisconnectedAt(TEST_PAIRING_A, null))
        assertNull(dao.getRuntime(TEST_PAIRING_A)?.readyDisconnectedAtEpochMs)
    }

    @Test
    fun revocationImmediatelyErasesBindingsAndTokens() = runTest {
        database.insertTestPairing()
        val dao = database.widgetDao()
        dao.upsertBinding(binding(41, "widget:agenda"))
        dao.stageTokens(listOf(token("f".repeat(32), 41)))

        assertTrue(database.revocationDao().fenceJournalAndEraseState(TEST_PAIRING_A, 20))
        assertTrue(dao.getBindings().isEmpty())
        assertNull(dao.getToken("f".repeat(32)))
    }

    @Test
    fun expiredCapabilitiesArePrunedWithoutTouchingCurrentOnes() = runTest {
        database.insertTestPairing()
        val dao = database.widgetDao()
        dao.upsertBinding(binding(41, "widget:agenda"))
        val expired = token("d".repeat(32), 41)
        val current = token("e".repeat(32), 41).copy(
            createdAtEpochMs = 900,
            expiresAtEpochMs = 2_000,
        )
        dao.stageTokens(listOf(expired, current))

        assertEquals(1, dao.deleteExpiredTokens(1_000))
        assertNull(dao.getToken(expired.token))
        assertEquals(current, dao.getToken(current.token))
    }

    private fun binding(id: Int, surface: String) = WidgetBindingEntity(
        appWidgetId = id,
        pairingId = TEST_PAIRING_A,
        surfaceId = surface,
        createdAtEpochMs = 10,
        updatedAtEpochMs = 10,
    )

    private fun token(value: String, id: Int, revision: Long = 1) = WidgetActionTokenEntity(
        token = value,
        appWidgetId = id,
        pairingId = TEST_PAIRING_A,
        surfaceId = "widget:agenda",
        revision = revision,
        descriptorJson = "{\"action\":\"fixture\"}",
        createdAtEpochMs = 10,
        expiresAtEpochMs = 1_000,
    )
}
