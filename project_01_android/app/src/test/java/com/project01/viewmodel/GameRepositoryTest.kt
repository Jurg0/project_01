package com.project01.viewmodel

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
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
    fun `shutdown completes without throwing`() {
        gameRepository.shutdown()
    }
}
