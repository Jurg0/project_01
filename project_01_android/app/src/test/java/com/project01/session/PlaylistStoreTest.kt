package com.project01.session

import android.net.Uri
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PlaylistStoreTest {

    private lateinit var dir: File
    private lateinit var store: PlaylistStore

    private fun video(uri: String, title: String) = Video(Uri.parse(uri), title)

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "playlist_test_${System.nanoTime()}")
        store = PlaylistStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `save and load round-trip`() {
        val playlist = listOf(
            video("content://a.mp4", "Anomaly A"),
            video("content://b.mp4", "Anomaly B")
        )
        store.savePlaylist("woods", playlist)
        val loaded = store.loadPlaylist("woods")
        assertEquals(2, loaded?.size)
        assertEquals("Anomaly A", loaded?.get(0)?.title)
        assertEquals(Uri.parse("content://b.mp4"), loaded?.get(1)?.uri)
    }

    @Test
    fun `load returns null for missing playlist`() {
        assertNull(store.loadPlaylist("does-not-exist"))
    }

    @Test
    fun `listPlaylists returns saved names sorted, hiding underscore-prefixed slots`() {
        store.savePlaylist("zeta", listOf(video("content://z.mp4", "Z")))
        store.savePlaylist("alpha", listOf(video("content://a.mp4", "A")))
        store.savePlaylist(PlaylistStore.LAST_USED_NAME, listOf(video("content://x.mp4", "X")))

        val names = store.listPlaylists()
        assertEquals(listOf("alpha", "zeta"), names)
    }

    @Test
    fun `deletePlaylist removes the file`() {
        store.savePlaylist("temp", listOf(video("content://a.mp4", "A")))
        assertNotNull(store.loadPlaylist("temp"))
        store.deletePlaylist("temp")
        assertNull(store.loadPlaylist("temp"))
    }

    @Test
    fun `saving with a blank name is rejected`() {
        store.savePlaylist("", listOf(video("content://a.mp4", "A")))
        store.savePlaylist("   ", listOf(video("content://a.mp4", "A")))
        assertTrue(store.listPlaylists().isEmpty())
    }

    @Test
    fun `saving with path separator is rejected`() {
        store.savePlaylist("foo/bar", listOf(video("content://a.mp4", "A")))
        store.savePlaylist("foo\\bar", listOf(video("content://a.mp4", "A")))
        assertTrue(store.listPlaylists().isEmpty())
    }

    @Test
    fun `LAST_USED_NAME slot can be saved and loaded but is hidden from listing`() {
        val playlist = listOf(video("content://a.mp4", "A"))
        store.savePlaylist(PlaylistStore.LAST_USED_NAME, playlist)
        assertNotNull(store.loadPlaylist(PlaylistStore.LAST_USED_NAME))
        assertFalse(PlaylistStore.LAST_USED_NAME in store.listPlaylists())
    }
}
