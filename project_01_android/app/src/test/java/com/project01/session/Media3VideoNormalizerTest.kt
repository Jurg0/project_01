package com.project01.session

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Covers the bookkeeping around the conversion — reuse, cleanup, and the check on what the
 * encoder actually produced — with the media3 export replaced by a fake. Robolectric only for
 * `Uri`; no decoder, no encoder, no real video.
 */
@RunWith(RobolectricTestRunner::class)
class Media3VideoNormalizerTest {

    private lateinit var outputDir: File
    private val source = Uri.parse("content://picked/clip.mp4")

    @Before
    fun setup() {
        outputDir = File(System.getProperty("java.io.tmpdir"), "normalizer-test-${System.nanoTime()}")
        outputDir.mkdirs()
    }

    @After
    fun tearDown() {
        outputDir.deleteRecursively()
    }

    private fun normalizer(
        probe: (Uri) -> VideoProbe?,
        export: suspend (Uri, File, Int, (Int) -> Unit) -> Result<VideoProbe>,
    ) = Media3VideoNormalizer(
        context = ApplicationProvider.getApplicationContext(),
        outputDir = outputDir,
        probeVideo = probe,
        exportVideo = export,
    )

    /** Stands in for a real export: writes some bytes and reports the given output size. */
    private fun fakeExport(
        producing: VideoProbe,
        bytes: Int = 1024,
        record: MutableList<Int>? = null,
    ): suspend (Uri, File, Int, (Int) -> Unit) -> Result<VideoProbe> = { _, output, height, _ ->
        record?.add(height)
        output.writeBytes(ByteArray(bytes))
        Result.success(producing)
    }

    @Test
    fun `an 8K portrait video is converted and the requested height is the long side`() = runTest {
        val requestedHeights = mutableListOf<Int>()
        val normalizer = normalizer(
            probe = { VideoProbe(7680, 4320, rotation = 90) },
            export = fakeExport(VideoProbe(1920, 1080), record = requestedHeights),
        )

        val result = normalizer.normalize(source, "clip.mp4")

        assertTrue(result is NormalizeResult.Converted)
        val converted = result as NormalizeResult.Converted
        assertEquals("clip_1080p.mp4", converted.video.title)
        assertEquals(listOf(1920), requestedHeights)
        assertTrue(File(outputDir, "clip_1080p.mp4").exists())
        assertEquals(1024L, converted.video.sizeBytes)
        assertFalse(converted.reused)
    }

    @Test
    fun `a 1080p video is left alone and never exported`() = runTest {
        var exported = false
        val normalizer = normalizer(
            probe = { VideoProbe(1920, 1080, rotation = 90) },
            export = { _, _, _, _ -> exported = true; Result.success(VideoProbe(1920, 1080)) },
        )

        val result = normalizer.normalize(source, "clip.mp4")

        assertEquals(NormalizeResult.Unchanged(1920, 1080), result)
        assertFalse("a video within limits must not be re-encoded", exported)
        assertFalse(File(outputDir, "clip_1080p.mp4").exists())
    }

    @Test
    fun `an already converted copy is reused instead of re-encoded`() = runTest {
        // Re-encoding a 400MB video every time the game master reopens a playlist would be
        // minutes of waiting for nothing.
        File(outputDir, "clip_1080p.mp4").writeBytes(ByteArray(2048))
        var exported = false
        val normalizer = normalizer(
            probe = { uri ->
                if (uri == source) VideoProbe(7680, 4320, rotation = 90) else VideoProbe(1920, 1080)
            },
            export = { _, _, _, _ -> exported = true; Result.success(VideoProbe(1920, 1080)) },
        )

        val result = normalizer.normalize(source, "clip.mp4")

        assertTrue(result is NormalizeResult.Converted)
        assertTrue((result as NormalizeResult.Converted).reused)
        assertFalse(exported)
        assertEquals(2048L, result.video.sizeBytes)
    }

    @Test
    fun `a failed export leaves no part file behind`() = runTest {
        // The lesson from FileTransfer: a stub left on disk is later mistaken for a finished
        // file and the video is broken forever on that device.
        val normalizer = normalizer(
            probe = { VideoProbe(7680, 4320) },
            export = { _, output, _, _ ->
                output.writeBytes(ByteArray(512))          // partial output, as a real crash leaves
                Result.failure(IllegalStateException("no encoder"))
            },
        )

        val result = normalizer.normalize(source, "clip.mp4")

        assertTrue(result is NormalizeResult.Failed)
        assertEquals("no encoder", (result as NormalizeResult.Failed).reason)
        assertEquals(7680, result.width)
        assertTrue("no leftovers", outputDir.list()!!.isEmpty())
    }

