package com.project01.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class SnapshotManager(private val snapshotFile: File) {

    private var periodicJob: Job? = null

    fun startPeriodicSnapshots(
        intervalMs: Long = SNAPSHOT_INTERVAL_MS,
        scope: CoroutineScope,
        stateProvider: suspend () -> GameStateSnapshot?
    ) {
        stopPeriodicSnapshots()
        periodicJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                stateProvider()?.let { saveSnapshot(it) }
            }
        }
    }

    fun stopPeriodicSnapshots() {
        periodicJob?.cancel()
        periodicJob = null
    }

    fun saveSnapshot(snapshot: GameStateSnapshot) {
        snapshotFile.parentFile?.mkdirs()
        val tempFile = File(snapshotFile.parentFile, "${snapshotFile.name}.tmp")
        tempFile.writeText(
            MessageEnvelope.json.encodeToString(GameStateSnapshot.serializer(), snapshot)
        )
        tempFile.renameTo(snapshotFile)
    }

    fun loadSnapshot(): GameStateSnapshot? {
        if (!snapshotFile.exists()) return null
        return try {
            MessageEnvelope.json.decodeFromString(
                GameStateSnapshot.serializer(),
                snapshotFile.readText()
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clearSnapshot() {
        snapshotFile.delete()
    }

    companion object {
        const val SNAPSHOT_INTERVAL_MS = 10_000L
        const val MAX_SNAPSHOT_AGE_MS = 60 * 60 * 1000L // 1 hour
    }
}
