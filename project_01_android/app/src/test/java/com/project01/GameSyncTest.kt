package com.project01

import app.cash.turbine.test
import com.project01.session.GameMessage
import com.project01.session.GameSync
import com.project01.session.NetworkEvent
import com.project01.session.PlaybackCommand
import com.project01.session.PlaybackCommandType
import com.project01.session.TestNetworkManager
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.Assert.*

class GameSyncTest {

    @Test
    fun `broadcast sends data to network manager`() = runTest {
        val testNetworkManager = TestNetworkManager()
        val gameSync = GameSync(testNetworkManager)
        val message = PlaybackCommand(PlaybackCommandType.NEXT)
        var broadcastMessage: GameMessage? = null

        testNetworkManager.onBroadcast = { data ->
            broadcastMessage = data
        }

        gameSync.broadcast(message)

        assertEquals(message, broadcastMessage)
    }

    @Test
    fun `events from network manager are exposed`() = runTest {
        val testNetworkManager = TestNetworkManager()
        val gameSync = GameSync(testNetworkManager)
        val event = NetworkEvent.DataReceived(PlaybackCommand(PlaybackCommandType.NEXT), "test_address")

        gameSync.events.test {
            launch {
                testNetworkManager.emitEvent(event)
            }
            assertEquals(event, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
