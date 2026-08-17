package com.project01.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM, no Robolectric. These rules decide whether a video is converted at all and what
 * it becomes, so they are the part worth pinning down: the field failure they exist to prevent
 * (an 8K recording that plays on the game master's phone and on nothing else) is invisible
 * until you are standing in a forest with twenty phones.
 */
class VideoSizeRulesTest {

    // --- needsDownscale ---

    @Test
    fun `8K needs downscaling`() {
        assertTrue(VideoSizeRules.needsDownscale(7680, 4320))
    }

    @Test
    fun `4K needs downscaling`() {
        assertTrue(VideoSizeRules.needsDownscale(3840, 2160))
    }

    @Test
    fun `1080p landscape is left alone`() {
        assertFalse(VideoSizeRules.needsDownscale(1920, 1080))
    }

    @Test
    fun `1080p stored portrait is left alone`() {
        assertFalse(VideoSizeRules.needsDownscale(1080, 1920))
    }

    @Test
    fun `720p is left alone`() {
        assertFalse(VideoSizeRules.needsDownscale(1280, 720))
    }

    @Test
    fun `a video that is short enough but too wide still needs downscaling`() {
        // 2560x1080 ultra-wide: the short side fits, the long side does not.
        assertTrue(VideoSizeRules.needsDownscale(2560, 1080))
    }

    @Test
    fun `a video that is narrow enough but too tall still needs downscaling`() {
        // Square-ish 1200x1200: the long side fits, the short side does not.
        assertTrue(VideoSizeRules.needsDownscale(1200, 1200))
    }

    @Test
    fun `an unreadable size is never converted`() {
        // Better to hand the game master the original than to guess at a conversion.
        assertFalse(VideoSizeRules.needsDownscale(0, 0))
        assertFalse(VideoSizeRules.needsDownscale(-1, 1080))
    }

    // --- targetHeight ---
    // The height asked of Presentation is the DISPLAYED height, verified on an A20e.

    @Test
    fun `8K recorded in portrait asks for the long side`() {
        // Stored 7680x4320 with rotation 90 displays as 4320x7680, so the displayed height is
        // the long side and must land on 1920 — giving displayed 1080x1920, exactly the shape
        // of the 1080p recording that plays on every phone in the fleet.
        assertEquals(1920, VideoSizeRules.targetHeight(7680, 4320, 90))
    }

    @Test
    fun `8K recorded in landscape asks for the short side`() {
        assertEquals(1080, VideoSizeRules.targetHeight(7680, 4320, 0))
    }

    @Test
    fun `270 degrees counts as quarter-turned too`() {
        assertEquals(1920, VideoSizeRules.targetHeight(7680, 4320, 270))
    }

    @Test
    fun `180 degrees is not quarter-turned`() {
        assertEquals(1080, VideoSizeRules.targetHeight(7680, 4320, 180))
    }

    @Test
    fun `4K portrait scales by half`() {
        assertEquals(1920, VideoSizeRules.targetHeight(3840, 2160, 90))
    }

    @Test
    fun `an ultra-wide video is capped by its long side, not its short one`() {
        // 5120x2160 landscape: scaling to short side 1080 would leave the width at 2560, over
        // the limit. The long side has to be what binds, giving 1920x810.
        assertEquals(810, VideoSizeRules.targetHeight(5120, 2160, 0))
    }

    @Test
    fun `target height is always even`() {
        // Encoders reject odd dimensions. 7680x4321 rotated is a contrived case; the point is
        // that no arithmetic here can produce one.
        for (rotation in listOf(0, 90, 180, 270)) {
            val height = VideoSizeRules.targetHeight(7681, 4321, rotation)
            assertEquals("rotation $rotation produced $height", 0, height % 2)
        }
    }

    @Test
    fun `every result fits the fleet's box`() {
        val sizes = listOf(
            7680 to 4320, 3840 to 2160, 5120 to 2160, 2560 to 1080, 1200 to 1200, 4000 to 3000,
        )
        for ((width, height) in sizes) {
            for (rotation in listOf(0, 90)) {
                val target = VideoSizeRules.targetHeight(width, height, rotation)
                val displayedWidth = if (VideoSizeRules.isQuarterTurned(rotation)) height else width
                val displayedHeight = if (VideoSizeRules.isQuarterTurned(rotation)) width else height
                // Presentation preserves the aspect ratio, so the other side follows the height.
                val resultingWidth = (target.toDouble() * displayedWidth / displayedHeight)
                assertTrue(
                    "${width}x$height rot $rotation -> ${resultingWidth.toInt()}x$target",
                    maxOf(resultingWidth, target.toDouble()) <= VideoSizeRules.MAX_LONG_SIDE + 1 &&
                        minOf(resultingWidth, target.toDouble()) <= VideoSizeRules.MAX_SHORT_SIDE + 1
                )
            }
        }
    }

    // --- downscaledTitle ---

    @Test
    fun `downscaled title keeps the extension`() {
        assertEquals("20260814_210013_1080p.mp4", VideoSizeRules.downscaledTitle("20260814_210013.mp4"))
    }

    @Test
    fun `downscaled title of an extensionless name gets mp4`() {
        assertEquals("clip_1080p.mp4", VideoSizeRules.downscaledTitle("clip"))
    }

    @Test
    fun `downscaled title is stable so a re-added file reuses the copy`() {
        val once = VideoSizeRules.downscaledTitle("anomaly.mp4")
        assertEquals(once, VideoSizeRules.downscaledTitle("anomaly.mp4"))
    }
}
