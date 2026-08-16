package com.project01.viewmodel

import android.app.Application
import android.net.Uri
import android.os.Looper

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import com.project01.session.AdvancedCommand
import com.project01.session.AdvancedCommandType
import com.project01.session.NetworkEvent
import com.project01.session.GameSync
import com.project01.session.MessageEnvelope
import com.project01.session.PlaylistStore
import com.project01.session.PasswordChallenge
import com.project01.session.PasswordHasher
import com.project01.session.PasswordMessage
import com.project01.session.PasswordResponseMessage
import com.project01.session.Player
import com.project01.session.PlaybackCommand
import com.project01.session.PlaybackCommandType
import com.project01.session.PlaybackState
import com.project01.session.ReconnectionManager
import com.project01.session.SnapshotManager
import com.project01.session.Video
import com.project01.ui.ConnectionStatus
import com.project01.ui.UiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.never

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

import org.robolectric.RobolectricTestRunner
import org.junit.Assert.*

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GameViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Mock
    private lateinit var mockApplication: Application
    @Mock
    private lateinit var mockGameRepository: GameRepository
    @Mock
    private lateinit var mockGameSync: GameSync
    @Mock
    private lateinit var mockReconnectionManager: ReconnectionManager
    @Mock
    private lateinit var mockSnapshotManager: SnapshotManager
    @Mock
    private lateinit var mockPlaylistStore: PlaylistStore
    @Mock
    private lateinit var mockPreparedGameStore: com.project01.session.PreparedGameStore

    private lateinit var gameSyncEventLiveData: MutableLiveData<NetworkEvent>
    private lateinit var videosLiveData: MutableLiveData<List<Video>>

    private lateinit var gameViewModel: GameViewModel

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        MockitoAnnotations.openMocks(this)

        gameSyncEventLiveData = MutableLiveData<NetworkEvent>()
        videosLiveData = MutableLiveData<List<Video>>()

        `when`(mockGameRepository.gameSync).thenReturn(mockGameSync)
        `when`(mockGameRepository.snapshotManager).thenReturn(mockSnapshotManager)
        `when`(mockGameRepository.playlistStore).thenReturn(mockPlaylistStore)
        `when`(mockGameSync.reconnectionManager).thenReturn(mockReconnectionManager)
        `when`(mockReconnectionManager.state).thenReturn(
            kotlinx.coroutines.flow.MutableStateFlow(ReconnectionManager.ReconnectionState.Idle)
        )
        `when`(mockGameRepository.gameSyncEvent).thenReturn(gameSyncEventLiveData)
        `when`(mockGameRepository.videos).thenReturn(videosLiveData)
        `when`(mockGameRepository.players).thenReturn(MutableLiveData())
        `when`(mockGameRepository.isGameStarted).thenReturn(MutableLiveData())
        `when`(mockGameRepository.toastMessage).thenReturn(MutableLiveData())
        `when`(mockGameRepository.fileTransferEvent).thenReturn(MutableLiveData())
        whenever(mockGameRepository.isWifiEnabled()).thenReturn(true)
        whenever(mockGameRepository.resolveHostAddress()).thenReturn("192.168.43.1")
        whenever(mockGameRepository.preparedGameStore).thenReturn(mockPreparedGameStore)
        whenever(mockApplication.mainLooper).thenReturn(mock(Looper::class.java))
        // FileTransferOrchestrator captures these at GameViewModel construction.
        whenever(mockApplication.filesDir).thenReturn(java.io.File(System.getProperty("java.io.tmpdir"), "gvm-test").apply { mkdirs() })
        whenever(mockApplication.contentResolver).thenReturn(mock(android.content.ContentResolver::class.java))
        whenever(mockGameRepository.fileTransfer).thenReturn(mock(com.project01.session.FileTransfer::class.java))
        gameViewModel = GameViewModel(mockApplication, mockGameRepository)
    }

    @After
    fun tearDown() {
        // Cancel viewModelScope coroutines (periodic sync/snapshot loops) before resetting dispatcher
        try {
            val method = gameViewModel.javaClass.getDeclaredMethod("onCleared")
            method.isAccessible = true
            method.invoke(gameViewModel)
        } catch (_: Exception) {}
        Dispatchers.resetMain()
    }

    // --- Existing tests ---

    @Test
    fun `turnOffScreen broadcasts and emits locally`() = runTest {
        var emitted: AdvancedCommand? = null
        gameViewModel.advancedCommand.observeForever { emitted = it }

        gameViewModel.turnOffScreen()

        verify(mockGameSync).broadcast(AdvancedCommand(AdvancedCommandType.TURN_OFF_SCREEN))
        assertNotNull(emitted)
        assertEquals(AdvancedCommandType.TURN_OFF_SCREEN, emitted!!.type)
    }

    @Test
    fun `deactivateTorch broadcasts and emits locally`() = runTest {
        var emitted: AdvancedCommand? = null
        gameViewModel.advancedCommand.observeForever { emitted = it }

        gameViewModel.deactivateTorch()

        verify(mockGameSync).broadcast(AdvancedCommand(AdvancedCommandType.DEACTIVATE_TORCH))
        assertNotNull(emitted)
        assertEquals(AdvancedCommandType.DEACTIVATE_TORCH, emitted!!.type)
    }

    @Test
    fun `turnOnScreen broadcasts and emits locally`() = runTest {
        var emitted: AdvancedCommand? = null
        gameViewModel.advancedCommand.observeForever { emitted = it }

        gameViewModel.turnOnScreen()

        verify(mockGameSync).broadcast(AdvancedCommand(AdvancedCommandType.TURN_ON_SCREEN))
        assertNotNull(emitted)
        assertEquals(AdvancedCommandType.TURN_ON_SCREEN, emitted!!.type)
    }

    @Test
    fun `activateTorch broadcasts and emits locally`() = runTest {
        var emitted: AdvancedCommand? = null
        gameViewModel.advancedCommand.observeForever { emitted = it }

        gameViewModel.activateTorch()

        verify(mockGameSync).broadcast(AdvancedCommand(AdvancedCommandType.ACTIVATE_TORCH))
        assertNotNull(emitted)
        assertEquals(AdvancedCommandType.ACTIVATE_TORCH, emitted!!.type)
    }

    @Test
    fun `endGame broadcasts EndGameMessage and stops game`() = runTest {
        makeGameMaster("password")

        gameViewModel.endGame()
        // endGame() now delays after the broadcast to let TCP send buffers drain
        // before tearing down the P2P group. Advance virtual time past that delay.
        advanceUntilIdle()

        verify(mockGameSync).broadcast(any<com.project01.session.EndGameMessage>())
        verify(mockGameRepository).setGameStarted(false)
        verify(mockSnapshotManager).clearSnapshot()
        assertFalse(gameViewModel.isGameMaster())
    }

    @Test
    fun `receiving EndGameMessage stops game for non-master`() {
        var emitted: UiError? = null
        gameViewModel.uiError.observeForever { emitted = it }

        gameSyncEventLiveData.value = NetworkEvent.DataReceived(
            com.project01.session.EndGameMessage(), "192.168.1.1"
        )

        verify(mockGameRepository).setGameStarted(false)
        assertTrue(emitted is UiError.Informational)
        assertEquals("Game ended by host", emitted!!.message)
    }

    // --- Video management tests ---

    @Test
    fun `addVideo updates local state and broadcasts`() = runTest {
        val uri = Uri.parse("content://video1")
        videosLiveData.value = emptyList()
        whenever(mockGameRepository.getFileName(uri)).thenReturn("Test Video")

        gameViewModel.addVideo(uri)

        verify(mockGameRepository).restoreVideos(any())
        verify(mockGameSync).broadcast(any<com.project01.session.VideoListMessage>())
    }

    @Test
    fun `moveVideoUp updates local state and broadcasts`() = runTest {
        val video1 = Video(Uri.parse("content://video1"), "Video 1")
        val video2 = Video(Uri.parse("content://video2"), "Video 2")
        videosLiveData.value = listOf(video1, video2)

        gameViewModel.moveVideoUp(1)

        verify(mockGameRepository).restoreVideos(listOf(video2, video1))
        verify(mockGameSync).broadcast(any<com.project01.session.VideoListMessage>())
    }

    @Test
    fun `moveVideoDown updates local state and broadcasts`() = runTest {
        val video1 = Video(Uri.parse("content://video1"), "Video 1")
        val video2 = Video(Uri.parse("content://video2"), "Video 2")
        videosLiveData.value = listOf(video1, video2)

        gameViewModel.moveVideoDown(0)

        verify(mockGameRepository).restoreVideos(listOf(video2, video1))
        verify(mockGameSync).broadcast(any<com.project01.session.VideoListMessage>())
    }

    @Test
    fun `removeVideo updates local state and broadcasts`() = runTest {
        val video1 = Video(Uri.parse("content://video1"), "Video 1")
        val video2 = Video(Uri.parse("content://video2"), "Video 2")
        videosLiveData.value = listOf(video1, video2)

        gameViewModel.removeVideo(0)

        verify(mockGameRepository).restoreVideos(listOf(video2))
        verify(mockGameSync).broadcast(any<com.project01.session.VideoListMessage>())
    }

    @Test
    fun `handleVideoList updates local state for all devices`() {
        val videoDto = com.project01.session.VideoDto("content://video1", "Video 1")
        val event = NetworkEvent.DataReceived(
            com.project01.session.VideoListMessage(listOf(videoDto)), "192.168.1.5"
        )

        gameSyncEventLiveData.value = event

        verify(mockGameRepository).restoreVideos(any())
    }

    // --- isGameMaster tests ---

    @Test
    fun `isGameMaster returns false by default`() {
        assertFalse(gameViewModel.isGameMaster())
    }

    // --- onVideoSelected tests ---

    @Test
    fun `onVideoSelected updates playback intent to play the selected video`() {
        val video1 = Video(Uri.parse("content://video1"), "Video 1")
        val video2 = Video(Uri.parse("content://video2"), "Video 2")
        videosLiveData.value = listOf(video1, video2)

        gameViewModel.onVideoSelected(video2)

        val intent = gameViewModel.playbackController.currentIntent()
        assertEquals(1, intent.videoIndex)
        assertEquals(0L, intent.positionMs)
        assertTrue(intent.isPlaying)
    }

    // --- Network event handling tests ---

    @Test
    fun `handleGameSyncEvent Error emits Recoverable UiError with origin and class`() {
        var emitted: UiError? = null
        gameViewModel.uiError.observeForever { emitted = it }

        gameSyncEventLiveData.value = NetworkEvent.Error(
            java.net.SocketException("Connection reset"),
            "broadcast→192.168.49.5"
        )

        assertTrue(emitted is UiError.Recoverable)
        assertEquals("broadcast→192.168.49.5: SocketException: Connection reset", emitted!!.message)
    }

    @Test
    fun `handleGameSyncEvent Error with null message still identifies origin and class`() {
        var emitted: UiError? = null
        gameViewModel.uiError.observeForever { emitted = it }

        gameSyncEventLiveData.value = NetworkEvent.Error(java.net.SocketException(), "handleClient")

        assertTrue(emitted is UiError.Recoverable)
        assertEquals("handleClient: SocketException: (no message)", emitted!!.message)
    }

    @Test
    fun `handleGameSyncEvent ClientDisconnected emits Informational UiError for game master`() {
        makeGameMaster("password")
        var emitted: UiError? = null
        gameViewModel.uiError.observeForever { emitted = it }

        gameSyncEventLiveData.value = NetworkEvent.ClientDisconnected("192.168.1.5")

        assertTrue(emitted is UiError.Informational)
        assertEquals("Client disconnected: 192.168.1.5", emitted!!.message)
    }

    @Test
    fun `handleGameSyncEvent ClientDisconnected triggers reconnect for non-game-master`() {
        whenever(mockGameSync.port).thenReturn(8888)
        whenever(mockGameRepository.resolveHostAddress()).thenReturn("192.168.1.1")
        gameViewModel.join("password")   // resolves the host and dials it, setting lastHost

        gameSyncEventLiveData.value = NetworkEvent.ClientDisconnected("192.168.1.1")

        verify(mockReconnectionManager).startReconnecting("192.168.1.1", 8888)
    }

    @Test
    fun `handleGameSyncEvent ClientDisconnected does not trigger reconnect for game master`() {
        makeGameMaster("password")
        gameSyncEventLiveData.value = NetworkEvent.ClientDisconnected("192.168.1.5")

        verify(mockReconnectionManager, never()).startReconnecting(any(), any())
    }

    @Test
    fun `handleGameSyncEvent ClientConnected sets CONNECTED state and stops reconnection`() {
        var state: ConnectionStatus? = null
        gameViewModel.connectionState.observeForever { state = it }

        gameSyncEventLiveData.value = NetworkEvent.ClientConnected("192.168.1.1")

        verify(mockReconnectionManager).stopReconnecting()
        assertEquals(ConnectionStatus.CONNECTED, state)
    }

    @Test
    fun `handleGameSyncEvent DataReceived with PlaybackCommand updates intent`() {
        val command = PlaybackCommand(PlaybackCommandType.PLAY_PAUSE, videoIndex = 2, playbackPosition = 3000L, playWhenReady = true)
        val event = NetworkEvent.DataReceived(command, "192.168.1.5")

        gameSyncEventLiveData.value = event

        val intent = gameViewModel.playbackController.currentIntent()
        assertEquals(2, intent.videoIndex)
        assertEquals(3000L, intent.positionMs)
        assertTrue(intent.isPlaying)
    }

    @Test
    fun `handleGameSyncEvent DataReceived with AdvancedCommand emits advanced command`() {
        val command = AdvancedCommand(AdvancedCommandType.TURN_OFF_SCREEN)
        val event = NetworkEvent.DataReceived(command, "192.168.1.5")

        var emitted: AdvancedCommand? = null
        gameViewModel.advancedCommand.observeForever { emitted = it }

        gameSyncEventLiveData.value = event

        assertNotNull(emitted)
        assertEquals(AdvancedCommandType.TURN_OFF_SCREEN, emitted!!.type)
    }

    @Test
    fun `handleGameSyncEvent DataReceived with PlaybackState ignores state that disagrees with intent`() {
        // Default intent is (0, 0, false); a state disagreeing on videoIndex
        // and playing flag is a stale drift broadcast that raced a newer
        // command. PlaybackCommand is the only authority on those fields, so
        // the state is dropped and intent stays at its default.
        val state = PlaybackState(videoIndex = 1, playbackPosition = 5000L, playWhenReady = true)
        val event = NetworkEvent.DataReceived(state, "192.168.1.5")

        gameSyncEventLiveData.value = event

        val intent = gameViewModel.playbackController.currentIntent()
        assertEquals(0, intent.videoIndex)
        assertEquals(0L, intent.positionMs)
        assertFalse(intent.isPlaying)
    }

    // --- onCleared tests ---

    @Test
    fun `onCleared removes observers and shuts down repository`() {
        // Trigger onCleared via reflection since it's protected
        val method = gameViewModel.javaClass.getDeclaredMethod("onCleared")
        method.isAccessible = true
        method.invoke(gameViewModel)

        verify(mockGameRepository).shutdown()
    }

    // --- Password challenge-response tests ---

    @Test
    fun `joinGame after receiving challenge sends hashed password`() = runTest {
        val nonce = "abc123"

        // Receive challenge first
        gameSyncEventLiveData.value = NetworkEvent.DataReceived(
            PasswordChallenge(nonce), "192.168.1.1"
        )

        // Then join with password
        gameViewModel.joinGame("Player", "mypassword")

        val expectedHash = PasswordHasher.hash("mypassword", nonce)
        verify(mockGameSync).broadcast(PasswordMessage(passwordHash = expectedHash))
    }

    @Test
    fun `joinGame before receiving challenge sends hash when challenge arrives`() = runTest {
        val nonce = "def456"

        // Enter password first
        gameViewModel.joinGame("Player", "mypassword")

        // Verify nothing sent yet (no challenge received)
        verify(mockGameSync, never()).broadcast(any())

        // Then receive challenge
        gameSyncEventLiveData.value = NetworkEvent.DataReceived(
            PasswordChallenge(nonce), "192.168.1.1"
        )

        val expectedHash = PasswordHasher.hash("mypassword", nonce)
        verify(mockGameSync).broadcast(PasswordMessage(passwordHash = expectedHash))
    }

    private fun makeGameMaster(password: String) {
        // The role is claimed directly by createGame now — there is no WifiP2pInfo to post.
        gameViewModel.createGame(password)
    }

    @Test
    fun `server verifies correct password hash`() = runTest {
        val nonce = "servernonce"
        val password = "correctpassword"
        makeGameMaster(password)

        `when`(mockGameSync.consumeNonce("192.168.1.5")).thenReturn(nonce)

        val correctHash = PasswordHasher.hash(password, nonce)
        gameSyncEventLiveData.value = NetworkEvent.DataReceived(
            PasswordMessage(passwordHash = correctHash), "192.168.1.5"
        )

        // Per-client response (sendTo), not broadcast — the hard gate answers the joiner privately.
        verify(mockGameSync).sendTo("192.168.1.5", PasswordResponseMessage(success = true))
    }

    @Test
    fun `server rejects incorrect password hash`() = runTest {
        val nonce = "servernonce"
        makeGameMaster("correctpassword")

        `when`(mockGameSync.consumeNonce("192.168.1.5")).thenReturn(nonce)

        val wrongHash = PasswordHasher.hash("wrongpassword", nonce)
        gameSyncEventLiveData.value = NetworkEvent.DataReceived(
            PasswordMessage(passwordHash = wrongHash), "192.168.1.5"
        )

        verify(mockGameSync).sendTo("192.168.1.5", PasswordResponseMessage(success = false))
    }

    @Test
    fun `server rejects password when nonce is missing`() = runTest {
        makeGameMaster("password")

        `when`(mockGameSync.consumeNonce("192.168.1.5")).thenReturn(null)

        gameSyncEventLiveData.value = NetworkEvent.DataReceived(
            PasswordMessage(passwordHash = "somehash"), "192.168.1.5"
        )

        verify(mockGameSync).sendTo("192.168.1.5", PasswordResponseMessage(success = false))
    }

    @Test
    fun `PasswordResponseMessage updates passwordVerified LiveData`() {
        var verified: Boolean? = null
        gameViewModel.passwordVerified.observeForever { verified = it }

        gameSyncEventLiveData.value = NetworkEvent.DataReceived(
            PasswordResponseMessage(success = true), "192.168.1.1"
        )

        assertEquals(true, verified)
    }

    // --- ConnectionStatus tests ---

    @Test
    fun `ClientDisconnected without host info sets DISCONNECTED state`() {
        var state: ConnectionStatus? = null
        gameViewModel.connectionState.observeForever { state = it }

        gameSyncEventLiveData.value = NetworkEvent.ClientDisconnected("192.168.1.1")

        assertEquals(ConnectionStatus.DISCONNECTED, state)
    }

    @Test
    fun `ClientDisconnected as game master does not change connection state`() {
        makeGameMaster("password")
        var state: ConnectionStatus? = null
        gameViewModel.connectionState.observeForever { state = it }
        // makeGameMaster sets connection state to HOST; capture it before the event
        val stateAfterMakeGameMaster = state

        gameSyncEventLiveData.value = NetworkEvent.ClientDisconnected("192.168.1.5")

        assertEquals(stateAfterMakeGameMaster, state)
    }

    // --- Protocol version tests ---

    @Test
    fun `protocol version mismatch emits Critical UiError and does not send password`() = runTest {
        gameViewModel.joinGame("Player", "mypassword")

        var emitted: UiError? = null
        gameViewModel.uiError.observeForever { emitted = it }

        gameSyncEventLiveData.value = NetworkEvent.DataReceived(
            PasswordChallenge(nonce = "abc123", protocolVersion = 999), "192.168.1.1"
        )

        assertTrue(emitted is UiError.Critical)
        assertTrue(emitted!!.message.contains("Incompatible app version"))
        verify(mockGameSync, never()).broadcast(any())
    }

    @Test
    fun `matching protocol version proceeds with authentication`() = runTest {
        val nonce = "abc123"

        gameSyncEventLiveData.value = NetworkEvent.DataReceived(
            PasswordChallenge(nonce = nonce, protocolVersion = MessageEnvelope.PROTOCOL_VERSION), "192.168.1.1"
        )

        gameViewModel.joinGame("Player", "mypassword")

        val expectedHash = PasswordHasher.hash("mypassword", nonce)
        verify(mockGameSync).broadcast(PasswordMessage(passwordHash = expectedHash))
    }

    // --- CREATE resolves a prepared game by password ---

    @Test
    fun `createGameForPassword starts nothing when no prepared game matches`() {
        // Field failure: a mistyped password started the most recently used game — wrong
        // content in front of the players, and a false success. Doing nothing is just as
        // invisible to an onlooker as starting a session, and the game master sees the
        // screen stay put.
        whenever(mockPreparedGameStore.findByPassword("wrong")).thenReturn(null)

        gameViewModel.createGameForPassword("wrong")

        assertFalse("must not claim the host role", gameViewModel.isGameMaster())
        verify(mockGameRepository, never()).setGameStarted(true)
    }

    @Test
    fun `createGameForPassword starts the matching prepared game`() {
        val prepared = com.project01.session.PreparedGame(
            name = "woods",
            password = "raven",
            videos = listOf(com.project01.session.VideoDto("content://a.mp4", "a.mp4")),
        )
        whenever(mockPreparedGameStore.findByPassword("raven")).thenReturn(prepared)

        gameViewModel.createGameForPassword("raven")

        assertTrue("the matching game must start", gameViewModel.isGameMaster())
        verify(mockGameRepository).setGameStarted(true)
    }
}
