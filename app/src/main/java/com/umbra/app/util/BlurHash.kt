package com.umbra.app.util

import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.scale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.withSign

/**
 * Encodes/decodes blurhash strings (https://blurha.sh, referenced by NIP-92/94's `blurhash`
 * field). [encode] runs when Umbra uploads its own media, attaching a low-frequency placeholder
 * to the outgoing `imeta` tag; [decode] renders one into a small placeholder [Bitmap] while the
 * real image loads for content authored by anyone else.
 *
 * Collapsed into one file since Umbra only needs the Android encode/decode path, not a
 * multiplatform split. [encode]'s max-AC-magnitude scan intentionally doesn't take `abs()` (see
 * [maxAcMagnitude]), matching a known-working production encoder exactly rather than the more
 * common abs()-based reference implementation — still produces a fully spec-valid, decodable
 * blurhash.
 */
object BlurHash {

    private val ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~".toCharArray()
    private val CHAR_MAP: IntArray = IntArray(128).also { map ->
        ALPHABET.forEachIndexed { index, c -> map[c.code] = index }
    }

    // Caches the expensive cos() arrays, keyed by (dimension * componentCount) — shared by
    // encode and decode, both index it identically (see cosinesFor's doc comment).
    private val cosinesXCache = LruCache<Int, DoubleArray>(20)
    private val cosinesYCache = LruCache<Int, DoubleArray>(20)

    // ---------------------------------------------------------------------------------------
    // Decode
    // ---------------------------------------------------------------------------------------

