package com.project01.session

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.random.Random

class ReconnectionManager(
    private val networkManager: NetworkManager,
    private val scope: CoroutineScope,
    private val maxRetries: Int = 10,
    private val baseDelayMs: Long = 1000,
    private val maxDelayMs: Long = 30000,
    private val connectionTimeoutMs: Long = 10_000
) {

    companion object {
        private const val TAG = "ReconnectionManager"
    }

    sealed class ReconnectionState {
        object Idle : ReconnectionState()
        data class Reconnecting(val attempt: Int) : ReconnectionState()
        object Connected : ReconnectionState()
        object Failed : ReconnectionState()
    }

    private val _state = MutableStateFlow<ReconnectionState>(ReconnectionState.Idle)
    val state: StateFlow<ReconnectionState> = _state.asStateFlow()

    private var reconnectionJob: Job? = null

    fun startReconnecting(host: String, port: Int) {
        if (reconnectionJob?.isActive == true) return
        reconnectionJob = scope.launch {
            for (attempt in 1..maxRetries) {
                _state.value = ReconnectionState.Reconnecting(attempt)
                val delayMs = computeDelay(attempt)
                kotlinx.coroutines.delay(delayMs)

                Log.d(TAG, "Reconnection attempt $attempt to $host:$port")

                if (tryConnect(host, port)) {
                    _state.value = ReconnectionState.Connected
                    return@launch
                }
            }
            _state.value = ReconnectionState.Failed
        }
    }

    private suspend fun tryConnect(host: String, port: Int): Boolean {
        val result = CompletableDeferred<Boolean>()
        val collectJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            networkManager.events.collect { event ->
                when (event) {
                    is NetworkEvent.ClientConnected -> result.complete(true)
                    is NetworkEvent.Error -> result.complete(false)
                    else -> {}
                }
            }
        }

        networkManager.connectTo(host, port)

        val success = try {
            withTimeoutOrNull(connectionTimeoutMs) { result.await() } ?: false
        } finally {
            collectJob.cancel()
        }
        return success
    }

    fun stopReconnecting() {
        reconnectionJob?.cancel()
        reconnectionJob = null
        _state.value = ReconnectionState.Idle
    }

    fun shutdown() {
        reconnectionJob?.cancel()
        reconnectionJob = null
    }

    internal fun computeDelay(attempt: Int): Long {
        val exponential = min(baseDelayMs * (1L shl (attempt - 1)), maxDelayMs)
        return exponential + Random.nextLong(0, 501)
    }
}