    @Test
    fun `a conversion that is still too large is rejected, not accepted`() = runTest {
        // The encoder may fall back to a resolution we did not ask for. Accepting it would put
        // a file in the playlist that fails on exactly the phones this exists to protect.
        val normalizer = normalizer(
            probe = { VideoProbe(7680, 4320) },
            export = fakeExport(VideoProbe(3840, 2160)),
        )

        val result = normalizer.normalize(source, "clip.mp4")

        assertTrue(result is NormalizeResult.Failed)
        assertTrue((result as NormalizeResult.Failed).reason.contains("3840x2160"))
        assertTrue("no leftovers", outputDir.list()!!.isEmpty())
    }

    @Test
    fun `a video whose size cannot be read is reported, not silently converted`() = runTest {
        val normalizer = normalizer(
            probe = { null },
            export = fakeExport(VideoProbe(1920, 1080)),
        )

        val result = normalizer.normalize(source, "clip.mp4")

        assertTrue(result is NormalizeResult.Failed)
        assertTrue(outputDir.list()!!.isEmpty())
    }

    @Test
    fun `progress is reported so the game master sees something happening`() = runTest {
        val seen = mutableListOf<Int>()
        val normalizer = normalizer(
            probe = { VideoProbe(7680, 4320) },
            export = { _, output, _, onProgress ->
                onProgress(0)
                onProgress(50)
                output.writeBytes(ByteArray(16))
                Result.success(VideoProbe(1920, 1080))
            },
        )

        normalizer.normalize(source, "clip.mp4") { seen.add(it) }

        assertEquals(listOf(0, 50), seen)
    }

    @Test
    fun `conversions run one at a time`() = runTest {
        // Adding several videos in a row must not start several hardware encodes at once: they
        // fight over the same encoder and finish later than they would in turn.
        var running = 0
        var mostAtOnce = 0
        val normalizer = normalizer(
            probe = { VideoProbe(7680, 4320) },
            export = { _, output, _, _ ->
                running++
                mostAtOnce = maxOf(mostAtOnce, running)
                kotlinx.coroutines.delay(100)
                output.writeBytes(ByteArray(8))
                running--
                Result.success(VideoProbe(1920, 1080))
            },
        )

        val first = backgroundScope.launch { normalizer.normalize(Uri.parse("content://a"), "a.mp4") }
        val second = backgroundScope.launch { normalizer.normalize(Uri.parse("content://b"), "b.mp4") }
        first.join()
        second.join()

        assertEquals(1, mostAtOnce)
        assertTrue(File(outputDir, "a_1080p.mp4").exists())
        assertTrue(File(outputDir, "b_1080p.mp4").exists())
    }

    @Test
    fun `a resolution is read once and then cached`() = runTest {
        // The diagnostics screen refreshes every 2s; re-reading every file's metadata each time
        // would make it a burden on the phone it exists to help.
        var probes = 0
        val normalizer = normalizer(
            probe = { probes++; VideoProbe(1920, 1080) },
            export = fakeExport(VideoProbe(1920, 1080)),
        )

        normalizer.resolutionOf(source)
        normalizer.resolutionOf(source)
        normalizer.normalize(source, "clip.mp4")

        assertEquals(1, probes)
    }

    @Test
    fun `a leftover part file from a killed conversion is not mistaken for a result`() = runTest {
        File(outputDir, "clip_1080p.mp4${Media3VideoNormalizer.PART_SUFFIX}")
            .writeBytes(ByteArray(999))
        val normalizer = normalizer(
            probe = { VideoProbe(7680, 4320) },
            export = fakeExport(VideoProbe(1920, 1080), bytes = 64),
        )

        val result = normalizer.normalize(source, "clip.mp4")

        assertTrue(result is NormalizeResult.Converted)
        assertEquals(64L, (result as NormalizeResult.Converted).video.sizeBytes)
        assertFalse(
            File(outputDir, "clip_1080p.mp4${Media3VideoNormalizer.PART_SUFFIX}").exists()
        )
    }
}
