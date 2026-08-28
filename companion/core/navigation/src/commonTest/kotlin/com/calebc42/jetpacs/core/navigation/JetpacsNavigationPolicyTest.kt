package com.calebc42.jetpacs.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class JetpacsNavigationPolicyTest {
    private val pairing = "pairing-1"
    private val catalog = JetpacsNavKey.Catalog(pairing)
    private val surface = JetpacsNavKey.Surface(pairing, "app:files")

    @Test
    fun requiredOnboardingOwnsTheOnlyDestination() {
        assertEquals(
            listOf(JetpacsNavKey.Pairing),
            reconcileJetpacsBackStack(
                listOf(catalog, surface, JetpacsNavKey.Settings),
                pairing,
                setOf("app:files"),
                onboardingRequired = true,
            ),
        )
    }

    @Test
    fun removedSurfacePopsWithoutSynthesizingAnotherDestination() {
        assertEquals(
            listOf(catalog),
            reconcileJetpacsBackStack(
                listOf(catalog, surface),
                pairing,
                emptySet(),
                onboardingRequired = false,
            ),
        )
    }

    @Test
    fun hiddenSurfaceUpdatesDoNotChangeTheSelectedRoute() {
        assertEquals(
            listOf(catalog, surface),
            reconcileJetpacsBackStack(
                listOf(catalog, surface),
                pairing,
                setOf("app:files", "app:glasspane"),
                onboardingRequired = false,
            ),
        )
    }

    @Test
    fun restoredLeafGetsAReceiverOwnedCatalogRoot() {
        assertEquals(
            listOf(catalog, surface),
            reconcileJetpacsBackStack(
                listOf(surface),
                pairing,
                setOf("app:files"),
                onboardingRequired = false,
            ),
        )
    }

    @Test
    fun completedOnboardingCannotRemainTheRestoredRoot() {
        assertEquals(
            listOf(catalog, JetpacsNavKey.Pairing),
            reconcileJetpacsBackStack(
                listOf(JetpacsNavKey.Pairing),
                pairing,
                setOf("app:files"),
                onboardingRequired = false,
            ),
        )
    }

    @Test
    fun catalogIsCanonicalizedToOneReceiverRoot() {
        assertEquals(
            listOf(catalog, surface),
            reconcileJetpacsBackStack(
                listOf(catalog, surface, catalog),
                pairing,
                setOf("app:files"),
                onboardingRequired = false,
            ),
        )
    }

    @Test
    fun keysFromAnotherPairingAreDiscarded() {
        assertEquals(
            listOf(catalog),
            reconcileJetpacsBackStack(
                listOf(
                    JetpacsNavKey.Catalog("pairing-2"),
                    JetpacsNavKey.Surface("pairing-2", "app:files"),
                ),
                pairing,
                setOf("app:files"),
                onboardingRequired = false,
            ),
        )
    }

    @Test
    fun duplicateNavigationRequestDoesNotGrowTheStack() {
        assertEquals(
            listOf(catalog, JetpacsNavKey.Settings),
            pushJetpacsDestination(
                listOf(catalog, JetpacsNavKey.Settings),
                JetpacsNavKey.Settings,
            ),
        )
    }

    @Test
    fun surfaceOpenPushesAPresentAppSurface() {
        val apps = JetpacsNavKey.Surface(pairing, "app:jetpacs.app-store")
        assertEquals(
            listOf(catalog, surface, apps),
            openPresentJetpacsSurface(
                listOf(catalog, surface),
                pairing,
                setOf("app:files", "app:jetpacs.app-store"),
                "app:jetpacs.app-store",
            ),
        )
    }

    @Test
    fun surfaceOpenIsANoOpForAnAbsentTarget() {
        assertEquals(
            listOf(catalog, surface),
            openPresentJetpacsSurface(
                listOf(catalog, surface),
                pairing,
                setOf("app:files"),
                "app:jetpacs.app-store",
            ),
        )
    }

    @Test
    fun surfaceOpenNeverSelectsANonAppDestination() {
        assertEquals(
            listOf(catalog, surface),
            openPresentJetpacsSurface(
                listOf(catalog, surface),
                pairing,
                setOf("notification:wrong"),
                "notification:wrong",
            ),
        )
    }
}
