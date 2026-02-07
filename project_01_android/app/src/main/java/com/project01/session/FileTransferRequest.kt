package com.project01.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("file_transfer_request")
data class FileTransferRequest(
    val fileName: String,
    val port: Int,
    val senderAddress: String,
    val targetAddress: String
) : GameMessage
