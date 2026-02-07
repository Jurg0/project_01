package com.project01.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import com.project01.session.AdvancedCommand
import com.project01.session.AdvancedCommandType
import com.project01.session.NetworkEvent
import com.project01.session.GameSync
import com.project01.session.PlaybackCommand
import com.project01.session.PlaybackCommandType
import com.project01.session.PlaybackState
import com.project01.session.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineDispatcher
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
import org.mockito.kotlin.never

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
    private lateinit var mockWifiP2pManager: WifiP2pManager
    @Mock
    private lateinit var mockWifiP2pChannel: WifiP2pManager.Channel

    private lateinit var gameSyncEventLiveData: MutableLiveData<NetworkEvent>
    private lateinit var connectionInfoLiveData: MutableLiveData<WifiP2pInfo>
    private lateinit var videosLiveData: MutableLiveData<List<Video>>

    private lateinit var gameViewModel: GameViewModel

    private val testDispatcher = TestCoroutineDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        MockitoAnnotations.openMocks(this)

        gameSyncEventLiveData = MutableLiveData<NetworkEvent>()
        connectionInfoLiveData = MutableLiveData<WifiP2pInfo>()
        videosLiveData = MutableLiveData<List<Video>>()

        `when`(mockGameRepository.gameSync).thenReturn(mockGameSync)
        `when`(mockGameRepository.gameSyncEvent).thenReturn(gameSyncEventLiveData)
        `when`(mockGameRepository.connectionInfo).thenReturn(connectionInfoLiveData)
        `when`(mockGameRepository.videos).thenReturn(videosLiveData)
        `when`(mockGameRepository.players).thenReturn(MutableLiveData())
        `when`(mockGameRepository.isGameStarted).thenReturn(MutableLiveData())
        `when`(mockGameRepository.thisDevice).thenReturn(MutableLiveData())
        `when`(mockGameRepository.toastMessage).thenReturn(MutableLiveData())
        `when`(mockGameRepository.fileTransferEvent).thenReturn(MutableLiveData())
        whenever(mockApplication.getSystemService(Context.WIFI_P2P_SERVICE)).thenReturn(mockWifiP2pManager)
        whenever(mockWifiP2pManager.initialize(any(), any(), any())).thenReturn(mockWifiP2pChannel)
        whenever(mockApplication.mainLooper).thenReturn(mock(Looper::class.java))
        gameViewModel = GameViewModel(mockApplication, mockGameRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        testDispatcher.cleanupTestCoroutines()
    }

    // --- Existing tests ---

    @Test
    fun `turnOffScreen broadcasts correct AdvancedCommand`() = runTest {
        gameViewModel.turnOffScreen()
        verify(mockGameSync).broadcast(AdvancedCommand(AdvancedCommandType.TURN_OFF_SCREEN))
    }

    @Test
    fun `deactivateTorch broadcasts correct AdvancedCommand`() = runTest {
        gameViewModel.deactivateTorch()
        verify(mockGameSync).broadcast(AdvancedCommand(AdvancedCommandType.DEACTIVATE_TORCH))
    }

    @Test
    fun `game view model can be instantiated`() {
        assertNotNull(gameViewModel)
    }

    // --- isGameMaster tests ---

    @Test
    fun `isGameMaster returns false by default`() {
        assertFalse(gameViewModel.isGameMaster())
    }

    // --- playNextVideo tests ---

    @Test
    fun `playNextVideo emits NEXT command when not at last video`() {
        val videos = listOf(
            Video(Uri.parse("content://video1"), "Video 1"),
            Video(Uri.parse("content://video2"), "Video 2"),
            Video(Uri.parse("content://video3"), "Video 3")
        )
        videosLiveData.value = videos

        var emitted: PlaybackCommand? = null
        gameViewModel.playbackCommand.observeForever { emitted = it }

        gameViewModel.playNextVideo(0)

        assertNotNull(emitted)
        assertEquals(PlaybackCommandType.NEXT, emitted!!.type)
    }

    @Test
    fun `playNextVideo emits PLAY_PAUSE command when at last video`() {
        val videos = listOf(
            Video(Uri.parse("content://video1"), "Video 1"),
            Video(Uri.parse("content://video2"), "Video 2")
        )
        videosLiveData.value = videos

        var emitted: PlaybackCommand? = null
        gameViewModel.playbackCommand.observeForever { emitted = it }

        gameViewModel.playNextVideo(1) // Last index

        assertNotNull(emitted)
        assertEquals(PlaybackCommandType.PLAY_PAUSE, emitted!!.type)
    }

    @Test
    fun `playNextVideo does nothing when videos is null`() {
        // videos LiveData has no value set
        var emitted: PlaybackCommand? = null
        gameViewModel.playbackCommand.observeForever { emitted = it }

        gameViewModel.playNextVideo(0)

        assertNull(emitted)
    }

    // --- onVideoSelected tests ---

    @Test
    fun `onVideoSelected emits PLAY_PAUSE command with correct video index`() {
        val video1 = Video(Uri.parse("content://video1"), "Video 1")
        val video2 = Video(Uri.parse("content://video2"), "Video 2")
        videosLiveData.value = listOf(video1, video2)

        var emitted: PlaybackCommand? = null
        gameViewModel.playbackCommand.observeForever { emitted = it }

        gameViewModel.onVideoSelected(video2)

        assertNotNull(emitted)
        assertEquals(PlaybackCommandType.PLAY_PAUSE, emitted!!.type)
        assertEquals(1, emitted!!.videoIndex)
    }

    // --- Network event handling tests ---

    @Test
    fun `handleGameSyncEvent Error shows toast`() {
        val errorMessage = "Connection lost"
        gameSyncEventLiveData.value = NetworkEvent.Error(Exception(errorMessage))

        verify(mockGameRepository).showToast(errorMessage)
    }

    @Test
    fun `handleGameSyncEvent Error with null message shows Unknown error`() {
        gameSyncEventLiveData.value = NetworkEvent.Error(Exception())

        verify(mockGameRepository).showToast("Unknown error")
    }

    @Test
    fun `handleGameSyncEvent ClientDisconnected shows toast`() {
        gameSyncEventLiveData.value = NetworkEvent.ClientDisconnected("192.168.1.5")

        verify(mockGameRepository).showToast("Client disconnected: 192.168.1.5")
    }

    @Test
    fun `handleGameSyncEvent DataReceived with PlaybackCommand emits playback command`() {
        val command = PlaybackCommand(PlaybackCommandType.NEXT)
        val event = NetworkEvent.DataReceived(command, "192.168.1.5")

        var emitted: PlaybackCommand? = null
        gameViewModel.playbackCommand.observeForever { emitted = it }

        gameSyncEventLiveData.value = event

        assertNotNull(emitted)
        assertEquals(PlaybackCommandType.NEXT, emitted!!.type)
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
    fun `handleGameSyncEvent DataReceived with PlaybackState emits playback command and shows video`() {
        val state = PlaybackState(videoIndex = 1, playbackPosition = 5000L, playWhenReady = true)
        val event = NetworkEvent.DataReceived(state, "192.168.1.5")

        var emittedCommand: PlaybackCommand? = null
        var showVideoCalled = false
        gameViewModel.playbackCommand.observeForever { emittedCommand = it }
        gameViewModel.showVideo.observeForever { showVideoCalled = true }

        gameSyncEventLiveData.value = event

        assertNotNull(emittedCommand)
        assertEquals(PlaybackCommandType.PLAY_PAUSE, emittedCommand!!.type)
        assertEquals(1, emittedCommand!!.videoIndex)
        assertEquals(5000L, emittedCommand!!.playbackPosition)
        assertTrue(emittedCommand!!.playWhenReady)
        assertTrue(showVideoCalled)
    }

    // --- broadcastPlaybackState tests ---

    @Test
    fun `broadcastPlaybackState does not broadcast when not game master`() = runTest {
        // By default, isGameMaster() returns false
        gameViewModel.broadcastPlaybackState(1000L, true, 0)

        verify(mockGameSync, never()).broadcast(any())
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
}
