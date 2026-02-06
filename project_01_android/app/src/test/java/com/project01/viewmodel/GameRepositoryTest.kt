package com.project01.viewmodel

import android.app.Application
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import com.project01.session.Player
import org.junit.Assert.assertEquals
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

    @Test
    fun `peerListListener updates players`() {
        val device1 = WifiP2pDevice().apply { deviceName = "Device 1"; deviceAddress = "00:11:22:33:44:55" }
        val device2 = WifiP2pDevice().apply { deviceName = "Device 2"; deviceAddress = "AA:BB:CC:DD:EE:FF" }
        val devices = listOf(device1, device2)

        val mockedPeerList = mock(WifiP2pDeviceList::class.java)
        `when`(mockedPeerList.deviceList).thenReturn(devices)

        gameRepository.peerListListener.onPeersAvailable(mockedPeerList)

        val expectedPlayers = listOf(Player(device1, "Device 1", false), Player(device2, "Device 2", false))
        assertEquals(expectedPlayers, gameRepository.players.value)
    }
}
