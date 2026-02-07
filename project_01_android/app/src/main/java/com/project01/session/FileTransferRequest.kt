package com.project01.session

data class FileTransferRequest(
    val fileName: String,
    val port: Int,
    val senderAddress: String,
    val targetAddress: String
) : java.io.Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
