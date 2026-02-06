package com.project01.session

import android.net.wifi.p2p.WifiP2pDevice
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.junit.Assert.*

@RunWith(MockitoJUnitRunner::class)
class PlayerTest {

    @Mock
    lateinit var mockDevice: WifiP2pDevice

    @Test
    fun `player can be instantiated`() {
        val player = Player(mockDevice, "Player 1", false)
        assertEquals("Player 1", player.name)
        assertEquals(false, player.isGameMaster)
        assertEquals(mockDevice, player.device)
    }
}
