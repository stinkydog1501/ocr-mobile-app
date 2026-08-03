package com.kinonn.ocrmobile.image

import android.graphics.Bitmap
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/**
 * Phase 2: Handwritten text optimization for improved OCR accuracy.
 *
 * Heuristic: handwriting has a higher density of fine strokes than printed
 * text, which shows up as higher variance in the Laplacian (edge energy).
 * When detected, the image gets denoising + stroke-connecting morphology +
 * CLAHE contrast before OCR.
 */
class HandwritingOptimizer {

    companion object {
        /** Mean of squared Laplacian above which we treat content as handwriting. */
        private const val VARIANCE_THRESHOLD = 1000.0
    }

    /**
     * Apply handwriting-specific preprocessing: denoise → connect broken
     * strokes (morphological close) → CLAHE contrast.
     */
    fun optimizeForHandwriting(bitmap: Bitmap): Bitmap {
        if (!ImagePreprocessor.isAvailable) return bitmap
        val mat = ImagePreprocessor.bitmapToMat(bitmap)

        val gray = Mat()
        try {
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGRA2GRAY)
        } finally {
            mat.release()
        }

        val denoised = Mat()
        try {
            // fastNlMeansDenoising is in Photo; preserves edges while removing noise
            Photo.fastNlMeansDenoising(gray, denoised, 10f, 7, 21)
        } finally {
            gray.release()
        }

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
        val morphed = Mat()
        try {
            // Close connects broken pen strokes
            Imgproc.morphologyEx(denoised, morphed, Imgproc.MORPH_CLOSE, kernel)
        } finally {
            denoised.release()
        }

        val enhanced = Mat()
        try {
            // CLAHE is in Imgproc, not Photo
            Imgproc.createCLAHE(3.0, Size(8.0, 8.0)).apply(morphed, enhanced)
        } finally {
            morphed.release()
        }
        return ImagePreprocessor.matToBitmap(enhanced)
    }

    /**
     * Estimate whether the image contains handwriting based on Laplacian
     * edge-energy variance. Returns false when OpenCV is unavailable.
     */
    fun detectHandwriting(bitmap: Bitmap): Boolean {
        if (!ImagePreprocessor.isAvailable) return false
        val mat = ImagePreprocessor.bitmapToMat(bitmap)

        val gray = Mat()
        try {
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGRA2GRAY)
        } finally {
            mat.release()
        }

        val laplacian = Mat()
        try {
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
        } finally {
            gray.release()
        }

        val variance = Mat()
        try {
            Core.multiply(laplacian, laplacian, variance)
        } finally {
            laplacian.release()
        }

        val score = try {
            Core.mean(variance).`val`[0]
        } finally {
            variance.release()
        }
        return score > VARIANCE_THRESHOLD
    }
}
