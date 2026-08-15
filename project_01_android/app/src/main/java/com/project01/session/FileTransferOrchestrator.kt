package com.project01.session

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the file-transfer workflow: requesting a missing file from the GM,
 * answering a request as the GM, recording which files have landed locally,
 * and resolving incoming video lists against the local filesDir.
 *
 * The receivedVideoFiles set is shared with the surrounding ViewModel for
 * periodic status broadcasts; it's cleared at end-of-game.
 */
class FileTransferOrchestrator(
    private val gameSync: GameSync,
    private val fileTransfer: FileTransfer,
    private val filesDir: File,
    private val contentResolver: ContentResolver,
    private val scope: CoroutineScope,
    private val isGameMaster: () -> Boolean,
    private val findFreePort: suspend () -> Int,
    private val videosProvider: () -> List<Video>?,
    private val updateVideos: (List<Video>) -> Unit,
) {
    private val received = mutableSetOf<String>()
    val receivedVideoFiles: Set<String>
        get() = received

    fun clearReceivedFiles() {
        received.clear()
    }

    fun onFileTransferSuccess(fileName: String) {
        received.add(fileName)
        if (isGameMaster()) return
        // Swap the playlist entry from the GM's content:// URI to the local file
        // we just received, otherwise ExoPlayer can't read it on this device.
        val currentVideos = videosProvider()?.toMutableList() ?: return
        val localUri = Uri.fromFile(File(filesDir, fileName))
        val index = currentVideos.indexOfFirst { it.title == fileName }
        if (index >= 0 && currentVideos[index].uri != localUri) {
            currentVideos[index] = Video(localUri, fileName)
            updateVideos(currentVideos)
        }
    }

    fun handleFileTransferRequest(request: FileTransferRequest, fromIp: String) {
        if (!isGameMaster()) return
        val video = videosProvider()?.find { it.title == request.fileName } ?: return
        scope.launch {
            fileTransfer.sendFileWithRetry(fromIp, request.port, video.uri, contentResolver)
        }
    }

    /**
     * Resolves each incoming video title against the local filesDir. Titles
     * with a file already on disk are rewritten to a file:// URI and added to
     * receivedVideoFiles; titles without a local file are returned unchanged
     * and a transfer is requested in the background.
     */
    fun resolveAndRequestMissing(
        videos: List<Video>,
        senderAddress: String,
    ): List<Video> {
        return videos.map { video ->
            val localFile = File(filesDir, video.title)
            if (localFile.exists()) {
                received.add(video.title)
                Video(Uri.fromFile(localFile), video.title)
            } else {
                requestFileTransfer(video.title, senderAddress)
                video
            }
        }
    }

    /**
     * Ask the game master for one file. We don't send our own address: the GM connects back
     * using the source IP of this request's TCP socket ([handleFileTransferRequest]'s
     * `fromIp`), so `FileTransferRequest.targetAddress` is never read. It used to carry the
     * Wi-Fi Direct MAC, and requesting was gated on that value being non-null — which would
     * have silently disabled every transfer once Wi-Fi Direct was removed.
     */
    private fun requestFileTransfer(
        fileName: String,
        senderAddress: String,
    ) {
        scope.launch {
            val port = findFreePort()
            val outputFile = File(filesDir, fileName)
            // Start the receive listener first so the ServerSocket is bound (or
            // about to bind) when the GM tries to connect back. sendFileWithRetry's
            // exponential backoff covers any residual race.
            launch {
                fileTransfer.startReceivingWithRetry(port, outputFile)
            }
            gameSync.broadcast(
                FileTransferRequest(fileName, port, senderAddress, targetAddress = "")
            )
        }
    }
}
