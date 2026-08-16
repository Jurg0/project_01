package com.project01.session

import android.net.Uri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.stub
import org.mockito.kotlin.doSuspendableAnswer
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FileTransferOrchestratorTest {

    private lateinit var tmpDir: File
    private val captureBroadcasts = mutableListOf<GameMessage>()
    private lateinit var network: TestNetworkManager
    private lateinit var sync: GameSync
    private lateinit var fileTransfer: FileTransfer
    private lateinit var videosState: MutableList<Video>
    private val updatedVideosLog = mutableListOf<List<Video>>()
    private var isGameMaster = false

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = TestScope(dispatcher)

    private fun newOrchestrator(): FileTransferOrchestrator {
        return FileTransferOrchestrator(
            gameSync = sync,
            fileTransfer = fileTransfer,
            filesDir = tmpDir,
            contentResolver = mock(),
            scope = scope,
            isGameMaster = { isGameMaster },
            findFreePort = { 9999 },
            videosProvider = { videosState.toList() },
            updateVideos = { updatedVideosLog.add(it) },
        )
    }

    @Before
    fun setup() {
        tmpDir = File.createTempFile("orchestrator", "").apply {
            delete()
            mkdirs()
        }
        captureBroadcasts.clear()
        updatedVideosLog.clear()
        videosState = mutableListOf()
        network = TestNetworkManager().apply {
            onBroadcast = { msg -> captureBroadcasts.add(msg) }
        }
        sync = GameSync(network)
        fileTransfer = mock()
    }

    @After
    fun teardown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `onFileTransferSuccess records file and rewrites video URI for non-game-master`() {
        isGameMaster = false
        videosState.add(Video(Uri.parse("content://gm/clip1.mp4"), "clip1.mp4"))
        val orchestrator = newOrchestrator()

        orchestrator.onFileTransferSuccess("clip1.mp4")

        assertTrue(orchestrator.receivedVideoFiles.contains("clip1.mp4"))
        assertEquals(1, updatedVideosLog.size)
        val updated = updatedVideosLog.single().single()
        assertEquals("clip1.mp4", updated.title)
        assertEquals(Uri.fromFile(File(tmpDir, "clip1.mp4")), updated.uri)
    }

    @Test
    fun `onFileTransferSuccess records file but does not rewrite URI for game master`() {
        isGameMaster = true
        videosState.add(Video(Uri.parse("content://gm/clip1.mp4"), "clip1.mp4"))
        val orchestrator = newOrchestrator()

        orchestrator.onFileTransferSuccess("clip1.mp4")

        assertTrue(orchestrator.receivedVideoFiles.contains("clip1.mp4"))
        assertTrue("GM should not rewrite its own video list", updatedVideosLog.isEmpty())
    }

    @Test
    fun `onFileTransferSuccess is idempotent when URI already points at local file`() {
        isGameMaster = false
        val localUri = Uri.fromFile(File(tmpDir, "clip1.mp4"))
        videosState.add(Video(localUri, "clip1.mp4"))
        val orchestrator = newOrchestrator()

        orchestrator.onFileTransferSuccess("clip1.mp4")

        assertTrue(orchestrator.receivedVideoFiles.contains("clip1.mp4"))
        assertTrue("No update when URI already local", updatedVideosLog.isEmpty())
    }

    @Test
    fun `handleFileTransferRequest sends file when game master holds the video`() = runTest(dispatcher) {
        isGameMaster = true
        val video = Video(Uri.parse("content://gm/clip1.mp4"), "clip1.mp4")
        videosState.add(video)
        val orchestrator = newOrchestrator()

        orchestrator.handleFileTransferRequest(
            FileTransferRequest("clip1.mp4", port = 9000, senderAddress = "gm", targetAddress = "peer"),
            fromIp = "peer",
        )
        advanceUntilIdle()

        verify(fileTransfer).sendFileWithRetry(eq("peer"), eq(9000), eq(video.uri), any(), any())
    }

    @Test
    fun `handleFileTransferRequest is no-op for non-game-master`() = runTest(dispatcher) {
        isGameMaster = false
        videosState.add(Video(Uri.parse("content://gm/clip1.mp4"), "clip1.mp4"))
        val orchestrator = newOrchestrator()

        orchestrator.handleFileTransferRequest(
            FileTransferRequest("clip1.mp4", port = 9000, senderAddress = "gm", targetAddress = "peer"),
            fromIp = "peer",
        )
        advanceUntilIdle()

        verifyNoInteractions(fileTransfer)
    }

    @Test
    fun `handleFileTransferRequest skips unknown title`() = runTest(dispatcher) {
        isGameMaster = true
        val orchestrator = newOrchestrator()

        orchestrator.handleFileTransferRequest(
            FileTransferRequest("missing.mp4", port = 9000, senderAddress = "gm", targetAddress = "peer"),
            fromIp = "peer",
        )
        advanceUntilIdle()

        verifyNoInteractions(fileTransfer)
    }

    @Test
    fun `resolveAndRequestMissing rewrites locally-cached titles and skips network request`() = runTest(dispatcher) {
        isGameMaster = false
        File(tmpDir, "cached.mp4").writeText("already-here")
        val orchestrator = newOrchestrator()

        val incoming = listOf(
            Video(Uri.parse("content://gm/cached.mp4"), "cached.mp4"),
        )

        val resolved = orchestrator.resolveAndRequestMissing(incoming, senderAddress = "gm")
        advanceUntilIdle()

        assertEquals(1, resolved.size)
        assertEquals(Uri.fromFile(File(tmpDir, "cached.mp4")), resolved[0].uri)
        assertTrue(orchestrator.receivedVideoFiles.contains("cached.mp4"))
        assertTrue("No transfer request for cached file", captureBroadcasts.isEmpty())
    }

    @Test
    fun `resolveAndRequestMissing requests transfer for missing files`() = runTest(dispatcher) {
        isGameMaster = false
        val orchestrator = newOrchestrator()
        val incoming = listOf(Video(Uri.parse("content://gm/new.mp4"), "new.mp4"))

        val resolved = orchestrator.resolveAndRequestMissing(incoming, senderAddress = "gm")
        advanceUntilIdle()

        // Returned list preserves the original (content://) URI for missing files.
        assertEquals(1, resolved.size)
        assertEquals(incoming[0].uri, resolved[0].uri)
        assertFalse(orchestrator.receivedVideoFiles.contains("new.mp4"))
        // Orchestrator broadcasts a FileTransferRequest and starts the receive listener.
        assertEquals(1, captureBroadcasts.size)
        val request = captureBroadcasts.single() as FileTransferRequest
        assertEquals("new.mp4", request.fileName)
        assertEquals(9999, request.port)
        assertEquals("gm", request.senderAddress)
        verify(fileTransfer).startReceivingWithRetry(eq(9999), any(), any())
    }

    @Test
    fun `resolveAndRequestMissing always requests, regardless of our own address`() = runTest(dispatcher) {
        // Regression: the request used to be gated on this device's Wi-Fi Direct address
        // being known. With Wi-Fi Direct removed that value was always null, which would
        // have silently stopped every video from ever transferring. The GM replies to the
        // source IP of this request's socket, so our own address is not needed.
        isGameMaster = false
        val orchestrator = newOrchestrator()
        val incoming = listOf(Video(Uri.parse("content://gm/new.mp4"), "new.mp4"))

        orchestrator.resolveAndRequestMissing(incoming, senderAddress = "gm")
        advanceUntilIdle()

        assertEquals(1, captureBroadcasts.size)
        verify(fileTransfer).startReceivingWithRetry(eq(9999), any(), any())
    }

    @Test
    fun `clearReceivedFiles empties the set`() {
        isGameMaster = false
        val orchestrator = newOrchestrator()
        orchestrator.onFileTransferSuccess("a.mp4")
        orchestrator.onFileTransferSuccess("b.mp4")
        assertEquals(2, orchestrator.receivedVideoFiles.size)

        orchestrator.clearReceivedFiles()

        assertTrue(orchestrator.receivedVideoFiles.isEmpty())
    }

    /**
     * Regression: a burst of Success events for the same playlist must swap EVERY
     * entry to its local file:// URI. This only holds when videosProvider reflects
     * the previous swap synchronously — the production wiring reads GameRepository's
     * currentVideos mirror, not the postValue-lagged videos.value. With a lagging
     * provider each swap rebuilds from the stale content:// list and the last write
     * wins, leaving all-but-one video unplayable on players (the field bug this fixes).
     */
    @Test
    fun `burst of successes swaps every entry when provider reflects prior swaps`() {
        isGameMaster = false
        // Synchronous mirror: updateVideos writes back into what videosProvider reads,
        // mirroring GameRepository.restoreVideos → currentVideos.
        val mirror = mutableListOf(
            Video(Uri.parse("content://gm/a.mp4"), "a.mp4"),
            Video(Uri.parse("content://gm/b.mp4"), "b.mp4"),
            Video(Uri.parse("content://gm/c.mp4"), "c.mp4"),
        )
        val orchestrator = FileTransferOrchestrator(
            gameSync = sync,
            fileTransfer = fileTransfer,
            filesDir = tmpDir,
            contentResolver = mock(),
            scope = scope,
            isGameMaster = { isGameMaster },
            findFreePort = { 9999 },
            videosProvider = { mirror.toList() },
            updateVideos = { mirror.clear(); mirror.addAll(it) },
        )

        orchestrator.onFileTransferSuccess("a.mp4")
        orchestrator.onFileTransferSuccess("b.mp4")
        orchestrator.onFileTransferSuccess("c.mp4")

        // Every entry ends up pointing at its local file — no swap was clobbered.
        listOf("a.mp4", "b.mp4", "c.mp4").forEach { name ->
            val entry = mirror.single { it.title == name }
            assertEquals(Uri.fromFile(File(tmpDir, name)), entry.uri)
        }
        assertEquals(setOf("a.mp4", "b.mp4", "c.mp4"), orchestrator.receivedVideoFiles)
    }

    // --- Sequential per-player queue ---

    @Test
    fun `missing videos are fetched one at a time, in playlist order`() = runTest(dispatcher) {
        // Every missing video used to be requested at once, splitting the link N ways so
        // nothing finished early — and an interrupted pre-load left every file partial and
        // therefore discarded. One at a time means video 1 is playable while video 3 is
        // still coming, and an early disconnect keeps whatever already finished.
        isGameMaster = false
        val started = mutableListOf<String>()
        val firstCanFinish = kotlinx.coroutines.CompletableDeferred<Unit>()
        fileTransfer.stub {
            onBlocking { startReceivingWithRetry(any(), any(), any()) } doSuspendableAnswer { invocation ->
                started.add(invocation.getArgument<java.io.File>(1).name)
                if (started.size == 1) firstCanFinish.await()   // hold the queue on video 1
                Unit
            }
        }
        val orchestrator = newOrchestrator()
        val incoming = listOf(
            Video(Uri.parse("content://gm/one.mp4"), "one.mp4"),
            Video(Uri.parse("content://gm/two.mp4"), "two.mp4"),
            Video(Uri.parse("content://gm/three.mp4"), "three.mp4"),
        )

        orchestrator.resolveAndRequestMissing(incoming, senderAddress = "gm")
        advanceUntilIdle()

        assertEquals("only the first video may be in flight", listOf("one.mp4"), started)
        assertEquals("and only its request goes out", 1, captureBroadcasts.size)
        assertEquals("one.mp4", (captureBroadcasts.single() as FileTransferRequest).fileName)

        firstCanFinish.complete(Unit)   // it finishes → the queue advances
        advanceUntilIdle()

        assertEquals(listOf("one.mp4", "two.mp4", "three.mp4"), started)
        assertEquals(3, captureBroadcasts.size)
        assertEquals(
            listOf("one.mp4", "two.mp4", "three.mp4"),
            captureBroadcasts.map { (it as FileTransferRequest).fileName },
        )
    }

    @Test
    fun `a playlist repeated while a transfer is in flight does not queue it twice`() = runTest(dispatcher) {
        // The host re-broadcasts its playlist on every join, so this happens routinely during
        // a long download. (A file that is still missing AFTER its transfer ended is meant to
        // be requested again — that is the retry path, not a duplicate.)
        isGameMaster = false
        val inFlight = kotlinx.coroutines.CompletableDeferred<Unit>()
        fileTransfer.stub {
            onBlocking { startReceivingWithRetry(any(), any(), any()) } doSuspendableAnswer {
                inFlight.await()
                Unit
            }
        }
        val orchestrator = newOrchestrator()
        val incoming = listOf(Video(Uri.parse("content://gm/one.mp4"), "one.mp4"))

        orchestrator.resolveAndRequestMissing(incoming, senderAddress = "gm")
        advanceUntilIdle()
        orchestrator.resolveAndRequestMissing(incoming, senderAddress = "gm")
        advanceUntilIdle()

        assertEquals("one request, not two", 1, captureBroadcasts.size)
        inFlight.complete(Unit)
    }
}
