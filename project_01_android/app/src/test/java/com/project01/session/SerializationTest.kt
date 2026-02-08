package com.project01.session

import org.junit.Test
import org.junit.Assert.*
import java.nio.ByteBuffer

class SerializationTest {

    private fun roundTrip(message: GameMessage): GameMessage {
        val encoded = MessageEnvelope.encode(message)
        return MessageEnvelope.decode(encoded)
    }

    @Test
    fun `PlaybackCommand serialization round-trip with PLAY_PAUSE`() {
        val original = PlaybackCommand(
            type = PlaybackCommandType.PLAY_PAUSE,
            videoIndex = 3,
            playbackPosition = 12345L,
            playWhenReady = false
        )
        val deserialized = roundTrip(original)
        assertEquals(original, deserialized)
    }

    @Test
    fun `PlaybackCommand serialization round-trip with NEXT and defaults`() {
        val original = PlaybackCommand(type = PlaybackCommandType.NEXT)
        val deserialized = roundTrip(original) as PlaybackCommand
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
        val deserialized = roundTrip(original)
        assertEquals(original, deserialized)
    }

    @Test
    fun `PlaybackState serialization round-trip`() {
        val original = PlaybackState(
            videoIndex = 5,
            playbackPosition = 67890L,
            playWhenReady = true
        )
        val deserialized = roundTrip(original)
        assertEquals(original, deserialized)
    }

    @Test
    fun `PlaybackState serialization round-trip with zero values`() {
        val original = PlaybackState(
            videoIndex = 0,
            playbackPosition = 0L,
            playWhenReady = false
        )
        val deserialized = roundTrip(original)
        assertEquals(original, deserialized)
    }

    @Test
    fun `AdvancedCommand serialization round-trip with TURN_OFF_SCREEN`() {
        val original = AdvancedCommand(type = AdvancedCommandType.TURN_OFF_SCREEN)
        val deserialized = roundTrip(original) as AdvancedCommand
        assertEquals(original, deserialized)
        assertEquals(AdvancedCommandType.TURN_OFF_SCREEN, deserialized.type)
    }

    @Test
    fun `AdvancedCommand serialization round-trip with DEACTIVATE_TORCH`() {
        val original = AdvancedCommand(type = AdvancedCommandType.DEACTIVATE_TORCH)
        val deserialized = roundTrip(original) as AdvancedCommand
        assertEquals(original, deserialized)
        assertEquals(AdvancedCommandType.DEACTIVATE_TORCH, deserialized.type)
    }

    @Test
    fun `PasswordMessage serialization round-trip`() {
        val hash = PasswordHasher.hash("s3cretP@ss!", "testnonce")
        val original = PasswordMessage(passwordHash = hash)
        val deserialized = roundTrip(original) as PasswordMessage
        assertEquals(original, deserialized)
        assertEquals(hash, deserialized.passwordHash)
    }

    @Test
    fun `PasswordMessage serialization round-trip with empty hash`() {
        val original = PasswordMessage(passwordHash = "")
        val deserialized = roundTrip(original)
        assertEquals(original, deserialized)
    }

    @Test
    fun `PasswordChallenge serialization round-trip`() {
        val nonce = PasswordHasher.generateNonce()
        val original = PasswordChallenge(nonce = nonce)
        val deserialized = roundTrip(original) as PasswordChallenge
        assertEquals(original, deserialized)
        assertEquals(nonce, deserialized.nonce)
    }

    @Test
    fun `PasswordResponseMessage serialization round-trip with success`() {
        val original = PasswordResponseMessage(success = true)
        val deserialized = roundTrip(original) as PasswordResponseMessage
        assertEquals(original, deserialized)
        assertEquals(true, deserialized.success)
    }

    @Test
    fun `PasswordResponseMessage serialization round-trip with failure`() {
        val original = PasswordResponseMessage(success = false)
        val deserialized = roundTrip(original) as PasswordResponseMessage
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
        val deserialized = roundTrip(original) as FileTransferRequest
        assertEquals(original, deserialized)
        assertEquals("video_clip.mp4", deserialized.fileName)
        assertEquals(9090, deserialized.port)
        assertEquals("192.168.1.10", deserialized.senderAddress)
        assertEquals("192.168.1.20", deserialized.targetAddress)
    }

    @Test
    fun `HeartbeatMsg serialization round-trip`() {
        val original = HeartbeatMsg(timestamp = 1700000000000L)
        val deserialized = roundTrip(original) as HeartbeatMsg
        assertEquals(original, deserialized)
        assertEquals(1700000000000L, deserialized.timestamp)
    }

    @Test
    fun `HeartbeatMsg serialization round-trip with default timestamp`() {
        val original = HeartbeatMsg()
        val deserialized = roundTrip(original) as HeartbeatMsg
        assertEquals(original.timestamp, deserialized.timestamp)
    }

    @Test
    fun `VideoListMessage serialization round-trip`() {
        val original = VideoListMessage(
            videos = listOf(
                VideoDto("content://video1", "Video 1"),
                VideoDto("content://video2", "Video 2")
            )
        )
        val deserialized = roundTrip(original) as VideoListMessage
        assertEquals(original, deserialized)
        assertEquals(2, deserialized.videos.size)
        assertEquals("content://video1", deserialized.videos[0].uriString)
        assertEquals("Video 1", deserialized.videos[0].title)
    }

    @Test
    fun `VideoListMessage serialization round-trip with empty list`() {
        val original = VideoListMessage(videos = emptyList())
        val deserialized = roundTrip(original) as VideoListMessage
        assertEquals(original, deserialized)
        assertTrue(deserialized.videos.isEmpty())
    }

    @Test
    fun `encode includes correct length prefix`() {
        val message = PlaybackCommand(PlaybackCommandType.NEXT)
        val bytes = MessageEnvelope.encode(message)
        val length = ByteBuffer.wrap(bytes, 0, 4).int
        assertEquals(bytes.size - 4, length)
    }

    @Test
    fun `JSON contains type discriminator`() {
        val message = PlaybackCommand(PlaybackCommandType.NEXT)
        val bytes = MessageEnvelope.encode(message)
        val jsonString = bytes.drop(4).toByteArray().decodeToString()
        assertTrue("JSON should contain type discriminator", jsonString.contains("\"msg_type\":\"playback_command\""))
    }
}