    /**
     * Decodes [blurHash] into a [width]-wide bitmap. Height is derived from [aspectRatio]
     * (width/height, typically from the matching `imeta` `dim` field) when known, otherwise
     * falls back to the aspect ratio implied by the hash's own component counts. Never throws —
     * any malformed input (wrong length, bad characters) returns null.
     */
    fun decode(blurHash: String?, width: Int, aspectRatio: Float? = null): Bitmap? {
        if (blurHash.isNullOrEmpty() || blurHash.length < 6 || width <= 0) return null

        return runCatching {
            val numCompEnc = decodeAt(blurHash, 0)
            val numCompX = (numCompEnc % 9) + 1
            val numCompY = (numCompEnc / 9) + 1
            if (blurHash.length != 4 + 2 * numCompX * numCompY) return null

            val ratio = aspectRatio?.takeIf { it > 0f } ?: (numCompX.toFloat() / numCompY.toFloat())
            val height = (width / ratio).roundToInt().coerceAtLeast(1)

            val colors = computeColors(numCompX, numCompY, blurHash)
            val pixels = composePixels(width, height, numCompX, numCompY, colors)
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }

    private fun computeColors(numCompX: Int, numCompY: Int, blurHash: String): Array<FloatArray> {
        val maxAc = (decodeAt(blurHash, 1) + 1) / 166f
        return Array(numCompX * numCompY) { i ->
            if (i == 0) {
                decodeDc(decode(blurHash, 2, 6))
            } else {
                decodeAc(decodeFixed2(blurHash, 4 + i * 2), maxAc)
            }
        }
    }

    private fun decodeDc(colorEnc: Int): FloatArray {
        val r = colorEnc shr 16
        val g = (colorEnc shr 8) and 255
        val b = colorEnc and 255
        return floatArrayOf(srgbToLinear(r), srgbToLinear(g), srgbToLinear(b))
    }

    private fun decodeAc(value: Int, maxAc: Float): FloatArray {
        val r = value / (19 * 19)
        val g = (value / 19) % 19
        val b = value % 19
        return floatArrayOf(
            signedPow2((r - 9) / 9f) * maxAc,
            signedPow2((g - 9) / 9f) * maxAc,
            signedPow2((b - 9) / 9f) * maxAc
        )
    }

    private fun signedPow2(value: Float) = value.pow(2f).withSign(value)

    private fun composePixels(
        width: Int,
        height: Int,
        numCompX: Int,
        numCompY: Int,
        colors: Array<FloatArray>
    ): IntArray {
        val pixels = IntArray(width * height)
        val cosinesX = cosinesFor(cosinesXCache, width, numCompX)
        val cosinesY = cosinesFor(cosinesYCache, height, numCompY)

        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (j in 0 until numCompY) {
                    for (i in 0 until numCompX) {
                        val basis = (cosinesX[i + numCompX * x] * cosinesY[j + numCompY * y]).toFloat()
                        val color = colors[j * numCompX + i]
                        r += color[0] * basis
                        g += color[1] * basis
                        b += color[2] * basis
                    }
                }
                pixels[x + width * y] = rgb(linearToSrgb(r), linearToSrgb(g), linearToSrgb(b))
            }
        }
        return pixels
    }

    /** Shared by encode and decode: `result[pos * numComp + comp] = cos(PI * pos * comp / size)`. */
    private fun cosinesFor(cache: LruCache<Int, DoubleArray>, size: Int, numComp: Int): DoubleArray {
        val key = size * numComp
        cache.get(key)?.let { return it }
        val computed = DoubleArray(size * numComp) {
            val pos = it / numComp
            val comp = it % numComp
            cos(PI * pos * comp / size)
        }
        cache.put(key, computed)
        return computed
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int = -0x1000000 or (red shl 16) or (green shl 8) or blue

    private fun linearToSrgb(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) {
            (v * 12.92f * 255f + 0.5f).toInt()
        } else {
            ((1.055f * v.pow(1 / 2.4f) - 0.055f) * 255 + 0.5f).toInt()
        }
    }

    private fun srgbToLinear(value: Int): Float {
        val v = value.coerceIn(0, 255) / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun decodeAt(str: String, at: Int): Int = CHAR_MAP[str[at].code]

    private fun decodeFixed2(str: String, from: Int): Int =
        CHAR_MAP[str[from].code] * 83 + CHAR_MAP[str[from + 1].code]

    private fun decode(str: String, from: Int, to: Int): Int {
        var result = 0
        for (i in from until to) result = result * 83 + CHAR_MAP[str[i].code]
        return result
    }

    // ---------------------------------------------------------------------------------------
    // Encode
    // ---------------------------------------------------------------------------------------

    // Blurhash is a low-frequency summary — encoding at full resolution wastes CPU for no
    // visual gain.
    private const val ENCODE_MAX_DIMENSION = 100

    /**
     * Encodes [bitmap] into a blurhash string. [componentX]/[componentY] default to an
     * aspect-ratio-scaled count (more detail on the longer axis, 1..9 per axis — see
     * [componentCountForAxis]) when not given explicitly.
     */
    fun encode(bitmap: Bitmap, componentX: Int? = null, componentY: Int? = null): String {
        val scaled = downscaleForEncoding(bitmap)
        val aspectRatio = scaled.width.toFloat() / scaled.height.toFloat()
        val numX = componentX ?: componentCountForAxis(aspectRatio)
        val numY = componentY ?: componentCountForAxis(1f / aspectRatio)
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        return encodePixels(pixels, scaled.width, scaled.height, numX, numY)
    }

    private fun downscaleForEncoding(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= ENCODE_MAX_DIMENSION && bitmap.height <= ENCODE_MAX_DIMENSION) return bitmap
        val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val targetWidth: Int
        val targetHeight: Int
        if (aspectRatio >= 1f) {
            targetWidth = ENCODE_MAX_DIMENSION
            targetHeight = (ENCODE_MAX_DIMENSION / aspectRatio).roundToInt().coerceAtLeast(1)
        } else {
            targetWidth = (ENCODE_MAX_DIMENSION * aspectRatio).roundToInt().coerceAtLeast(1)
            targetHeight = ENCODE_MAX_DIMENSION
        }
        return bitmap.scale(targetWidth, targetHeight)
    }

    private fun componentCountForAxis(axisAspectRatio: Float): Int = when {
        axisAspectRatio > 1f -> 9
        axisAspectRatio < 1f -> (9 * axisAspectRatio).roundToInt().coerceIn(1, 9)
        else -> 4
    }

    private fun encodePixels(pixels: IntArray, width: Int, height: Int, componentX: Int, componentY: Int): String {
        require(componentX in 1..9 && componentY in 1..9) { "Blur hash must have between 1 and 9 components" }

        val factors = Array(componentX * componentY) { FloatArray(3) }
        val cosinesX = cosinesFor(cosinesXCache, width, componentX)
        val cosinesY = cosinesFor(cosinesYCache, height, componentY)
        val scale = 1f / (width * height)

        for (j in 0 until componentY) {
            for (i in 0 until componentX) {
                val normalisation = if (i == 0 && j == 0) 1f else 2f
                var r = 0f
                var g = 0f
                var b = 0f
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val basis = normalisation * (cosinesX[i + componentX * x] * cosinesY[j + componentY * y]).toFloat()
                        val pixel = pixels[y * width + x]
                        r += basis * srgbToLinear((pixel shr 16) and 0xff)
                        g += basis * srgbToLinear((pixel shr 8) and 0xff)
                        b += basis * srgbToLinear(pixel and 0xff)
                    }
                }
                val colors = factors[j * componentX + i]
                colors[0] = r * scale
                colors[1] = g * scale
                colors[2] = b * scale
            }
        }

        val hash = CharArray(1 + 1 + 4 + 2 * (factors.size - 1))
        val sizeFlag = (componentX - 1 + (componentY - 1) * 9).toLong()
        encode83(sizeFlag, 1, hash, 0)

        val maximumValue: Float
        if (factors.size > 1) {
            val actualMaximumValue = maxAcMagnitude(factors, 1, factors.size)
            val quantisedMaximumValue = floor(max(0f, min(82f, floor(actualMaximumValue * 166f - 0.5f))))
            maximumValue = (quantisedMaximumValue + 1f) / 166f
            encode83(quantisedMaximumValue.roundToLong(), 1, hash, 1)
        } else {
            maximumValue = 1f
            encode83(0L, 1, hash, 1)
        }

        encode83(encodeDc(factors[0]), 4, hash, 2)
        for (i in 1 until factors.size) {
            encode83(encodeAc(factors[i], maximumValue), 2, hash, 6 + 2 * (i - 1))
        }
        return hash.concatToString()
    }

    /**
     * Deliberately matches a known-working production encoder rather than the canonical blurhash
     * reference implementation: unlike that reference, this does NOT take `abs()` of each AC
     * factor — see this file's class doc comment. Still yields a fully valid, decodable blurhash.
     */
    private fun maxAcMagnitude(values: Array<FloatArray>, from: Int, endExclusive: Int): Float {
        var result = Float.NEGATIVE_INFINITY
        for (i in from until endExclusive) {
            for (value in values[i]) {
                if (value > result) result = value
            }
        }
        return result
    }

    private fun encodeDc(value: FloatArray): Long {
        val r = linearToSrgb(value[0]).toLong()
        val g = linearToSrgb(value[1]).toLong()
        val b = linearToSrgb(value[2]).toLong()
        return (r shl 16) + (g shl 8) + b
    }

    private fun encodeAc(value: FloatArray, maximumValue: Float): Long {
        val quantR = floor(max(0f, min(18f, floor(signPow(value[0] / maximumValue, 0.5f) * 9f + 9.5f))))
        val quantG = floor(max(0f, min(18f, floor(signPow(value[1] / maximumValue, 0.5f) * 9f + 9.5f))))
        val quantB = floor(max(0f, min(18f, floor(signPow(value[2] / maximumValue, 0.5f) * 9f + 9.5f))))
        return (quantR * 19f * 19f + quantG * 19f + quantB).roundToLong()
    }

    /** Inverse of [signedPow2] for an arbitrary exponent: `sign(value) * |value|^exp`. */
    private fun signPow(value: Float, exp: Float): Float = abs(value).pow(exp).withSign(value)

    private fun encode83(value: Long, length: Int, buffer: CharArray, offset: Int) {
        var exp = 1L
        for (i in 1..length) {
            val digit = (value / exp % 83).toInt()
            buffer[offset + length - i] = ALPHABET[digit]
            exp *= 83
        }
    }
}
