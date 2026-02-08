package com.project01.session

sealed class NetworkEvent {
    data class DataReceived(val data: GameMessage, val sender: String) : NetworkEvent()
    data class Error(val exception: Exception) : NetworkEvent()
    data class ClientConnected(val address: String) : NetworkEvent()
    data class ClientDisconnected(val address: String) : NetworkEvent()
}
