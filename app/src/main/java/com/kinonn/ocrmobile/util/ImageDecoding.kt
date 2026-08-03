package com.kinonn.ocrmobile.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.kinonn.ocrmobile.core.ocr.OcrImage
import java.io.IOException

/**
 * Decodes a captured/gallery image into the engine's RGB888 format,
 * downsampled (max ~1600px) and corrected for EXIF rotation.
 */
object ImageDecoding {

    fun decodeToOcrImage(context: Context, uri: Uri, maxDimension: Int = 1600): OcrImage {
        val resolver = context.contentResolver

        // Open the stream once and read all needed data before closing
        val (bitmap, orientation) = resolver.openInputStream(uri)?.use { stream ->
            // Read EXIF first while stream is open
            val exif = try {
                androidx.exifinterface.media.ExifInterface(stream)
            } catch (_: Exception) {
                null
            }
            val orient = exif?.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            ) ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL

            // Reset stream position if possible, otherwise reopen
            if (exif != null && stream.markSupported()) {
                try {
                    stream.reset()
                } catch (_: Exception) {
                    // Can't reset, fall through
                }
            }

            // Decode bounds
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeStream(stream, null, bounds)
            val srcWidth = bounds.outWidth
            val srcHeight = bounds.outHeight

            // Reset stream again for full decode
            if (stream.markSupported()) {
                try {
                    stream.reset()
                } catch (_: Exception) {
                    // Fall through
                }
            }

            // Decode with sampling
            var sampleSize = 1
            while (srcWidth / (sampleSize * 2) >= maxDimension ||
                srcHeight / (sampleSize * 2) >= maxDimension
            ) {
                sampleSize *= 2
            }

            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = android.graphics.BitmapFactory.decodeStream(stream, null, options)
                ?: throw IllegalStateException("Cannot decode image")

            Pair(bitmap, orient)
        } ?: throw IllegalStateException("Cannot open image")

        val rotated = rotateByOrientation(bitmap, orientation)
        if (rotated !== bitmap) bitmap.recycle()

        val pixels = IntArray(rotated.width * rotated.height)
        rotated.getPixels(pixels, 0, rotated.width, 0, 0, rotated.width, rotated.height)
        rotated.recycle()

        val rgb = ByteArray(pixels.size * 3)
        for (i in pixels.indices) {
            rgb[i * 3] = (pixels[i] shr 16 and 0xFF).toByte()
            rgb[i * 3 + 1] = (pixels[i] shr 8 and 0xFF).toByte()
            rgb[i * 3 + 2] = (pixels[i] and 0xFF).toByte()
        }
        return OcrImage(rgb, rotated.width, rotated.height)
    }

    private fun rotateByOrientation(bitmap: android.graphics.Bitmap, orientation: Int): android.graphics.Bitmap {
        return when (orientation) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> rotate(bitmap, 90f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> rotate(bitmap, 180f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> rotate(bitmap, 270f)
            else -> bitmap
        }
    }

    private fun rotate(bitmap: android.graphics.Bitmap, degrees: Float): android.graphics.Bitmap {
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        return android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
