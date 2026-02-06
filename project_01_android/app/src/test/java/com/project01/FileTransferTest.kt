package com.project01

import com.project01.session.FileTransfer
import com.project01.session.FileTransferEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class FileTransferTest {

    @Test
    fun `file transfer can send and receive a file`() = runTest {
        val fileTransfer = FileTransfer()
        val content = "This is a test file."
        val inputFile = File.createTempFile("test", ".txt").apply {
            writeText(content)
        }
        val outputFile = File.createTempFile("test_out", ".txt")
        val port = 9999

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
}
