package com.project01

import com.project01.session.FileTransfer
import com.project01.session.FileTransferEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.Collections

/**
 * Real loopback transfers over real sockets.
 *
 * Uses `runBlocking`, not `runTest`: these exercise blocking socket I/O on real threads, so
 * virtual time buys nothing and only hides timing bugs.
 */
class FileTransferTest {

    private fun findFreePort(): Int {
        val socket = java.net.ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }

    /**
     * Run one transfer and return every event it produced.
     *
     * Subscribing BEFORE the transfer starts is the point: `FileTransfer.events` is a
     * MutableSharedFlow with no buffer, so anything emitted before a subscriber exists is
     * dropped. These tests used to start the transfer first and then call `events.first {}`,
     * which hung whenever the machine was fast enough to finish a small transfer before the
     * collector attached — the CI failure that surfaced this.
     *
     * Collecting everything (instead of taking the first Success) also removes a second race:
     * sender and receiver each emit their own Success, so `first` returned whichever won.
     */
    private fun runLoopbackTransfer(inputFile: File, outputFile: File): List<FileTransferEvent> =
        runBlocking {
            val fileTransfer = FileTransfer()
            val port = findFreePort()
            val events = Collections.synchronizedList(mutableListOf<FileTransferEvent>())

            val collector = launch(Dispatchers.IO) {
                fileTransfer.events.collect { events.add(it) }
            }
            delay(GRACE_MS)   // let the collector subscribe

            val receiver = launch(Dispatchers.IO) { fileTransfer.startReceiving(port, outputFile) }
            delay(GRACE_MS)   // let the ServerSocket bind before anyone dials it

            val sender = launch(Dispatchers.IO) { fileTransfer.sendFile("localhost", port, inputFile) }

            withTimeout(TRANSFER_TIMEOUT_MS) {
                receiver.join()
                sender.join()
            }
            delay(GRACE_MS)   // let the final events land
            collector.cancel()
            events.toList()
        }

    private fun List<FileTransferEvent>.successes() = filterIsInstance<FileTransferEvent.Success>()

    @Test
    fun `file transfer can send and receive a file`() {
        val content = "This is a test file."
        val inputFile = File.createTempFile("test", ".txt").apply { writeText(content) }
        val outputFile = File.createTempFile("test_out", ".txt")

        val events = runLoopbackTransfer(inputFile, outputFile)

        // Both ends report success: the sender for its file, the receiver for the copy.
        assertTrue("expected a Success, got $events", events.successes().isNotEmpty())
        assertTrue(events.none { it is FileTransferEvent.Failure })
        assertEquals(content, outputFile.readText())

        inputFile.delete()
        outputFile.delete()
    }

    @Test
    fun `checksum validation succeeds for intact file`() {
        val content = "Checksum test content with some data for validation."
        val inputFile = File.createTempFile("checksum_test", ".txt").apply { writeText(content) }
        val outputFile = File.createTempFile("checksum_out", ".txt")

        val events = runLoopbackTransfer(inputFile, outputFile)

        assertTrue("expected a Success, got $events", events.successes().isNotEmpty())
        assertTrue("intact file must not report a checksum failure",
            events.none { it is FileTransferEvent.ChecksumFailed })
        assertEquals(content, outputFile.readText())

        val expectedChecksum = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
        val actualChecksum = MessageDigest.getInstance("SHA-256").digest(outputFile.readBytes())
        assertArrayEquals(expectedChecksum, actualChecksum)

        inputFile.delete()
        outputFile.delete()
    }

    @Test
    fun `large file transfers correctly with 64KB buffer`() {
        // Larger than the 64KB buffer, so the read/write loop runs many times.
        val content = buildString { repeat(200_000) { append(('a' + (it % 26))) } }
        val inputFile = File.createTempFile("large_test", ".txt").apply { writeText(content) }
        val outputFile = File.createTempFile("large_out", ".txt")

        val events = runLoopbackTransfer(inputFile, outputFile)

        assertTrue("expected a Success, got $events", events.successes().isNotEmpty())
        assertEquals(content.length, outputFile.readText().length)
        assertEquals(content, outputFile.readText())

        inputFile.delete()
        outputFile.delete()
    }

    @Test
    fun `a failed send is actually retried`() = runBlocking<Unit> {
        // Regression: sendFile caught its own exceptions and returned normally, so the catch
        // inside sendFileWithRetry could never fire and the loop returned after ONE attempt.
        // The documented "up to 3 attempts" never ran — and the transfer most likely to need
        // it is the big one (a 200MB video over a phone hotspot).
        val fileTransfer = FileTransfer()
        val file = File.createTempFile("retry", ".bin").apply { writeText("payload") }
        val deadPort = findFreePort()   // nothing is listening, so every attempt fails

        val events = Collections.synchronizedList(mutableListOf<FileTransferEvent>())
        val collector = launch(Dispatchers.IO) {
            fileTransfer.events.collect { events.add(it) }
        }
        delay(GRACE_MS)

        fileTransfer.sendFileWithRetry("127.0.0.1", deadPort, file, maxRetries = 3)
        delay(GRACE_MS)
        collector.cancel()

        assertEquals("should retry twice before giving up",
            2, events.filterIsInstance<FileTransferEvent.RetryAttempt>().size)
        assertEquals("and report one final failure",
            1, events.filterIsInstance<FileTransferEvent.Failure>().size)

        file.delete()
    }

    private companion object {
        /** Real time, so a slow CI runner still wins the socket/subscription races. */
        const val GRACE_MS = 300L
        const val TRANSFER_TIMEOUT_MS = 60_000L
    }
}
