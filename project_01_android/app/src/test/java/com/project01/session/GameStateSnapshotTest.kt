package com.project01.session

import org.junit.Test
import org.junit.Assert.*

class GameStateSnapshotTest {

    @Test
    fun `serialization round-trip preserves all fields`() {
        val snapshot = GameStateSnapshot(
            videoList = listOf(
                VideoDto("content://media/video1.mp4", "Video 1"),
                VideoDto("content://media/video2.mp4", "Video 2")
            ),
            currentVideoIndex = 1,
            playbackPosition = 45000L,
            isPlaying = true,
            playerAddresses = listOf("aa:bb:cc:dd:ee:f1", "aa:bb:cc:dd:ee:f2"),
            gameMasterAddress = "aa:bb:cc:dd:ee:f1",
            timestamp = 1700000000000L
        )

        val json = MessageEnvelope.json.encodeToString(GameStateSnapshot.serializer(), snapshot)
        val restored = MessageEnvelope.json.decodeFromString(GameStateSnapshot.serializer(), json)

        assertEquals(snapshot, restored)
    }

    @Test
    fun `serialization round-trip with empty lists`() {
        val snapshot = GameStateSnapshot(
            videoList = emptyList(),
            currentVideoIndex = 0,
            playbackPosition = 0L,
            isPlaying = false,
            playerAddresses = emptyList(),
            gameMasterAddress = "",
            timestamp = 1700000000000L
        )

        val json = MessageEnvelope.json.encodeToString(GameStateSnapshot.serializer(), snapshot)
        val restored = MessageEnvelope.json.decodeFromString(GameStateSnapshot.serializer(), json)

        assertEquals(snapshot, restored)
    }

    @Test
    fun `encodes and decodes as GameMessage`() {
        val snapshot = GameStateSnapshot(
            videoList = listOf(VideoDto("content://video.mp4", "Test")),
            currentVideoIndex = 0,
            playbackPosition = 1000L,
            isPlaying = false,
            playerAddresses = listOf("aa:bb:cc:dd:ee:ff"),
            gameMasterAddress = "aa:bb:cc:dd:ee:ff",
            timestamp = 1700000000000L
        )

        val encoded = MessageEnvelope.encode(snapshot)
        val decoded = MessageEnvelope.decode(encoded)

        assertTrue(decoded is GameStateSnapshot)
        assertEquals(snapshot, decoded)
    }

    @Test
    fun `json contains msg_type discriminator`() {
        val snapshot = GameStateSnapshot(
            videoList = emptyList(),
            currentVideoIndex = 0,
            playbackPosition = 0L,
            isPlaying = false,
            playerAddresses = emptyList(),
            gameMasterAddress = "",
            timestamp = 0L
        )

        val json = MessageEnvelope.json.encodeToString(GameMessage.serializer(), snapshot)
        assertTrue(json.contains("\"msg_type\":\"game_state_snapshot\""))
    }
}
