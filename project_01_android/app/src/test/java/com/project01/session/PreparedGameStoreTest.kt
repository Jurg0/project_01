package com.project01.session

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Pure-JVM (no Robolectric): PreparedGame uses VideoDto (plain strings) and
 * MessageEnvelope.json, none of which touch Android.
 */
class PreparedGameStoreTest {

    private lateinit var dir: File
    private lateinit var store: PreparedGameStore

    private fun dto(uri: String, title: String) = VideoDto(uri, title)

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "prepared_test_${System.nanoTime()}")
        store = PreparedGameStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `save and load round-trip preserves password and videos`() {
        val game = PreparedGame(
            name = "woods",
            password = "raven",
            videos = listOf(dto("content://a.mp4", "Anomaly A"), dto("content://b.mp4", "Anomaly B")),
        )
        store.save(game)

        val loaded = store.load("woods")
        assertEquals("raven", loaded?.password)
        assertEquals(2, loaded?.videos?.size)
        assertEquals("Anomaly B", loaded?.videos?.get(1)?.title)
        assertEquals("content://a.mp4", loaded?.videos?.get(0)?.uriString)
    }

    @Test
    fun `load returns null for missing game`() {
        assertNull(store.load("nope"))
    }

    @Test
    fun `findByPassword returns the matching game`() {
        store.save(PreparedGame("woods", "raven", listOf(dto("content://a.mp4", "A"))))
        store.save(PreparedGame("cave", "owl", listOf(dto("content://b.mp4", "B"))))

        assertEquals("woods", store.findByPassword("raven")?.name)
        assertEquals("cave", store.findByPassword("owl")?.name)
    }

    @Test
    fun `findByPassword returns null when no game matches`() {
        store.save(PreparedGame("woods", "raven", emptyList()))
        assertNull(store.findByPassword("wrong"))
    }

    @Test
    fun `findByPassword resolves duplicate passwords to the alphabetically-first game`() {
        store.save(PreparedGame("zeta", "shared", listOf(dto("content://z.mp4", "Z"))))
        store.save(PreparedGame("alpha", "shared", listOf(dto("content://a.mp4", "A"))))

        assertEquals("alpha", store.findByPassword("shared")?.name)
    }

    @Test
    fun `list is sorted by name and listNames matches`() {
        store.save(PreparedGame("zeta", "p1", emptyList()))
        store.save(PreparedGame("alpha", "p2", emptyList()))

        assertEquals(listOf("alpha", "zeta"), store.list().map { it.name })
        assertEquals(listOf("alpha", "zeta"), store.listNames())
    }

    @Test
    fun `save rejects blank and path-separator names`() {
        store.save(PreparedGame("", "p", emptyList()))
        store.save(PreparedGame("a/b", "p", emptyList()))
        store.save(PreparedGame("a\\b", "p", emptyList()))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `delete removes the game`() {
        store.save(PreparedGame("woods", "raven", emptyList()))
        assertNotNull(store.load("woods"))
        store.delete("woods")
        assertNull(store.load("woods"))
    }

    @Test
    fun `corrupt or foreign json is skipped, not fatal`() {
        store.save(PreparedGame("good", "raven", listOf(dto("content://a.mp4", "A"))))
        dir.mkdirs()
        // Garbage, and a legacy bare-array playlist file (no password field) — neither is a PreparedGame.
        File(dir, "corrupt.json").writeText("{ this is not json")
        File(dir, "legacy.json").writeText("""[{"uriString":"content://x.mp4","title":"X"}]""")

        assertNull(store.load("corrupt"))
        assertNull(store.load("legacy"))
        // list() drops the bad files and keeps the valid one.
        assertEquals(listOf("good"), store.list().map { it.name })
    }
}
