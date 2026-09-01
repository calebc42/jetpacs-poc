// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkOccurrenceBookTest {
    @Test
    fun armedNetworkIsSilentUntilARealLoss() {
        val book = NetworkOccurrenceBook<String>()
        book.prime(mapOf("initial" to "wifi"))

        assertTrue(book.available("initial").isEmpty())
        assertTrue(book.capabilities("initial", "wifi").isEmpty())
        assertEquals(
            listOf(NetworkOccurrence("lost", "wifi")),
            book.lost("initial"),
        )
    }

    @Test
    fun sameTransportHandoverStillProducesBothOccurrences() {
        val book = NetworkOccurrenceBook<String>()
        book.prime(mapOf("old" to "wifi"))

        assertTrue(book.available("new").isEmpty())
        assertEquals(
            listOf(NetworkOccurrence("available", "wifi")),
            book.capabilities("new", "wifi"),
        )
        assertEquals(
            listOf(NetworkOccurrence("lost", "wifi")),
            book.lost("old"),
        )
    }

    @Test
    fun duplicateCallbacksDoNotDuplicateOccurrences() {
        val book = NetworkOccurrenceBook<String>()

        book.available("new")
        book.available("new")
        assertEquals(1, book.capabilities("new", "cellular").size)
        assertTrue(book.capabilities("new", "cellular").isEmpty())
        assertEquals(1, book.lost("new").size)
        assertTrue(book.lost("new").isEmpty())
    }
}
