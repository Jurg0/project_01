package com.project01.session

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the file-transfer workflow: requesting a missing file from the GM,
 * answering a request as the GM, recording which files have landed locally,
 * and resolving incoming video lists against the local filesDir.
 *
 * The receivedVideoFiles set is shared with the surrounding ViewModel for
 * periodic status broadcasts; it's cleared at end-of-game.
 *
 * **Downloads run one at a time, in playlist order.** Every missing video used to be
 * requested at once, so with N videos the link was split N ways and nothing finished early —
 * and an interrupted pre-load left every file partial and therefore discarded. Sequential
 * means video 1 is playable while video 5 is still coming, and a session that ends early
 * keeps everything already finished. Different players still transfer in parallel: they are
 * separate devices, each running its own queue against the same host.
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

    private data class PendingTransfer(val fileName: String, val senderAddress: String)

    /** Unbounded so enqueuing never blocks the caller; a single worker drains it in order. */
    private val queue = Channel<PendingTransfer>(Channel.UNLIMITED)
    private var worker: Job? = null

    /** Titles queued or in flight, so a re-broadcast playlist can't enqueue the same file twice. */
    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun clearReceivedFiles() {
        received.clear()
        pending.clear()
        while (queue.tryReceive().isSuccess) { /* drop requests from the finished session */ }
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
     * Resolves each incoming video title against the local filesDir. Titles with a file
     * already on disk are rewritten to a file:// URI and added to receivedVideoFiles; titles
     * without a local file are returned unchanged and queued for download.
     *
     * A file on disk is always complete: downloads land in a `.part` file that is only
     * renamed into place once its checksum verifies, so an interrupted pre-load can't leave
     * a stub here that would be mistaken for a cached video.
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
                enqueue(video.title, senderAddress)
                video
            }
        }
    }

    /**
     * Queue one file. The worker fetches queued files one at a time, in the order the
     * playlist listed them.
     */
    private fun enqueue(fileName: String, senderAddress: String) {
        if (!pending.add(fileName)) return          // already queued or downloading
        queue.trySend(PendingTransfer(fileName, senderAddress))
        Log.d(TAG, "queued $fileName (${pending.size} pending)")
        startWorker()
    }

    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            for (request in queue) {
                try {
                    fetch(request)
                } catch (e: Exception) {
                    Log.w(TAG, "transfer of ${request.fileName} ended: ${e.javaClass.simpleName}")
                } finally {
                    pending.remove(request.fileName)
                }
            }
        }
    }

    /**
     * Fetch one file and return only when it has finished or definitively failed, so the
     * worker can move to the next. `startReceivingWithRetry` never throws — it exhausts its
     * retries and returns — so joining it is a reliable "this one is done either way" signal
     * and a dead transfer cannot stall the queue forever.
     *
     * We don't send our own address: the game master replies to the source IP of this
     * request's socket (see [handleFileTransferRequest]).
     */
    private suspend fun fetch(request: PendingTransfer) {
        val outputFile = File(filesDir, request.fileName)
        if (outputFile.exists()) {                  // arrived some other way meanwhile
            received.add(request.fileName)
            return
        }
        val port = findFreePort()
        Log.d(TAG, "fetching ${request.fileName} on port $port")
        coroutineScope {
            // Start listening before asking, so the host can connect back immediately.
            val receiving = launch { fileTransfer.startReceivingWithRetry(port, outputFile) }
            gameSync.broadcast(
                FileTransferRequest(request.fileName, port, request.senderAddress, targetAddress = "")
            )
            receiving.join()
        }
    }

    private companion object {
        private const val TAG = "FileTransfer"
    }
}
