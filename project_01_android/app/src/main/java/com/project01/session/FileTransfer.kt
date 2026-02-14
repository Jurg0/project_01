package com.project01.session

import android.net.Uri
import android.content.ContentResolver
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

sealed class FileTransferEvent {
    data class Progress(val fileName: String, val progress: Int) : FileTransferEvent()
    data class Success(val fileName: String) : FileTransferEvent()
    data class Failure(val fileName: String, val error: Throwable) : FileTransferEvent()
    data class RetryAttempt(val fileName: String, val attempt: Int, val maxRetries: Int) : FileTransferEvent()
    data class ChecksumFailed(val fileName: String) : FileTransferEvent()
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
                        val input = DataInputStream(clientSocket.getInputStream())

                        val fileSize = input.readLong()
                        val checksum = ByteArray(CHECKSUM_SIZE)
                        input.readFully(checksum)

                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(BUFFER_SIZE)
                        var totalBytesRead = 0L

                        FileOutputStream(outputFile).use { fileOutputStream ->
                            while (totalBytesRead < fileSize) {
                                val toRead = minOf(buffer.size.toLong(), fileSize - totalBytesRead).toInt()
                                val bytesRead = input.read(buffer, 0, toRead)
                                if (bytesRead == -1) break
                                fileOutputStream.write(buffer, 0, bytesRead)
                                digest.update(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead
                                val progress = ((totalBytesRead * 100) / fileSize).toInt()
                                _events.emit(FileTransferEvent.Progress(videoTitle, progress))
                            }
                        }

                        val computedChecksum = digest.digest()
                        if (!computedChecksum.contentEquals(checksum)) {
                            outputFile.delete()
                            _events.emit(FileTransferEvent.ChecksumFailed(videoTitle))
                        } else {
                            _events.emit(FileTransferEvent.Success(videoTitle))
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
                val checksum = computeChecksum(file.inputStream())

                Socket(host, port).use { socket ->
                    val output = DataOutputStream(socket.getOutputStream())
                    output.writeLong(file.length())
                    output.write(checksum)

                    file.inputStream().use { fileInputStream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                        output.flush()
                        _events.emit(FileTransferEvent.Success(videoTitle))
                    }
                }
            } catch (e: Exception) {
                _events.emit(FileTransferEvent.Failure(videoTitle, e))
            }
        }
    }

    private fun queryFileSize(contentResolver: ContentResolver, uri: Uri): Long {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                    return cursor.getLong(sizeIndex)
                }
            }
        }
        return -1L
    }

    suspend fun sendFile(host: String, port: Int, uri: Uri, contentResolver: ContentResolver) {
        withContext(Dispatchers.IO) {
            val videoTitle = uri.lastPathSegment ?: "unknown_file"
            try {
                val checksum = contentResolver.openInputStream(uri)?.use { computeChecksum(it) }
                    ?: throw Exception("Could not open input stream for URI: $uri")

                val fileSize = queryFileSize(contentResolver, uri).let {
                    if (it > 0) it else contentResolver.openInputStream(uri)?.use { s -> s.available().toLong() }
                        ?: throw Exception("Could not determine file size for URI: $uri")
                }

                Socket(host, port).use { socket ->
                    val output = DataOutputStream(socket.getOutputStream())
                    output.writeLong(fileSize)
                    output.write(checksum)

                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                        output.flush()
                        _events.emit(FileTransferEvent.Success(videoTitle))
                    } ?: throw Exception("Could not open input stream for URI: $uri")
                }
            } catch (e: Exception) {
                _events.emit(FileTransferEvent.Failure(videoTitle, e))
            }
        }
    }

    suspend fun sendFileWithRetry(
        host: String, port: Int, file: File, maxRetries: Int = MAX_RETRIES
    ) {
        for (attempt in 1..maxRetries) {
            try {
                sendFile(host, port, file)
                return
            } catch (e: Exception) {
                if (attempt < maxRetries) {
                    _events.emit(FileTransferEvent.RetryAttempt(file.name, attempt, maxRetries))
                    delay(BASE_RETRY_DELAY_MS * (1L shl (attempt - 1)))
                } else {
                    _events.emit(FileTransferEvent.Failure(file.name, e))
                }
            }
        }
    }

    suspend fun sendFileWithRetry(
        host: String, port: Int, uri: Uri, contentResolver: ContentResolver, maxRetries: Int = MAX_RETRIES
    ) {
        val videoTitle = uri.lastPathSegment ?: "unknown_file"
        for (attempt in 1..maxRetries) {
            try {
                sendFile(host, port, uri, contentResolver)
                return
            } catch (e: Exception) {
                if (attempt < maxRetries) {
                    _events.emit(FileTransferEvent.RetryAttempt(videoTitle, attempt, maxRetries))
                    delay(BASE_RETRY_DELAY_MS * (1L shl (attempt - 1)))
                } else {
                    _events.emit(FileTransferEvent.Failure(videoTitle, e))
                }
            }
        }
    }

    suspend fun startReceivingWithRetry(
        port: Int, outputFile: File, maxRetries: Int = MAX_RETRIES
    ) {
        val videoTitle = outputFile.name
        for (attempt in 1..maxRetries) {
            try {
                startReceiving(port, outputFile)
                return
            } catch (e: Exception) {
                if (attempt < maxRetries) {
                    _events.emit(FileTransferEvent.RetryAttempt(videoTitle, attempt, maxRetries))
                    delay(BASE_RETRY_DELAY_MS * (1L shl (attempt - 1)))
                } else {
                    _events.emit(FileTransferEvent.Failure(videoTitle, e))
                }
            }
        }
    }

    private fun computeChecksum(inputStream: java.io.InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        return digest.digest()
    }

    fun shutdown() {
        coroutineScope.cancel()
    }

    companion object {
        private const val TAG = "FileTransfer"
        const val BUFFER_SIZE = 65536
        const val CHECKSUM_SIZE = 32
        const val MAX_RETRIES = 3
        const val BASE_RETRY_DELAY_MS = 1000L
    }
}
