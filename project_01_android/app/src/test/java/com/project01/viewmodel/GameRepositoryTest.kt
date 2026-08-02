package com.project01.viewmodel

import android.app.Application
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import com.project01.session.Player
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowWifiP2pManager

@RunWith(RobolectricTestRunner::class)
class GameRepositoryTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var gameRepository: GameRepository
    private lateinit var shadowWifiP2pManager: ShadowWifiP2pManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        gameRepository = GameRepository(context)
        shadowWifiP2pManager = Shadows.shadowOf(gameRepository.wifiP2pManager)
    }

    @After
    fun teardown() {
        gameRepository.shutdown()
    }

    @Test
    fun `connectionInfoListener updates connectionInfo LiveData`() {
        val info = WifiP2pInfo()
        var observed: WifiP2pInfo? = null
        gameRepository.connectionInfo.observeForever { observed = it }

        gameRepository.connectionInfoListener.onConnectionInfoAvailable(info)

        assertEquals(info, observed)
    }

    @Test
    fun `shutdown completes without throwing`() {
        gameRepository.shutdown()
    }
}
