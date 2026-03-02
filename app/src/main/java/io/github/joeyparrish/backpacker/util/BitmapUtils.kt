package io.github.joeyparrish.backpacker.util

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Utility functions for converting between Android Bitmaps and OpenCV Mats,
 * and for normalizing screenshots to the 720p internal resolution.
 */
object BitmapUtils {

    fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        return mat
    }

    fun matToBitmap(mat: Mat): Bitmap {
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bitmap)
        return bitmap
    }

    /**
     * Scale a Bitmap to 720 pixels wide (height scaled proportionally).
     * All CV thresholds are calibrated against 720p output from this function.
     */
    fun scaleTo720p(bitmap: Bitmap): Bitmap {
        val targetWidth = CoordinateTransform.NORM_WIDTH
        if (bitmap.width == targetWidth) return bitmap

        val scale = targetWidth.toFloat() / bitmap.width
        val targetHeight = (bitmap.height * scale).toInt()

        val src = bitmapToMat(bitmap)
        val dst = Mat()
        Imgproc.resize(src, dst, Size(targetWidth.toDouble(), targetHeight.toDouble()))
        src.release()

        val result = matToBitmap(dst)
        dst.release()
        return result
    }
}
