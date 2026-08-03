package com.kinonn.ocrmobile.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.kinonn.ocrmobile.core.ocr.OcrImage
import com.kinonn.ocrmobile.image.HandwritingOptimizer
import com.kinonn.ocrmobile.image.ImagePreprocessor

/**
 * Decodes a captured/gallery image into the engine's RGB888 format.
 *
 * Uses [ImageDecoder] (handles EXIF rotation and downsampling correctly on
 * API 29+ — content URIs are not rewindable streams, so manual EXIF +
 * BitmapFactory decoding is a trap), then applies Phase 2 preprocessing.
 *
 * Preprocessing failures degrade gracefully to the unprocessed image so a
 * capture is never lost.
 */
object ImageDecoding {

    private val preprocessor by lazy { ImagePreprocessor() }
    private val handwritingOptimizer by lazy { HandwritingOptimizer() }

    fun decodeToOcrImage(context: Context, uri: Uri, maxDimension: Int = 1600): OcrImage {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Downsample so the longest side stays within maxDimension
            val maxSide = maxOf(info.size.width, info.size.height)
            var sample = 1
            while (maxSide / (sample * 2) >= maxDimension) sample *= 2
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }

        val processed = try {
            applyPreprocessing(decoded)
        } catch (e: Exception) {
            // Never lose a capture over preprocessing; use the original
            decoded
        }
        if (processed !== decoded) decoded.recycle()

        val (rgb, width, height) = bitmapToRgb888(processed)
        processed.recycle()

        return OcrImage(rgb, width, height)
    }

    /**
     * Phase 2 preprocessing chain, each step degrading to identity when the
     * OpenCV native library is unavailable:
     * deskew → handwriting detection/optimization → binarize.
     */
    private fun applyPreprocessing(bitmap: Bitmap): Bitmap {
        if (!ImagePreprocessor.isAvailable) return bitmap
        var current = bitmap

        val deskewed = preprocessor.deskew(current)
        if (deskewed !== current) {
            current.recycle()
            current = deskewed
        }

        // Detect handwriting before binarizing — the binary image would
        // trigger the edge-energy heuristic on every scan.
        val enhanced = if (handwritingOptimizer.detectHandwriting(current)) {
            val optimized = handwritingOptimizer.optimizeForHandwriting(current)
            if (optimized !== current) {
                current.recycle()
                optimized
            } else {
                current
            }
        } else {
            current
        }

        val binarized = preprocessor.binarize(enhanced)
        return if (binarized !== enhanced) {
            enhanced.recycle()
            binarized
        } else {
            enhanced
        }
    }

    private fun bitmapToRgb888(bitmap: Bitmap): Triple<ByteArray, Int, Int> {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val rgb = ByteArray(pixels.size * 3)
        for (i in pixels.indices) {
            rgb[i * 3] = (pixels[i] shr 16 and 0xFF).toByte()     // R
            rgb[i * 3 + 1] = (pixels[i] shr 8 and 0xFF).toByte()  // G
            rgb[i * 3 + 2] = (pixels[i] and 0xFF).toByte()        // B
        }
        return Triple(rgb, width, height)
    }
}
