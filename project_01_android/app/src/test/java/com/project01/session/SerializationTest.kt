package com.project01.session

import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class SerializationTest {

    private fun <T> serializeAndDeserialize(obj: T): T {
        val byteArrayOutputStream = ByteArrayOutputStream()
        ObjectOutputStream(byteArrayOutputStream).use { it.writeObject(obj) }
        val bytes = byteArrayOutputStream.toByteArray()
        val byteArrayInputStream = ByteArrayInputStream(bytes)
        @Suppress("UNCHECKED_CAST")
        return ObjectInputStream(byteArrayInputStream).use { it.readObject() } as T
    }

    @Test
    fun `PlaybackCommand serialization round-trip with PLAY_PAUSE`() {
        val original = PlaybackCommand(
            type = PlaybackCommandType.PLAY_PAUSE,
            videoIndex = 3,
            playbackPosition = 12345L,
            playWhenReady = false
        )
        val deserialized = serializeAndDeserialize(original)
        assertEquals(original, deserialized)
    }

    @Test
    fun `PlaybackCommand serialization round-trip with NEXT and defaults`() {
        val original = PlaybackCommand(type = PlaybackCommandType.NEXT)
        val deserialized = serializeAndDeserialize(original)
        assertEquals(original, deserialized)
        assertEquals(PlaybackCommandType.NEXT, deserialized.type)
        assertEquals(-1, deserialized.videoIndex)
        assertEquals(-1L, deserialized.playbackPosition)
        assertEquals(true, deserialized.playWhenReady)
    }

    @Test
    fun `PlaybackCommand serialization round-trip with PREVIOUS`() {
        val original = PlaybackCommand(
            type = PlaybackCommandType.PREVIOUS,
            videoIndex = 0,
            playbackPosition = 99999L,
            playWhenReady = true
        )
        val deserialized = serializeAndDeserialize(original)
        assertEquals(original, deserialized)
    }

    @Test
    fun `PlaybackState serialization round-trip`() {
        val original = PlaybackState(
            videoIndex = 5,
            playbackPosition = 67890L,
            playWhenReady = true
        )
        val deserialized = serializeAndDeserialize(original)
        assertEquals(original, deserialized)
    }

    @Test
    fun `PlaybackState serialization round-trip with zero values`() {
        val original = PlaybackState(
            videoIndex = 0,
            playbackPosition = 0L,
            playWhenReady = false
        )
        val deserialized = serializeAndDeserialize(original)
        assertEquals(original, deserialized)
    }

    @Test
    fun `AdvancedCommand serialization round-trip with TURN_OFF_SCREEN`() {
        val original = AdvancedCommand(type = AdvancedCommandType.TURN_OFF_SCREEN)
        val deserialized = serializeAndDeserialize(original)
        assertEquals(original, deserialized)
        assertEquals(AdvancedCommandType.TURN_OFF_SCREEN, deserialized.type)
    }

    @Test
    fun `AdvancedCommand serialization round-trip with DEACTIVATE_TORCH`() {
        val original = AdvancedCommand(type = AdvancedCommandType.DEACTIVATE_TORCH)
        val deserialized = serializeAndDeserialize(original)
        assertEquals(original, deserialized)
        assertEquals(AdvancedCommandType.DEACTIVATE_TORCH, deserialized.type)
    }

    @Test
    fun `PasswordMessage serialization round-trip`() {
        val original = PasswordMessage(password = "s3cretP@ss!")
        val deserialized = serializeAndDeserialize(original)
        assertEquals(original, deserialized)
        assertEquals("s3cretP@ss!", deserialized.password)
    }

    @Test
    fun `PasswordMessage serialization round-trip with empty password`() {
        val original = PasswordMessage(password = "")
        val deserialized = serializeAndDeserialize(original)
        assertEquals(original, deserialized)
    }

    @Test
    fun `PasswordResponseMessage serialization round-trip with success`() {
        val original = PasswordResponseMessage(success = true)
        val deserialized = serializeAndDeserialize(original)
        assertEquals(original, deserialized)
        assertEquals(true, deserialized.success)
    }

    @Test
    fun `PasswordResponseMessage serialization round-trip with failure`() {
        val original = PasswordResponseMessage(success = false)
        val deserialized = serializeAndDeserialize(original)
        assertEquals(original, deserialized)
        assertEquals(false, deserialized.success)
    }

    @Test
    fun `FileTransferRequest serialization round-trip`() {
        val original = FileTransferRequest(
            fileName = "video_clip.mp4",
            port = 9090,
            senderAddress = "192.168.1.10",
            targetAddress = "192.168.1.20"
        )
        val deserialized = serializeAndDeserialize(original)
        assertEquals(original, deserialized)
        assertEquals("video_clip.mp4", deserialized.fileName)
        assertEquals(9090, deserialized.port)
        assertEquals("192.168.1.10", deserialized.senderAddress)
        assertEquals("192.168.1.20", deserialized.targetAddress)
    }

    @Test
    fun `Heartbeat serialization round-trip`() {
        val original = Heartbeat(timestamp = 1700000000000L)
        val deserialized = serializeAndDeserialize(original)
        assertEquals(original, deserialized)
        assertEquals(1700000000000L, deserialized.timestamp)
    }

    @Test
    fun `Heartbeat serialization round-trip with default timestamp`() {
        val original = Heartbeat()
        val deserialized = serializeAndDeserialize(original)
        assertEquals(original, deserialized)
        assertEquals(original.timestamp, deserialized.timestamp)
    }
}
