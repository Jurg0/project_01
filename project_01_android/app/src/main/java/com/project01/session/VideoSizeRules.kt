package com.project01.session

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What resolution the fleet can actually play, and how far a video has to shrink to get there.
 *
 * Pure arithmetic, no Android types, so the decision that gates every conversion is testable.
 *
 * **Why 1920x1080 is the ceiling.** The game master's phone records 8K (7680x4320). The older
 * player phones have no 8K decoder at all: an S9 and an A20e both failed at
 * `MediaCodec.configure` on such a file while playing a 1080p recording from the same phone
 * without trouble, so the video appeared on the game master's screen and nowhere else —
 * indistinguishable, in the field, from a broken playback sync (field-diagnosed 2026-08-17).
 * 1080p is also all a phone screen can show, and it cuts the file roughly tenfold, which
 * matters more than anything else here: transfers run at ~1.9 MB/s over the hotspot.
 */
object VideoSizeRules {

    /** The most pixels we ask any player phone to decode, expressed as a box. */
    const val MAX_SHORT_SIDE = 1080
    const val MAX_LONG_SIDE = 1920

    /** True when [width]x[height] does not fit the box above in either orientation. */
    fun needsDownscale(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false      // unknown size: don't touch it
        return max(width, height) > MAX_LONG_SIDE || min(width, height) > MAX_SHORT_SIDE
    }

    /**
     * The height to ask media3's `Presentation.createForHeight` for.
     *
     * That height is the **displayed** height — the height after the recording's rotation is
     * applied — and the rotation metadata survives the conversion. Verified on an A20e: a
     * portrait recording stored as 1920x1080 with rotation 90 (so displayed 1080x1920), asked
     * for height 540, came out displayed 304x540 (stored 540x304, rotation still 90). So a
     * portrait video must ask for the long side and a landscape one for the short side, which
     * is why this takes [rotationDegrees] rather than working off the stored height alone.
     *
     * The scale factor is computed from the short and long sides, so both end up inside the
     * box whichever way round the video is. Always even — encoders reject odd dimensions.
     */
    fun targetHeight(storedWidth: Int, storedHeight: Int, rotationDegrees: Int): Int {
        val displayedHeight = if (isQuarterTurned(rotationDegrees)) storedWidth else storedHeight
        val shortSide = min(storedWidth, storedHeight)
        val longSide = max(storedWidth, storedHeight)
        val scale = min(
            MAX_SHORT_SIDE.toDouble() / shortSide,
            MAX_LONG_SIDE.toDouble() / longSide,
        )
        return makeEven((displayedHeight * scale).roundToInt())
    }

    /** True when the recording is stored sideways, so displayed width and height are swapped. */
    fun isQuarterTurned(rotationDegrees: Int): Boolean =
        ((rotationDegrees % 180) + 180) % 180 == 90

    /** The title a downscaled copy gets. Stable, so re-adding the same file reuses the copy. */
    fun downscaledTitle(title: String): String {
        val dot = title.lastIndexOf('.')
        return if (dot > 0) {
            "${title.substring(0, dot)}$DOWNSCALED_SUFFIX${title.substring(dot)}"
        } else {
            "$title$DOWNSCALED_SUFFIX.mp4"
        }
    }

    private fun makeEven(value: Int): Int = max(2, value - (value % 2))

    const val DOWNSCALED_SUFFIX = "_1080p"
}
