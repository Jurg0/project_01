package com.project01.viewmodel

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GameRepositoryTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var gameRepository: GameRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        gameRepository = GameRepository(context)
    }

    @After
    fun teardown() {
        gameRepository.shutdown()
    }

    @Test
    fun `resolveHostAddress does not throw when there is no active network`() {
        // Robolectric's default network has no gateway; the resolver must fall through all
        // three sources and report "unknown" rather than blowing up or inventing an address.
        gameRepository.resolveHostAddress()
    }

    @Test
    fun `currentPlayers reflects each update immediately so back-to-back roster writes accumulate`() {
        // Field failure: the game master showed one player while two were connected. Each
        // roster update is a read-modify-write, and reading the LiveData (which lags a
        // postValue) gave both authentications the same stale list — the second erased the
        // first. The synchronous mirror is what makes them accumulate.
        val first = com.project01.session.Player(
            android.net.wifi.p2p.WifiP2pDevice().apply { deviceAddress = "10.0.0.2" }, "10.0.0.2", false)
        val second = com.project01.session.Player(
            android.net.wifi.p2p.WifiP2pDevice().apply { deviceAddress = "10.0.0.3" }, "10.0.0.3", false)

        gameRepository.updatePlayers(gameRepository.currentPlayers + first)
        gameRepository.updatePlayers(gameRepository.currentPlayers + second)

        assertEquals(
            listOf("10.0.0.2", "10.0.0.3"),
            gameRepository.currentPlayers.map { it.device.deviceAddress },
        )
    }

    @Test
    fun `shutdown completes without throwing`() {
        gameRepository.shutdown()
    }
}
