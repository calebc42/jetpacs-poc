package com.calebc42.jetpacs.core.navigation

/**
 * Reconcile receiver-owned application navigation with the durable EBP cache.
 *
 * EBP `view.switch` is deliberately absent from this policy. It selects one
 * view *inside* a [JetpacsNavKey.Surface]; it never adds, removes, or replaces
 * an Android destination.
 */
fun reconcileJetpacsBackStack(
    backStack: List<JetpacsNavKey>,
    pairingId: String,
    presentAppSurfaces: Set<String>,
    onboardingRequired: Boolean,
): List<JetpacsNavKey> {
    if (onboardingRequired) return listOf(JetpacsNavKey.Pairing)

    val root = if ("app:hub" in presentAppSurfaces) {
        JetpacsNavKey.Surface(pairingId, "app:hub")
    } else {
        JetpacsNavKey.Catalog(pairingId)
    }

    val retainedDestinations = buildList {
        backStack.forEach { key ->
            val valid = when (key) {
                JetpacsNavKey.Pairing -> true
                JetpacsNavKey.Settings -> true
                is JetpacsNavKey.Catalog -> false
                is JetpacsNavKey.Surface ->
                    key.pairingId == pairingId && key.surfaceId in presentAppSurfaces && key != root
            }
            if (valid && lastOrNull() != key) add(key)
        }
    }

    return listOf(root) + retainedDestinations
}

/** Return BACKSTACK with DESTINATION pushed once at the top. */
fun pushJetpacsDestination(
    backStack: List<JetpacsNavKey>,
    destination: JetpacsNavKey,
): List<JetpacsNavKey> =
    if (backStack.lastOrNull() == destination) backStack else backStack + destination

/** Execute SPEC 14.2 `surface.open` against the hydrated app-surface cache. */
fun openPresentJetpacsSurface(
    backStack: List<JetpacsNavKey>,
    pairingId: String,
    presentAppSurfaces: Set<String>,
    surfaceId: String,
): List<JetpacsNavKey> {
    if (!surfaceId.startsWith("app:") || surfaceId !in presentAppSurfaces) {
        return backStack
    }
    return pushJetpacsDestination(
        backStack,
        JetpacsNavKey.Surface(pairingId, surfaceId),
    )
}
