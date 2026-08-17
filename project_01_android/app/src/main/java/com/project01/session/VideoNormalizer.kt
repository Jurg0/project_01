package com.project01.session

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A video's stored dimensions and its rotation flag, as the container reports them. */
data class VideoProbe(val width: Int, val height: Int, val rotation: Int = 0)

/** What happened to one video on its way into a playlist. */
sealed class NormalizeResult {
    /** Already inside the fleet's limits — the original goes into the playlist untouched. */
    data class Unchanged(val width: Int, val height: Int) : NormalizeResult()

    /** A downscaled copy was written to app storage; [video] points at it. */
    data class Converted(
        val video: Video,
        val fromWidth: Int,
        val fromHeight: Int,
        val toWidth: Int,
        val toHeight: Int,
        val reused: Boolean,
    ) : NormalizeResult()

    /**
     * Could not be shrunk. The caller keeps the original and tells the game master, who is the
     * only one who knows whether the fleet can cope: refusing the video outright would be
     * worse than a warning, since a fleet of new phones plays 8K fine.
     */
    data class Failed(val reason: String, val width: Int, val height: Int) : NormalizeResult()
}

/**
 * Makes a picked video playable on every phone in the fleet before it enters a playlist.
 *
 * This runs when the game master **builds** a playlist, never at session or transfer time: by
 * the time a game starts, every entry is already something the players can decode, and the
 * conversion cost is paid at home rather than in the woods.
 */
interface VideoNormalizer {
    /**
     * Examine [uri] and, if it is too big for the fleet, write a downscaled copy.
     *
     * [onProgress] is called with 0 as soon as a conversion actually starts, then with the
     * encoder's percentage as it runs. It is never called for a video that needs no work, so a
     * caller can treat the first callback as "this is going to take a while".
     */
    suspend fun normalize(uri: Uri, title: String, onProgress: (Int) -> Unit = {}): NormalizeResult

    /**
     * The stored resolution and rotation of [uri], or null if it can't be read. Cached, so the
     * diagnostics screen can refresh every 2s without re-reading every file.
     *
     * This exists because the game master's phone has no developer mode: without adb, the
     * on-device diagnostics screen is the only place a video too large for the player phones
     * can be spotted before the game starts.
     */
    suspend fun resolutionOf(uri: Uri): VideoProbe?
}

