// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import com.calebc42.ebp.wire.EbpCommandActor
import com.calebc42.ebp.wire.FrameClose
import com.calebc42.ebp.wire.WireParseError
import com.calebc42.ebp.wire.encodeFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * PLAN-responsiveness C2a: the reader decodes on its own thread and the
 * process actor runs one command per complete frame. A frame still arriving
 * holds nothing of the actor's or the engine's, so a UI action submitted
 * meanwhile is answered at once.
 */
class FrameReaderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val actor = EbpCommandActor(scope)

    @After
    fun tearDown() {
        actor.close()
        scope.cancel()
    }

    private fun frame(id: Int, bodyBytes: Int = 0): ByteArray =
        encodeFrame(
            """{"jsonrpc":"2.0","id":"r$id","method":"noop","params":{"pad":"${"x".repeat(bodyBytes)}"}}""",
        )

    private fun idOf(message: JsonObject): String = (message["id"] as JsonPrimitive).content

    @Test
    fun aUiActionIsAnsweredWhileAMultiChunkFrameIsMidFeed() {
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut, 1 shl 20)
        val delivered = CopyOnWriteArrayList<String>()
        val firstReadDone = CountDownLatch(1)
        val reader = FrameReader(pipeIn)
        val readerThread = thread(name = "test-reader") {
            reader.run(
                open = { true },
                onFrame = { message, _ ->
                    runBlocking { actor.execute { delivered += idOf(message) } }
                },
                onFault = { false },
            )
        }
        // Two reader slices' worth of body: the frame cannot complete on the
        // first read.
        val whole = frame(1, bodyBytes = 150_000)
        val firstHalf = whole.copyOfRange(0, 70_000)
        pipeOut.write(firstHalf)
        pipeOut.flush()
        // Let the reader consume the first slice before the action lands.
        thread { while (pipeIn.available() > 0) Thread.sleep(5); firstReadDone.countDown() }
        assertTrue(firstReadDone.await(5, TimeUnit.SECONDS))

        // The "UI action": a command on the same actor, answered while the
        // frame is mid-feed because no chunk was ever submitted.
        val answered = runBlocking {
            withTimeout(2_000) { actor.execute { "answered" } }
        }
        assertEquals("answered", answered)
        assertEquals("nothing delivered while the frame is partial", emptyList<String>(), delivered)

        pipeOut.write(whole, firstHalf.size, whole.size - firstHalf.size)
        pipeOut.flush()
        pipeOut.close()
        readerThread.join(5_000)
        assertEquals(listOf("r1"), delivered)
    }

    @Test
    fun twoFramesInOneReadAreTwoCommandsInWireOrder() {
        val delivered = mutableListOf<String>()
        FrameReader(ByteArrayInputStream(frame(1) + frame(2))).run(
            open = { true },
            onFrame = { message, _ -> delivered += idOf(message) },
            onFault = { false },
        )
        assertEquals(listOf("r1", "r2"), delivered)
    }

    @Test
    fun aFrameSpanningThreeReadsIsOneCommand() {
        val delivered = mutableListOf<String>()
        FrameReader(ByteArrayInputStream(frame(1, bodyBytes = 150_000) + frame(2)), bufferSize = 65_536)
            .run(
                open = { true },
                onFrame = { message, _ -> delivered += idOf(message) },
                onFault = { false },
            )
        assertEquals(listOf("r1", "r2"), delivered)
    }

    @Test
    fun aRecoverableFaultIsReportedAndTheFramesBehindItAreDrained() {
        val delivered = mutableListOf<String>()
        val faults = mutableListOf<Exception>()
        val bad = "Content-Length: 5\r\n\r\n{nope".toByteArray()
        FrameReader(ByteArrayInputStream(bad + frame(2))).run(
            open = { true },
            onFrame = { message, _ -> delivered += idOf(message) },
            onFault = { fault -> faults += fault; true },
        )
        assertEquals(1, faults.size)
        assertTrue(faults.single() is WireParseError)
        assertEquals(listOf("r2"), delivered)
    }

    @Test
    fun aClosingFaultStopsTheReader() {
        val delivered = mutableListOf<String>()
        val faults = mutableListOf<Exception>()
        val oversized = "Content-Length: 99999999\r\n\r\n".toByteArray()
        FrameReader(ByteArrayInputStream(oversized + frame(2))).run(
            open = { true },
            onFrame = { message, _ -> delivered += idOf(message) },
            onFault = { fault -> faults += fault; false },
        )
        assertTrue(faults.single() is FrameClose)
        assertEquals(emptyList<String>(), delivered)
    }

    @Test
    fun decodeTimeIsReportedPerFrameOnlyWhenTiming() {
        val untimed = mutableListOf<Long>()
        FrameReader(ByteArrayInputStream(frame(1))).run({ true }, { _, nanos -> untimed += nanos }, { false })
        assertEquals(listOf(0L), untimed)
        val timed = mutableListOf<Long>()
        FrameReader(ByteArrayInputStream(frame(1) + frame(2)), timing = true)
            .run({ true }, { _, nanos -> timed += nanos }, { false })
        assertEquals(2, timed.size)
        assertTrue(timed.all { it > 0L })
    }
}
