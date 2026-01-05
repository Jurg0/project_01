package com.project01.session

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

sealed class FileTransferEvent {
    data class Progress(val fileName: String, val progress: Int) : FileTransferEvent()
    data class Success(val fileName: String) : FileTransferEvent()
    data class Failure(val fileName: String, val error: Throwable) : FileTransferEvent()
}

class FileTransfer {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<FileTransferEvent>()
    val events = _events.asSharedFlow()

    suspend fun startReceiving(port: Int, outputFile: File) {
        withContext(Dispatchers.IO) {
            val videoTitle = outputFile.name
            try {
                ServerSocket(port).use { serverSocket ->
                    serverSocket.accept().use { clientSocket ->
                        clientSocket.getInputStream().use { inputStream ->
                            FileOutputStream(outputFile).use { fileOutputStream ->
                                val sizeBuffer = ByteArray(8)
                                inputStream.read(sizeBuffer, 0, 8)
                                val fileSize = ByteBuffer.wrap(sizeBuffer).long
                                val buffer = ByteArray(4096)
                                var bytesRead: Int = 0
                                var totalBytesRead = 0L
                                while (totalBytesRead < fileSize && inputStream.read(buffer)
                                        .also { bytesRead = it } != -1
                                ) {
                                    fileOutputStream.write(buffer, 0, bytesRead)
                                    totalBytesRead += bytesRead
                                    val progress = ((totalBytesRead * 100) / fileSize).toInt()
                                    _events.emit(FileTransferEvent.Progress(videoTitle, progress))
                                }
                                _events.emit(FileTransferEvent.Success(videoTitle))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _events.emit(FileTransferEvent.Failure(videoTitle, e))
            }
        }
    }

    suspend fun sendFile(host: String, port: Int, file: File) {
        withContext(Dispatchers.IO) {
            val videoTitle = file.name
            try {
                Socket(host, port).use { socket ->
                    socket.getOutputStream().use { outputStream ->
                        file.inputStream().use { fileInputStream ->
                            outputStream.write(ByteBuffer.allocate(8).putLong(file.length()).array())
                            val buffer = ByteArray(4096)
                            var bytesRead: Int
                            while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                            }
                            _events.emit(FileTransferEvent.Success(videoTitle))
                        }
                    }
                }
            } catch (e: Exception) {
                _events.emit(FileTransferEvent.Failure(videoTitle, e))
            }
        }
    }

    fun shutdown() {
        coroutineScope.cancel()
    }
}