/**
 * media3 Transformer implementation. Decodes with the phone's hardware, scales to fit
 * [VideoSizeRules]'s box and re-encodes as H.264 — the one video format every Android phone in
 * this fleet's age range decodes.
 *
 * The output lands in a `.part` file and is renamed only once the export has finished AND the
 * result has been measured to be within the limits, so a file present in [outputDir] is always
 * a complete, verified, playable copy. Same reasoning as [FileTransfer]: a killed conversion
 * must not leave a stub that a later run mistakes for a finished one.
 *
 * [probeVideo] and [exportVideo] are injectable so every branch below — reuse, failure
 * cleanup, a conversion that came out too big anyway — can be tested without a real decoder.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class Media3VideoNormalizer(
    private val context: Context,
    private val outputDir: File = context.filesDir,
    private val probeVideo: (Uri) -> VideoProbe? = { uri -> retrieverProbe(context, uri) },
    private val exportVideo: suspend (Uri, File, Int, (Int) -> Unit) -> Result<VideoProbe> =
        { uri, output, height, onProgress -> media3Export(context, uri, output, height, onProgress) },
) : VideoNormalizer {

    /**
     * One conversion at a time. Adding three videos in a row starts three coroutines, and three
     * concurrent hardware encodes would fight over the same encoder and take longer than doing
     * them in turn — the same reason file transfers run sequentially per device.
     */
    private val oneAtATime = Mutex()

    /** Resolutions never change for a given URI, so they are read once. */
    private val probeCache = ConcurrentHashMap<String, VideoProbe>()

    override suspend fun resolutionOf(uri: Uri): VideoProbe? = cachedProbe(uri)

    private fun cachedProbe(uri: Uri): VideoProbe? {
        probeCache[uri.toString()]?.let { return it }
        return probeVideo(uri)?.also { probeCache[uri.toString()] = it }
    }

    override suspend fun normalize(
        uri: Uri,
        title: String,
        onProgress: (Int) -> Unit,
    ): NormalizeResult = oneAtATime.withLock {
        val source = cachedProbe(uri)
            ?: return NormalizeResult.Failed("could not read the video's resolution", 0, 0)
        if (!VideoSizeRules.needsDownscale(source.width, source.height)) {
            Log.d(TAG, "$title is ${source.width}x${source.height} — no conversion needed")
            return NormalizeResult.Unchanged(source.width, source.height)
        }

        val outputTitle = VideoSizeRules.downscaledTitle(title)
        val output = File(outputDir, outputTitle)
        if (output.exists() && output.length() > 0) {
            // Converted on an earlier run. A file here is always complete (see .part below), so
            // re-adding the same video costs nothing instead of re-encoding hundreds of MB.
            Log.d(TAG, "$outputTitle already converted, reusing ${output.length()}B")
            val cached = cachedProbe(Uri.fromFile(output))
            return NormalizeResult.Converted(
                video = Video(Uri.fromFile(output), outputTitle, output.length()),
                fromWidth = source.width,
                fromHeight = source.height,
                toWidth = cached?.width ?: 0,
                toHeight = cached?.height ?: 0,
                reused = true,
            )
        }

        val targetHeight = VideoSizeRules.targetHeight(source.width, source.height, source.rotation)
        val part = File(outputDir, "$outputTitle$PART_SUFFIX").also { it.delete() }
        Log.d(TAG, "$title: ${source.width}x${source.height} rotation=${source.rotation} " +
            "— converting to displayed height $targetHeight")

        val export = exportVideo(uri, part, targetHeight, onProgress)
        val failure = export.exceptionOrNull()
        if (failure != null) {
            part.delete()
            Log.w(TAG, "$title: conversion failed", failure)
            return NormalizeResult.Failed(
                failure.message ?: failure.javaClass.simpleName,
                source.width,
                source.height,
            )
        }

        val result = export.getOrThrow()
        // Measure what actually came out. The encoder is allowed to fall back to a resolution
        // other than the one requested, and a copy that is still too large would fail on the
        // same phones as the original — silently, which is the whole failure being fixed here.
        if (VideoSizeRules.needsDownscale(result.width, result.height)) {
            part.delete()
            val reason = "conversion produced ${result.width}x${result.height}, still too large"
            Log.w(TAG, "$title: $reason")
            return NormalizeResult.Failed(reason, source.width, source.height)
        }
        if (!part.renameTo(output)) {
            part.delete()
            return NormalizeResult.Failed(
                "could not move the converted file into place", source.width, source.height
            )
        }
        Log.d(TAG, "$title: converted to ${result.width}x${result.height}, " +
            "${output.length() / 1024 / 1024}MB (was ${source.width}x${source.height})")
        return NormalizeResult.Converted(
            video = Video(Uri.fromFile(output), outputTitle, output.length()),
            fromWidth = source.width,
            fromHeight = source.height,
            toWidth = result.width,
            toHeight = result.height,
            reused = false,
        )
    }

    companion object {
        private const val TAG = "VideoPrep"
        const val PART_SUFFIX = ".part"
        private const val PROGRESS_POLL_MS = 500L
        /** ~10 Mbps: visibly clean at 1080p, and ~25MB for a 20s clip instead of 207MB. */
        private const val TARGET_BITRATE = 10_000_000

        private fun retrieverProbe(context: Context, uri: Uri): VideoProbe? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, uri)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                if (width == null || height == null) null
                else VideoProbe(width.toInt(), height.toInt(), rotation?.toIntOrNull() ?: 0)
            } catch (e: Exception) {
                Log.w(TAG, "could not read metadata for $uri", e)
                null
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    // release() throws on some devices when setDataSource failed; nothing to do.
                }
            }
        }

        /**
         * Run one export to completion and report the stored size of what came out.
         *
         * Transformer is not thread-safe and must be driven from a Looper thread, so everything
         * happens on the main thread; the encoding itself runs on media3's own threads, so this
         * does not block the UI.
         */
        private suspend fun media3Export(
            context: Context,
            uri: Uri,
            output: File,
            targetHeight: Int,
            onProgress: (Int) -> Unit,
        ): Result<VideoProbe> = withContext(Dispatchers.Main) {
            val finished = CompletableDeferred<Result<VideoProbe>>()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder().setBitrate(TARGET_BITRATE).build()
                        )
                        // Let media3 pick something the phone supports if the request is
                        // refused; what comes out is measured by the caller either way.
                        .setEnableFallback(true)
                        .build()
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finished.complete(
                            Result.success(VideoProbe(exportResult.width, exportResult.height))
                        )
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        finished.complete(Result.failure(exportException))
                    }
                })
                .build()

            // Presentation's height is the DISPLAYED height — see VideoSizeRules.targetHeight.
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(targetHeight))))
                .build()

            onProgress(0)
            transformer.start(edited, output.absolutePath)
            val poller = launch {
                val holder = ProgressHolder()
                while (isActive) {
                    delay(PROGRESS_POLL_MS)
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress)
                    }
                }
            }
            try {
                finished.await()
            } finally {
                poller.cancel()
                // Covers the cancelled-coroutine path too (the game master left the screen):
                // without this the export would keep encoding in the background forever.
                if (!finished.isCompleted) transformer.cancel()
            }
        }
    }
}
