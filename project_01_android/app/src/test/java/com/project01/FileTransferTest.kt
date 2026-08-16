package com.project01

import com.project01.session.FileTransfer
import com.project01.session.FileTransferEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest

class FileTransferTest {

    private fun findFreePort(): Int {
        val socket = java.net.ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }

    @Test
    fun `file transfer can send and receive a file`() = runTest {
        val fileTransfer = FileTransfer()
        val content = "This is a test file."
        val inputFile = File.createTempFile("test", ".txt").apply {
            writeText(content)
        }
        val outputFile = File.createTempFile("test_out", ".txt")
        val port = findFreePort()

        val receiverJob = launch {
            fileTransfer.startReceiving(port, outputFile)
        }

        val senderJob = launch {
            fileTransfer.sendFile("localhost", port, inputFile)
        }

        val event = fileTransfer.events.first { it is FileTransferEvent.Success }
        assertEquals(inputFile.name, (event as FileTransferEvent.Success).fileName)

        receiverJob.join()
        senderJob.join()

        assertEquals(content, outputFile.readText())

        inputFile.delete()
        outputFile.delete()
    }

    @Test
    fun `checksum validation succeeds for intact file`() = runTest {
        val fileTransfer = FileTransfer()
        val content = "Checksum test content with some data for validation."
        val inputFile = File.createTempFile("checksum_test", ".txt").apply {
            writeText(content)
        }
        val outputFile = File.createTempFile("checksum_out", ".txt")
        val port = findFreePort()

        val receiverJob = launch {
            fileTransfer.startReceiving(port, outputFile)
        }

        val senderJob = launch {
            fileTransfer.sendFile("localhost", port, inputFile)
        }

        val event = fileTransfer.events.first { it is FileTransferEvent.Success }
        assertTrue(event is FileTransferEvent.Success)

        receiverJob.join()
        senderJob.join()

        assertEquals(content, outputFile.readText())

        // Verify checksums match
        val expectedChecksum = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
        val actualChecksum = MessageDigest.getInstance("SHA-256").digest(outputFile.readBytes())
        assertArrayEquals(expectedChecksum, actualChecksum)

        inputFile.delete()
        outputFile.delete()
    }

    @Test
    fun `large file transfers correctly with 64KB buffer`() = runTest {
        val fileTransfer = FileTransfer()
        // Create content larger than 64KB buffer
        val content = "A".repeat(100_000)
        val inputFile = File.createTempFile("large_test", ".txt").apply {
            writeText(content)
        }
        val outputFile = File.createTempFile("large_out", ".txt")
        val port = findFreePort()

        val receiverJob = launch {
            fileTransfer.startReceiving(port, outputFile)
        }

        val senderJob = launch {
            fileTransfer.sendFile("localhost", port, inputFile)
        }

        val event = fileTransfer.events.first { it is FileTransferEvent.Success }
        assertTrue(event is FileTransferEvent.Success)

        receiverJob.join()
        senderJob.join()

        assertEquals(content, outputFile.readText())

        inputFile.delete()
        outputFile.delete()
    }

    @Test
    fun `a failed send is actually retried`() = runTest {
        // Regression: sendFile caught its own exceptions and returned normally, so the catch
        // inside sendFileWithRetry could never fire and the loop returned after ONE attempt.
        // The documented "up to 3 attempts" never ran — and the transfer most likely to need
        // it is the big one (a 200MB video over a phone hotspot).
        val fileTransfer = FileTransfer()
        val file = File.createTempFile("retry", ".bin").apply { writeText("payload") }
        // Nothing is listening on this port, so every attempt fails.
        val deadPort = findFreePort()

        val attempts = mutableListOf<FileTransferEvent>()
        // Unconfined so the collector subscribes immediately: events is a MutableSharedFlow
        // with no buffer, so anything emitted before a subscriber exists is dropped.
        val collector = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            fileTransfer.events.collect { attempts.add(it) }
        }

        fileTransfer.sendFileWithRetry("127.0.0.1", deadPort, file, maxRetries = 3)

        val retries = attempts.filterIsInstance<FileTransferEvent.RetryAttempt>()
        val failures = attempts.filterIsInstance<FileTransferEvent.Failure>()
        assertEquals("should retry twice before giving up", 2, retries.size)
        assertEquals("and report one final failure", 1, failures.size)
        collector.cancel()
    }
}
