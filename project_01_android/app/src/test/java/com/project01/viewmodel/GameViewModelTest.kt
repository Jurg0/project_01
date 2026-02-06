package com.project01.viewmodel

import android.app.Application
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper


import com.project01.session.AdvancedCommand
import com.project01.session.AdvancedCommandType
import com.project01.viewmodel.GameRepository
import com.project01.session.GameSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever
import org.mockito.kotlin.any


import kotlinx.coroutines.test.runTest

import org.robolectric.RobolectricTestRunner
import org.junit.Assert.*

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class GameViewModelTest {

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

    private lateinit var gameViewModel: GameViewModel

    private val testDispatcher = TestCoroutineDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        MockitoAnnotations.openMocks(this)
        `when`(mockGameRepository.gameSync).thenReturn(mockGameSync)
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

    @Test
    fun `turnOffScreen broadcasts correct AdvancedCommand`() = runTest {
        // When
        gameViewModel.turnOffScreen()

        // Then
        verify(mockGameSync).broadcast(AdvancedCommand(AdvancedCommandType.TURN_OFF_SCREEN))
    }

    @Test
    fun `deactivateTorch broadcasts correct AdvancedCommand`() = runTest {

        // When
        gameViewModel.deactivateTorch()

        // Then
        verify(mockGameSync).broadcast(AdvancedCommand(AdvancedCommandType.DEACTIVATE_TORCH))
    }

    @Test
    fun `game view model can be instantiated`() {
        // Since the constructor now takes mockApplication and mockGameRepository,
        // if instantiation succeeds, the test passes.
        assertNotNull(gameViewModel)
    }
}
