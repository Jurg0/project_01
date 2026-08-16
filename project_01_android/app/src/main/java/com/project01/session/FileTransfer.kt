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
import java.io.IOException
import java.net.InetSocketAddress
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

/** A completed transfer whose bytes don't match the sender's checksum. */
class ChecksumMismatchException(fileName: String) : IOException("checksum mismatch for $fileName")

class FileTransfer {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<FileTransferEvent>()
    val events = _events.asSharedFlow()

    /**
     * Receive one file, throwing on any failure. Emits Progress but NOT the terminal event —
     * the caller decides, so a retry wrapper can swallow a failed attempt and try again.
     *
     * Socket timeouts are essential here: without them a stalled sender leaves this blocked
     * forever holding the port, so the transfer neither completes nor fails and the retry
     * never happens. A 200MB video over a phone hotspot is exactly where that bites.
     */
    private suspend fun receiveOrThrow(port: Int, outputFile: File) {
        withContext(Dispatchers.IO) {
            val videoTitle = outputFile.name
            // Download to a .part file and only rename once the checksum verifies, so a
            // finished file on disk always means a COMPLETE, verified file. Writing straight
            // to outputFile meant an interrupted transfer left a stub that
            // resolveAndRequestMissing then accepted as cached — the video would be
            // permanently unplayable on that device and never re-fetched. That matters most
            // for the pre-load workflow, where phones are deliberately disconnected and large
            // files are very likely to be mid-transfer.
            val partFile = File(outputFile.parentFile, "${outputFile.name}$PART_SUFFIX")
            Log.d(TAG, "receiving $videoTitle on port $port")
            val startedAt = System.currentTimeMillis()
            try {
                ServerSocket(port).use { serverSocket ->
                    serverSocket.soTimeout = ACCEPT_TIMEOUT_MS
                    serverSocket.accept().use { clientSocket ->
                        clientSocket.soTimeout = READ_TIMEOUT_MS
                        val input = DataInputStream(clientSocket.getInputStream())

                        val fileSize = input.readLong()
                        val checksum = ByteArray(CHECKSUM_SIZE)
                        input.readFully(checksum)
                        Log.d(TAG, "$videoTitle: ${fileSize / 1024 / 1024}MB incoming")

                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(BUFFER_SIZE)
                        var totalBytesRead = 0L
                        var loggedDecile = -1

                        FileOutputStream(partFile).use { fileOutputStream ->
                            while (totalBytesRead < fileSize) {
                                val toRead = minOf(buffer.size.toLong(), fileSize - totalBytesRead).toInt()
                                val bytesRead = input.read(buffer, 0, toRead)
                                if (bytesRead == -1) {
                                    // Truncated: the old code broke out and let the checksum fail,
                                    // which reported a corrupt file instead of a retryable one.
                                    throw IOException("stream ended after $totalBytesRead of $fileSize bytes")
                                }
                                fileOutputStream.write(buffer, 0, bytesRead)
                                digest.update(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead
                                val progress = ((totalBytesRead * 100) / fileSize).toInt()
                                // Log every 10% — a 400MB file is ~6000 buffers, far too many
                                // to log individually, but silence is what made a stalled
                                // transfer impossible to tell from a slow one.
                                if (progress / 10 > loggedDecile) {
                                    loggedDecile = progress / 10
                                    Log.d(TAG, "$videoTitle: $progress% (${totalBytesRead / 1024 / 1024}MB)")
                                }
                                _events.emit(FileTransferEvent.Progress(videoTitle, progress))
                            }
                        }

                        if (!digest.digest().contentEquals(checksum)) {
                            partFile.delete()
                            Log.w(TAG, "$videoTitle: checksum mismatch, discarded")
                            throw ChecksumMismatchException(videoTitle)
                        }
                        outputFile.delete()   // replace any older copy
                        if (!partFile.renameTo(outputFile)) {
                            throw IOException("could not move $partFile into place")
                        }
                        val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
                        Log.d(TAG, "$videoTitle: complete, ${fileSize / 1024 / 1024}MB in ${"%.1f".format(seconds)}s")
                    }
                }
            } catch (e: Exception) {
                // Never leave a stub behind that a later run would mistake for a cached file.
                partFile.delete()
                Log.w(TAG, "$videoTitle: receive failed — ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
        }
    }

    suspend fun startReceiving(port: Int, outputFile: File) {
        val videoTitle = outputFile.name
        try {
            receiveOrThrow(port, outputFile)
            _events.emit(FileTransferEvent.Success(videoTitle))
        } catch (e: ChecksumMismatchException) {
            _events.emit(FileTransferEvent.ChecksumFailed(videoTitle))
        } catch (e: Exception) {
            _events.emit(FileTransferEvent.Failure(videoTitle, e))
        }
    }

    /** Send one file, throwing on failure and emitting no terminal event (see receiveOrThrow). */
    private suspend fun sendOrThrow(host: String, port: Int, file: File) {
        withContext(Dispatchers.IO) {
            val checksum = computeChecksum(file.inputStream())
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
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
                }
            }
        }
    }

    suspend fun sendFile(host: String, port: Int, file: File) {
        try {
            sendOrThrow(host, port, file)
            _events.emit(FileTransferEvent.Success(file.name))
        } catch (e: Exception) {
            _events.emit(FileTransferEvent.Failure(file.name, e))
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

    /** Send one file, throwing on failure and emitting no terminal event (see receiveOrThrow). */
    private suspend fun sendOrThrow(host: String, port: Int, uri: Uri, contentResolver: ContentResolver) {
        withContext(Dispatchers.IO) {
            val checksum = contentResolver.openInputStream(uri)?.use { computeChecksum(it) }
                ?: throw IOException("Could not open input stream for URI: $uri")

            val fileSize = queryFileSize(contentResolver, uri).let {
                if (it > 0) it else contentResolver.openInputStream(uri)?.use { s -> s.available().toLong() }
                    ?: throw IOException("Could not determine file size for URI: $uri")
            }

            val title = uri.lastPathSegment ?: "unknown_file"
            Log.d(TAG, "sending $title (${fileSize / 1024 / 1024}MB) to $host:$port")
            val startedAt = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
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
                    val seconds = (System.currentTimeMillis() - startedAt) / 1000.0
                    Log.d(TAG, "sent $title to $host in ${"%.1f".format(seconds)}s")
                } ?: throw IOException("Could not open input stream for URI: $uri")
            }
        }
    }

    suspend fun sendFile(host: String, port: Int, uri: Uri, contentResolver: ContentResolver) {
        val videoTitle = uri.lastPathSegment ?: "unknown_file"
        try {
            sendOrThrow(host, port, uri, contentResolver)
            _events.emit(FileTransferEvent.Success(videoTitle))
        } catch (e: Exception) {
            _events.emit(FileTransferEvent.Failure(videoTitle, e))
        }
    }

    suspend fun sendFileWithRetry(
        host: String, port: Int, file: File, maxRetries: Int = MAX_RETRIES
    ) {
        for (attempt in 1..maxRetries) {
            try {
                sendOrThrow(host, port, file)   // throwing core, so a retry can happen
                _events.emit(FileTransferEvent.Success(file.name))
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
                // sendOrThrow, not sendFile: sendFile swallows its own exceptions, so the
                // catch below could never fire and this loop returned after one attempt —
                // the documented "up to 3 attempts" never happened for any transfer.
                sendOrThrow(host, port, uri, contentResolver)
                _events.emit(FileTransferEvent.Success(videoTitle))
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
                receiveOrThrow(port, outputFile)   // throwing core, so a retry can happen
                _events.emit(FileTransferEvent.Success(videoTitle))
                return
            } catch (e: ChecksumMismatchException) {
                _events.emit(FileTransferEvent.ChecksumFailed(videoTitle))
                if (attempt >= maxRetries) return
                delay(BASE_RETRY_DELAY_MS * (1L shl (attempt - 1)))
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
        const val ACCEPT_TIMEOUT_MS = 60_000
        const val READ_TIMEOUT_MS = 30_000
        const val CONNECT_TIMEOUT_MS = 10_000
        const val PART_SUFFIX = ".part"
        const val BASE_RETRY_DELAY_MS = 1000L
    }
}
