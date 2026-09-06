package com.umbra.app.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.umbra.app.util.logging.LogScrubber
import com.umbra.app.util.logging.UmbraLog
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Result of a strip attempt. [stripped] is the safety contract: `false` means this file's
 * metadata could not be confirmed removed (unsupported/unreadable format, or a mid-strip
 * failure) — callers MUST refuse to upload [uri] in that case rather than fall back to the
 * original. Never treat a failed strip as "upload anyway."
 */
data class StrippingResult(
    val uri: Uri,
    val mimeType: String,
    val stripped: Boolean
)

/**
 * Media picked for upload (profile picture today; any future Blossom attachment) must never
 * carry more than what the user intended to share. EXIF (GPS, device make/model, serial
 * numbers, timestamps) and container-level metadata routinely leak all of that.
 *
 * Approach taken here, which solves the same problem for the same threat model:
 * - Images: clear a curated list of sensitive EXIF tags in place via `ExifInterface`, then
 *   `saveAttributes()`. This is lossless — unlike decoding to a `Bitmap` and re-encoding, it
 *   neither degrades JPEG quality nor collapses an animated GIF/WEBP to a single frame.
 * - GIF (`image/gif`): passed through completely unmodified. GIF89a has no EXIF/APPn-style
 *   metadata container at all — unlike JPEG/PNG/WEBP, there is nothing for `ExifInterface` to
 *   read or write, and its GIF support is read-only in practice, so routing GIFs through the
 *   same `setAttribute`/`saveAttributes()` path as other image types would either no-op or
 *   throw. Treated as "nothing to strip" rather than "unsupported, fail closed" — same
 *   conclusion as AVIF's inspect-only path below, reached for the opposite reason (AVIF *can*
 *   carry EXIF and needs inspecting; GIF structurally never can).
 * - AVIF: `ExifInterface` can read AVIF's HEIF/ISOBMFF container but cannot reliably rewrite
 *   it, so AVIF gets an inspect-only path — pass through unchanged only if verified clean,
 *   otherwise fail closed.
 * - Video: remux (via `MediaExtractor`/`MediaMuxer`) into a fresh MP4 container, copying only
 *   the codec sample data — this drops container-level metadata atoms without re-encoding
 *   (no quality loss). The rotation hint is read separately and reapplied, since it lives at
 *   the container level rather than in a track format.
 *
 * Any format this can't positively confirm as stripped — an unsupported image type
 * `ExifInterface` can't rewrite, a corrupt file, an extraction/mux failure — returns
 * `stripped = false`. Fail closed, always: never let an unverifiable file through unstripped.
 */
object MediaMetadataStripper {

    private const val TAG = "UmbraMediaStripper"
    private val logger = UmbraLog.tag(TAG)
    private const val AVIF_MIME = "image/avif"
    private const val GIF_MIME = "image/gif"
    private const val DEFAULT_REMUX_BUFFER_SIZE = 8 * 1024 * 1024

