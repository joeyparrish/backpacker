package io.github.joeyparrish.backpacker.util

import android.graphics.PointF

/**
 * Translates coordinates between the normalized 720p internal space used by the
 * CV pipeline and actual device pixel coordinates used by gesture injection.
 */
object CoordinateTransform {

    /** Internal width all screenshots are normalized to. */
    const val NORM_WIDTH = 720

    fun toDeviceX(normX: Float, deviceWidth: Int): Float =
        normX * deviceWidth / NORM_WIDTH

    fun toDeviceY(normY: Float, deviceWidth: Int): Float =
        normY * deviceWidth / NORM_WIDTH

    fun toNorm(devicePt: PointF, deviceWidth: Int): PointF {
        val scale = NORM_WIDTH.toFloat() / deviceWidth
        return PointF(devicePt.x * scale, devicePt.y * scale)
    }

    fun radiusToDevice(normRadius: Float, deviceWidth: Int): Float =
        normRadius * deviceWidth / NORM_WIDTH
}
