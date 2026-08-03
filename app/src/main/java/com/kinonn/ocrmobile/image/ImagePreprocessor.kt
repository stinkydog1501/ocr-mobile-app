package com.kinonn.ocrmobile.image

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Phase 2: Image preprocessing utilities for improving OCR accuracy.
 *
 * Uses the official OpenCV Android AAR (`org.opencv:opencv:4.9.0`).
 * All operations degrade to identity (return the input bitmap unchanged)
 * when the OpenCV native library cannot be loaded, so the OCR pipeline
 * keeps working on devices without OpenCV.
 */
class ImagePreprocessor {

    companion object {
        /**
         * True when the OpenCV native library loaded successfully.
         * `OpenCVLoader.initDebug()` loads libopencv_java4 from the APK.
         */
        val isAvailable: Boolean by lazy {
            runCatching { OpenCVLoader.initDebug() }.getOrDefault(false)
        }

        /** Converts an Android Bitmap (ARGB_8888) to a CV_8UC4 Mat (BGRA memory order). */
        internal fun bitmapToMat(bitmap: Bitmap): Mat {
            val mat = Mat(bitmap.height, bitmap.width, CvType.CV_8UC4)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

            val data = ByteArray(pixels.size * 4)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                data[i * 4] = (pixel and 0xFF).toByte()            // B
                data[i * 4 + 1] = ((pixel shr 8) and 0xFF).toByte() // G
                data[i * 4 + 2] = ((pixel shr 16) and 0xFF).toByte() // R
                data[i * 4 + 3] = ((pixel shr 24) and 0xFF).toByte() // A
            }
            mat.put(0, 0, data)
            return mat
        }

        /**
         * Converts a Mat to an ARGB_8888 Bitmap. Releases [mat] when done.
         * Handles 1 (gray), 3 (RGB), and 4 (RGBA/BGRA) channel mats.
         */
        internal fun matToBitmap(mat: Mat): Bitmap {
            val width = mat.cols()
            val height = mat.rows()

            val rgba = Mat()
            try {
                when (mat.channels()) {
                    1 -> Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_GRAY2RGBA)
                    3 -> Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_RGB2RGBA)
                    4 -> mat.copyTo(rgba)
                    else -> throw IllegalArgumentException("Unsupported channel count: ${mat.channels()}")
                }
            } finally {
                mat.release()
            }

            val data = ByteArray(width * height * 4)
            rgba.get(0, 0, data)
            rgba.release()

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)
            for (i in pixels.indices) {
                val b = data[i * 4].toInt() and 0xFF
                val g = data[i * 4 + 1].toInt() and 0xFF
                val r = data[i * 4 + 2].toInt() and 0xFF
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            return bitmap
        }
    }

    /**
     * Apply full preprocessing pipeline: contrast → deskew → binarize.
     * Returns a new bitmap; the input is recycled along the way.
     */
    fun preprocessForOCR(bitmap: Bitmap): Bitmap {
        if (!isAvailable) return bitmap
        var current = bitmap

        val contrast = enhanceContrast(current)
        if (contrast !== current) {
            current.recycle()
            current = contrast
        }

        val deskewed = deskew(current)
        if (deskewed !== current) {
            current.recycle()
            current = deskewed
        }

        val binarized = binarize(current)
        if (binarized !== current) {
            current.recycle()
            current = binarized
        }
        return current
    }

    /**
     * Enhance contrast using CLAHE (Contrast Limited Adaptive Histogram Equalization).
     * Improves text visibility under uneven lighting.
     */
    fun enhanceContrast(bitmap: Bitmap): Bitmap {
        if (!isAvailable) return bitmap
        val mat = bitmapToMat(bitmap)

        val gray = Mat()
        try {
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGRA2GRAY)
        } finally {
            mat.release()
        }

        val result = Mat()
        try {
            // CLAHE is in Imgproc, not Photo
            Imgproc.createCLAHE(2.0, Size(8.0, 8.0)).apply(gray, result)
        } finally {
            gray.release()
        }
        return matToBitmap(result)
    }

    /**
     * Deskew the image by fitting a rotated rectangle to the text and
     * rotating it back to horizontal. Corrects tilted captures.
     */
    fun deskew(bitmap: Bitmap): Bitmap {
        if (!isAvailable) return bitmap
        val mat = bitmapToMat(bitmap)
        val result = try {
            deskewMat(mat)
        } finally {
            mat.release()
        }
        return matToBitmap(result)
    }

    private fun deskewMat(mat: Mat): Mat {
        val gray = Mat()
        val blurred = Mat()
        val binary = Mat()
        val nonZero = Mat()
        try {
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGRA2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.threshold(
                blurred, binary, 0.0, 255.0,
                Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU
            )
            Core.findNonZero(binary, nonZero)
        } finally {
            gray.release()
            blurred.release()
            binary.release()
        }

        val points2f = MatOfPoint2f()
        try {
            // findNonZero returns CV_32SC2; minAreaRect needs CV_32FC2
            nonZero.convertTo(points2f, CvType.CV_32FC2)
            if (points2f.empty()) return mat.clone()

            val rotated = Imgproc.minAreaRect(points2f)

            var angle = rotated.angle
            if (angle < -45.0) angle += 90.0

            // Only rotate when the skew is significant (minAreaRect angle is noisy near 0)
            if (kotlin.math.abs(angle) < 1.0) {
                return mat.clone()
            }

            val rotationMat = Imgproc.getRotationMatrix2D(rotated.center, angle, 1.0)
            val result = Mat()
            Imgproc.warpAffine(mat, result, rotationMat, mat.size())
            rotationMat.release()
            return result
        } finally {
            nonZero.release()
            points2f.release()
        }
    }

    /**
     * Binarize the image with adaptive thresholding for clean OCR input.
     * Returns a 1-channel grayscale bitmap.
     */
    fun binarize(bitmap: Bitmap): Bitmap {
        if (!isAvailable) return bitmap
        val mat = bitmapToMat(bitmap)

        val gray = Mat()
        try {
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGRA2GRAY)
        } finally {
            mat.release()
        }

        val binary = Mat()
        try {
            Imgproc.adaptiveThreshold(
                gray, binary, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                11,  // block size (must be odd)
                2.0  // constant subtracted from the mean
            )
        } finally {
            gray.release()
        }
        return matToBitmap(binary)
    }
}
