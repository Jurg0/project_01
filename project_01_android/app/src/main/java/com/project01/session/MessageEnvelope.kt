package com.project01.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer

@Serializable
@SerialName("video_list")
data class VideoListMessage(val videos: List<VideoDto>) : GameMessage

@Serializable
data class VideoDto(val uriString: String, val title: String)

@Serializable
@SerialName("heartbeat")
data class HeartbeatMsg(val timestamp: Long = System.currentTimeMillis()) : GameMessage

object MessageEnvelope {
    val json = Json {
        classDiscriminator = "msg_type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(message: GameMessage): ByteArray {
        val jsonBytes = json.encodeToString(GameMessage.serializer(), message).encodeToByteArray()
        val buffer = ByteBuffer.allocate(4 + jsonBytes.size)
        buffer.putInt(jsonBytes.size)
        buffer.put(jsonBytes)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): GameMessage {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        return readFrom(input)
    }

    fun readFrom(input: DataInputStream): GameMessage {
        val length = input.readInt()
        val jsonBytes = ByteArray(length)
        input.readFully(jsonBytes)
        return json.decodeFromString(GameMessage.serializer(), jsonBytes.decodeToString())
    }
}
