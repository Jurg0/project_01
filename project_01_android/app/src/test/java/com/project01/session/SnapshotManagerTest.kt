package com.project01.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotManagerTest {

    private lateinit var tempDir: File
    private lateinit var snapshotFile: File
    private lateinit var manager: SnapshotManager

    private fun testSnapshot(timestamp: Long = System.currentTimeMillis()) = GameStateSnapshot(
        videoList = listOf(VideoDto("content://video.mp4", "Test Video")),
        currentVideoIndex = 1,
        playbackPosition = 5000L,
        isPlaying = true,
        playerAddresses = listOf("aa:bb:cc:dd:ee:ff"),
        gameMasterAddress = "aa:bb:cc:dd:ee:ff",
        timestamp = timestamp
    )

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "snapshot_test_${System.nanoTime()}")
        tempDir.mkdirs()
        snapshotFile = File(tempDir, "game_state_snapshot.json")
        manager = SnapshotManager(snapshotFile)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `save and load round-trip`() {
        val snapshot = testSnapshot()
        manager.saveSnapshot(snapshot)
        val loaded = manager.loadSnapshot()
        assertEquals(snapshot, loaded)
    }

    @Test
    fun `loadSnapshot returns null for missing file`() {
        assertNull(manager.loadSnapshot())
    }

    @Test
    fun `loadSnapshot returns null for corrupt file`() {
        snapshotFile.writeText("not valid json {{{")
        assertNull(manager.loadSnapshot())
    }

    @Test
    fun `clearSnapshot deletes file`() {
        manager.saveSnapshot(testSnapshot())
        assertTrue(snapshotFile.exists())
        manager.clearSnapshot()
        assertFalse(snapshotFile.exists())
    }

    @Test
    fun `save overwrites previous snapshot`() {
        val first = testSnapshot(timestamp = 1000L)
        val second = testSnapshot(timestamp = 2000L)
        manager.saveSnapshot(first)
        manager.saveSnapshot(second)
        val loaded = manager.loadSnapshot()
        assertEquals(2000L, loaded?.timestamp)
    }

    @Test
    fun `periodic snapshots saves at interval`() = runTest {
        var callCount = 0
        val snapshot = testSnapshot()

        manager.startPeriodicSnapshots(
            intervalMs = 1000L,
            scope = this
        ) {
            callCount++
            snapshot
        }

        advanceTimeBy(2500)
        assertEquals(2, callCount)
        assertNotNull(manager.loadSnapshot())

        manager.stopPeriodicSnapshots()
    }

    @Test
    fun `stopPeriodicSnapshots cancels loop`() = runTest {
        var callCount = 0

        manager.startPeriodicSnapshots(
            intervalMs = 500L,
            scope = this
        ) {
            callCount++
            testSnapshot()
        }

        advanceTimeBy(600)
        assertEquals(1, callCount)

        manager.stopPeriodicSnapshots()
        advanceTimeBy(1000)
        assertEquals(1, callCount)
    }

    @Test
    fun `periodic snapshots skips null from stateProvider`() = runTest {
        manager.startPeriodicSnapshots(
            intervalMs = 500L,
            scope = this
        ) {
            null
        }

        advanceTimeBy(1500)
        assertNull(manager.loadSnapshot())

        manager.stopPeriodicSnapshots()
    }

    @Test
    fun `snapshot interval constant is 10 seconds`() {
        assertEquals(10_000L, SnapshotManager.SNAPSHOT_INTERVAL_MS)
    }

    @Test
    fun `max snapshot age constant is 1 hour`() {
        assertEquals(3_600_000L, SnapshotManager.MAX_SNAPSHOT_AGE_MS)
    }
}
