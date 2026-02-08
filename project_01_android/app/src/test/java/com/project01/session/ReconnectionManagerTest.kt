package com.project01.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class ReconnectionManagerTest {

    @Test
    fun `initial state is Idle`() = runTest {
        val networkManager = TestNetworkManager()
        val manager = ReconnectionManager(networkManager, scope = this)

        assertEquals(ReconnectionManager.ReconnectionState.Idle, manager.state.value)
    }

    @Test
    fun `reconnection succeeds on first attempt`() = runTest {
        val networkManager = TestNetworkManager()
        val manager = ReconnectionManager(
            networkManager, scope = this,
            baseDelayMs = 100, connectionTimeoutMs = 5000
        )

        networkManager.onConnectTo = { _, _ ->
            launch { networkManager.emitEvent(NetworkEvent.ClientConnected("127.0.0.1")) }
        }

        manager.startReconnecting("127.0.0.1", 8888)
        advanceUntilIdle()

        assertEquals(ReconnectionManager.ReconnectionState.Connected, manager.state.value)
    }

    @Test
    fun `reconnection retries on error and succeeds on second attempt`() = runTest {
        val networkManager = TestNetworkManager()
        val manager = ReconnectionManager(
            networkManager, scope = this,
            baseDelayMs = 100, connectionTimeoutMs = 5000
        )

        var attemptCount = 0
        networkManager.onConnectTo = { _, _ ->
            attemptCount++
            if (attemptCount == 1) {
                launch { networkManager.emitEvent(NetworkEvent.Error(Exception("Connection refused"))) }
            } else {
                launch { networkManager.emitEvent(NetworkEvent.ClientConnected("127.0.0.1")) }
            }
        }

        manager.startReconnecting("127.0.0.1", 8888)
        advanceUntilIdle()

        assertEquals(2, attemptCount)
        assertEquals(ReconnectionManager.ReconnectionState.Connected, manager.state.value)
    }

    @Test
    fun `reconnection transitions to Failed after max retries`() = runTest {
        val networkManager = TestNetworkManager()
        val maxRetries = 3
        val manager = ReconnectionManager(
            networkManager, scope = this,
            maxRetries = maxRetries, baseDelayMs = 100, connectionTimeoutMs = 500
        )

        networkManager.onConnectTo = { _, _ ->
            launch { networkManager.emitEvent(NetworkEvent.Error(Exception("Connection refused"))) }
        }

        manager.startReconnecting("127.0.0.1", 8888)
        advanceUntilIdle()

        assertEquals(ReconnectionManager.ReconnectionState.Failed, manager.state.value)
    }

    @Test
    fun `stopReconnecting cancels and returns to Idle`() = runTest {
        val networkManager = TestNetworkManager()
        val manager = ReconnectionManager(
            networkManager, scope = this,
            baseDelayMs = 10_000, connectionTimeoutMs = 5000
        )

        // Don't set up onConnectTo - connection will never succeed
        manager.startReconnecting("127.0.0.1", 8888)

        // Let first attempt start (state should be Reconnecting)
        advanceUntilIdle()

        manager.stopReconnecting()

        assertEquals(ReconnectionManager.ReconnectionState.Idle, manager.state.value)
    }

    @Test
    fun `computeDelay uses exponential backoff capped at maxDelay`() {
        val networkManager = TestNetworkManager()
        val manager = ReconnectionManager(
            networkManager, scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job()),
            baseDelayMs = 1000, maxDelayMs = 10_000
        )

        // Attempt 1: base * 2^0 = 1000 + jitter (0..500)
        val delay1 = manager.computeDelay(1)
        assertTrue("Delay 1 should be 1000-1500, was $delay1", delay1 in 1000..1500)

        // Attempt 2: base * 2^1 = 2000 + jitter
        val delay2 = manager.computeDelay(2)
        assertTrue("Delay 2 should be 2000-2500, was $delay2", delay2 in 2000..2500)

        // Attempt 4: base * 2^3 = 8000 + jitter
        val delay4 = manager.computeDelay(4)
        assertTrue("Delay 4 should be 8000-8500, was $delay4", delay4 in 8000..8500)

        // Attempt 5: base * 2^4 = 16000 → capped at 10000 + jitter
        val delay5 = manager.computeDelay(5)
        assertTrue("Delay 5 should be capped at 10000-10500, was $delay5", delay5 in 10_000..10_500)
    }

    @Test
    fun `duplicate startReconnecting is ignored while active`() = runTest {
        val networkManager = TestNetworkManager()
        val manager = ReconnectionManager(
            networkManager, scope = this,
            baseDelayMs = 10_000, connectionTimeoutMs = 5000
        )

        var connectCount = 0
        networkManager.onConnectTo = { _, _ -> connectCount++ }

        manager.startReconnecting("127.0.0.1", 8888)
        manager.startReconnecting("127.0.0.1", 8888) // Should be ignored

        advanceUntilIdle()

        // Only one reconnection loop should be active
        assertTrue(connectCount <= 10) // maxRetries default
    }
}
