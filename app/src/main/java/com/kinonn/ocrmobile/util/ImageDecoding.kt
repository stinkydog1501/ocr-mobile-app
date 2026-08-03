package com.kinonn.ocrmobile.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("Cannot read image")

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= maxDimension ||
            bounds.outHeight / (sampleSize * 2) >= maxDimension
        ) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Cannot decode image")

        val rotated = rotateByExif(context, uri, decoded)
        if (rotated !== decoded) decoded.recycle()

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

    private fun rotateByExif(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotate(bitmap, 90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotate(bitmap, 180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotate(bitmap, 270f)
            else -> bitmap
        }
    }

    private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