    private val SENSITIVE_EXIF_TAGS = arrayOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_IMAGE_UNIQUE_ID,
        ExifInterface.TAG_USER_COMMENT
    )

    /** Dispatches on [mimeType]; anything neither image nor video fails closed. */
    fun strip(uri: Uri, mimeType: String?, context: Context): StrippingResult = when {
        mimeType?.startsWith("image/", ignoreCase = true) == true -> stripImage(uri, mimeType, context)
        mimeType?.startsWith("video/", ignoreCase = true) == true -> stripVideo(uri, context)
        else -> StrippingResult(uri, mimeType.orEmpty(), stripped = false)
    }

    private fun stripImage(uri: Uri, mimeType: String, context: Context): StrippingResult {
        if (mimeType.equals(AVIF_MIME, ignoreCase = true)) {
            return inspectAvif(uri, mimeType, context)
        }
        if (mimeType.equals(GIF_MIME, ignoreCase = true)) {
            // See this file's class doc comment — GIF has no EXIF container to strip at all.
            return StrippingResult(uri, mimeType, stripped = true)
        }

        var tempFile: File? = null
        return try {
            val extension = when {
                mimeType.endsWith("jpeg", ignoreCase = true) || mimeType.endsWith("jpg", ignoreCase = true) -> ".jpg"
                mimeType.endsWith("png", ignoreCase = true) -> ".png"
                mimeType.endsWith("webp", ignoreCase = true) -> ".webp"
                else -> ".tmp"
            }
            tempFile = File.createTempFile("umbra_stripped_", extension, context.cacheDir)

            val input = context.contentResolver.openInputStream(uri) ?: run {
                tempFile?.delete()
                return StrippingResult(uri, mimeType, stripped = false)
            }
            input.use { source ->
                tempFile.outputStream().use { output -> source.copyTo(output) }
            }

            val exif = ExifInterface(tempFile.absolutePath)
            for (tag in SENSITIVE_EXIF_TAGS) {
                exif.setAttribute(tag, null)
            }
            exif.saveAttributes()

            logger.d { "Stripped EXIF metadata from image" }
            StrippingResult(tempFile.toUri(), mimeType, stripped = true)
        } catch (e: Exception) {
            tempFile?.delete()
            logger.d { "Failed to strip image metadata: ${LogScrubber.scrubThrowableMessageForLogs(e)}" }
            StrippingResult(uri, mimeType, stripped = false)
        }
    }

    /**
     * AVIF's HEIF/ISOBMFF container can't be reliably rewritten by `ExifInterface`, so this
     * verifies rather than strips: pass the original through only if no sensitive tag is
     * present, otherwise fail closed. Any parse failure also fails closed — an AVIF we can't
     * inspect is treated the same as one we know is dirty.
     */
    private fun inspectAvif(uri: Uri, mimeType: String, context: Context): StrippingResult {
        val getAttribute: (String) -> String? = try {
            context.contentResolver.openInputStream(uri)?.use { stream: InputStream ->
                val exif = ExifInterface(stream)
                exif::getAttribute
            } ?: return StrippingResult(uri, mimeType, stripped = false)
        } catch (e: Exception) {
            logger.d { "Could not parse AVIF for metadata inspection: ${LogScrubber.scrubThrowableMessageForLogs(e)}" }
            return StrippingResult(uri, mimeType, stripped = false)
        }

        val hasSensitiveTag = SENSITIVE_EXIF_TAGS.any { tag -> getAttribute(tag) != null }
        if (hasSensitiveTag) {
            logger.d { "AVIF contains sensitive EXIF tag(s); refusing upload" }
            return StrippingResult(uri, mimeType, stripped = false)
        }

        logger.d { "AVIF EXIF inspection: no sensitive tags found" }
        return StrippingResult(uri, mimeType, stripped = true)
    }

    /**
     * Remuxes into a fresh MP4 container — copies track samples as-is (no re-encode) into a
     * `MediaMuxer` that never received the source's metadata atoms. Note: `MediaMuxer` itself
     * may still write its own creation timestamp/encoder info into the new container; this is
     * not controllable via the public Android API and is a known residual limitation of the
     * remux approach in general.
     */
    fun stripVideo(uri: Uri, context: Context): StrippingResult {
        val outputMimeType = "video/mp4"
        return try {
            val tempOutputFile = File.createTempFile("umbra_stripped_video_", ".mp4", context.cacheDir)
            val succeeded = remuxTracks(uri, context, tempOutputFile) { muxer, _, ctx, sourceUri ->
                // Rotation is a container-level property, not part of any track format — read
                // it explicitly and reapply so playback orientation survives the remux.
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(ctx, sourceUri)
                    val rotation = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull() ?: 0
                    if (rotation != 0) muxer.setOrientationHint(rotation)
                } finally {
                    retriever.release()
                }
            }

            if (!succeeded) return StrippingResult(uri, outputMimeType, stripped = false)

            logger.d { "Stripped metadata from video" }
            StrippingResult(tempOutputFile.toUri(), outputMimeType, stripped = true)
        } catch (e: Exception) {
            logger.d { "Failed to strip video metadata: ${LogScrubber.scrubThrowableMessageForLogs(e)}" }
            StrippingResult(uri, outputMimeType, stripped = false)
        }
    }

    private fun extractorToCodecFlags(sampleFlags: Int): Int {
        var flags = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return flags
    }

    private fun remuxTracks(
        uri: Uri,
        context: Context,
        outputFile: File,
        preStart: (MediaMuxer, MediaExtractor, Context, Uri) -> Unit = { _, _, _, _ -> }
    ): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var succeeded = false
        try {
            extractor.setDataSource(context, uri, null)
            if (extractor.trackCount == 0) return false

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackIndexMap = mutableMapOf<Int, Int>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                trackIndexMap[i] = muxer.addTrack(format)
                extractor.selectTrack(i)
            }

            preStart(muxer, extractor, context, uri)

            muxer.start()
            muxerStarted = true

            var maxInputSize = DEFAULT_REMUX_BUFFER_SIZE
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInputSize = maxOf(maxInputSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }
            val buffer = ByteBuffer.allocateDirect(maxInputSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val outputTrack = trackIndexMap[extractor.sampleTrackIndex] ?: break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractorToCodecFlags(extractor.sampleFlags)

                muxer.writeSampleData(outputTrack, buffer, bufferInfo)
                extractor.advance()
            }

            muxer.stop()
            muxerStarted = false
            succeeded = true
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            muxer?.release()
            extractor.release()
            if (!succeeded) outputFile.delete()
        }
        return succeeded
    }
}
