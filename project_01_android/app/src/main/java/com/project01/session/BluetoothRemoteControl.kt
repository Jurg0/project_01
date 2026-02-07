package com.project01.session

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.*

class BluetoothRemoteControl(private val listener: (String) -> Unit) {

    private var connectThread: ConnectThread? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        connectThread = ConnectThread(device)
        connectThread?.start()
    }

    fun disconnect() {
        connectThread?.cancel()
    }

    private inner class ConnectThread(device: BluetoothDevice) : Thread() {

        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
        }

        override fun run() {
            // Cancel discovery because it otherwise slows down the connection.
            //bluetoothAdapter.cancelDiscovery()

            mmSocket?.let { socket ->
                // Connect to the remote device through the socket. This call blocks
                // until it succeeds or throws an exception.
                try {
                    socket.connect()
                } catch (e: IOException) {
                    // Unable to connect; close the socket and return.
                    try {
                        socket.close()
                    } catch (e: IOException) {
                        // Could not close the client socket
                    }
                    return
                }


                // The connection attempt succeeded. Perform work associated with
                // the connection in a separate thread.
                manageMyConnectedSocket(socket)
            }
        }

        // Closes the client socket and causes the thread to finish.
        fun cancel() {
            try {
                mmSocket?.close()
            } catch (e: IOException) {
                // Could not close the client socket
            }
        }
    }

    private fun manageMyConnectedSocket(socket: BluetoothSocket) {
        val inputStream = socket.inputStream
        val buffer = ByteArray(1024)
        var numBytes: Int

        try {
            while (true) {
                numBytes = inputStream.read(buffer)
                val readMessage = String(buffer, 0, numBytes)
                listener(readMessage)
            }
        } catch (_: IOException) {
            // Connection lost or closed
        } finally {
            try { inputStream.close() } catch (_: IOException) {}
            try { socket.close() } catch (_: IOException) {}
        }
    }
}