// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import com.calebc42.ebp.wire.SessionState
import com.calebc42.ebp.renderer.model.EditorConnectionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectNotificationTest {
    @Test
    fun notificationTargetsAndroidEmacs() {
        assertEquals("org.gnu.emacs", ANDROID_EMACS_PACKAGE)
    }

    @Test
    fun onlyAuthenticatedStatesCountAsAConnection() {
        assertFalse(isAuthenticatedConnectionState(SessionState.CONNECTED))
        assertFalse(isAuthenticatedConnectionState(SessionState.CHALLENGED))
        assertTrue(isAuthenticatedConnectionState(SessionState.SYNCING))
        assertTrue(isAuthenticatedConnectionState(SessionState.READY))
        assertFalse(isAuthenticatedConnectionState(SessionState.CLOSED))
    }

    @Test
    fun editorReadinessExposesOnlySyncingAndReadyAsInteractiveLifecycleStates() {
        assertEquals(EditorConnectionPhase.OFFLINE, editorConnectionPhaseOf(null))
        assertEquals(
            EditorConnectionPhase.OFFLINE,
            editorConnectionPhaseOf(SessionState.CHALLENGED),
        )
        assertEquals(
            EditorConnectionPhase.OPENING,
            editorConnectionPhaseOf(SessionState.SYNCING),
        )
        assertEquals(
            EditorConnectionPhase.READY,
            editorConnectionPhaseOf(SessionState.READY),
        )
        assertEquals(
            EditorConnectionPhase.OFFLINE,
            editorConnectionPhaseOf(SessionState.CLOSED),
        )
    }

    @Test
    fun onlyCurrentAuthenticatedSessionLossPostsReconnect() {
        assertTrue(shouldPostReconnectNotification(true, true))
        assertFalse(shouldPostReconnectNotification(false, true))
        assertFalse(shouldPostReconnectNotification(true, false))
        assertFalse(shouldPostReconnectNotification(false, false))
    }

    @Test
    fun connectionIndicatorTracksTheNewestAuthenticatedSession() {
        val first = Any()
        val replacement = Any()
        val tracker = AuthenticatedConnectionTracker<Any>()

        assertFalse(tracker.connected.value)
        tracker.authenticated(first)
        assertTrue(tracker.connected.value)

        tracker.authenticated(replacement)
        assertFalse(tracker.disconnected(first))
        assertTrue(tracker.connected.value)

        assertTrue(tracker.disconnected(replacement))
        assertFalse(tracker.connected.value)
    }

    @Test
    fun readyLifecycleBelongsOnlyToTheNewestTransportGeneration() {
        val first = Any()
        val replacement = Any()
        val tracker = ReadyConnectionTracker<Any>()

        assertFalse(tracker.connected())
        assertFalse(tracker.supersede(1))
        assertTrue(tracker.ready(1, first))
        assertTrue(tracker.connected())

        // Accepting the successor ends the previous READY authority before
        // the successor authenticates. Delayed callbacks from generation 1
        // cannot mutate generation 2's lifecycle.
        assertTrue(tracker.supersede(2))
        assertFalse(tracker.connected())
        assertFalse(tracker.ready(1, first))
        assertFalse(tracker.disconnected(1, first))

        assertTrue(tracker.ready(2, replacement))
        assertFalse(tracker.disconnected(2, first))
        assertTrue(tracker.connected())
        assertTrue(tracker.disconnected(2, replacement))
        assertFalse(tracker.connected())
    }
}
