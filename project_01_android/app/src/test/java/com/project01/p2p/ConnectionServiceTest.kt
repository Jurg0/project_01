package com.project01.p2p

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

@RunWith(RobolectricTestRunner::class)
class ConnectionServiceTest {

    private lateinit var controller: ServiceController<ConnectionService>
    private lateinit var service: ConnectionService

    @Before
    fun setup() {
        controller = Robolectric.buildService(ConnectionService::class.java)
        service = controller.create().get()
    }

    @Test
    fun `onStartCommand calls startForeground with notification`() {
        controller.startCommand(0, 0)

        val shadowService = shadowOf(service)
        val notification = shadowService.lastForegroundNotification
        assertNotNull("startForeground should be called", notification)
        assertEquals(ConnectionService.NOTIFICATION_ID, shadowService.lastForegroundNotificationId)
    }

    @Test
    fun `notification channel created on API 26+`() {
        controller.startCommand(0, 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = notificationManager.getNotificationChannel(ConnectionService.CHANNEL_ID)
            assertNotNull("Notification channel should be created", channel)
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        }
    }

    @Test
    fun `service returns START_STICKY`() {
        val result = service.onStartCommand(null, 0, 0)
        assertEquals(android.app.Service.START_STICKY, result)
    }

    @Test
    fun `companion object constants are correct`() {
        assertEquals("game_connection", ConnectionService.CHANNEL_ID)
        assertEquals(1, ConnectionService.NOTIFICATION_ID)
        assertEquals(4 * 60 * 60 * 1000L, ConnectionService.WAKE_LOCK_TIMEOUT_MS)
    }

    @Test
    fun `onDestroy does not crash`() {
        controller.startCommand(0, 0)
        service.onDestroy()
        // Verify no exception thrown during cleanup
    }
}
