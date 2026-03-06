// Copyright 2026 Joey Parrish
// SPDX-License-Identifier: MIT

package io.github.joeyparrish.backpacker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.ImageView

/**
 * WindowManager overlay that displays a processed vision debug bitmap and dismisses on tap.
 *
 * Used by both stop-detection debug (cyan mask + bounding boxes painted on the
 * 720p screenshot) and spinner debug (ring mask painted on the screenshot).
 *
 * The image fills the full screen width, maintains its aspect ratio, and is
 * gravity-anchored to the bottom of the screen.  [show] can be called repeatedly
 * to replace the current image (e.g. before/after a swipe in spinner debug mode).
 */
class VisionDebugView(context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val imageView = ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_XY
        setOnClickListener { hide() }
    }

    private var isAttached = false

    private val layoutParams = LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.WRAP_CONTENT,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM
    }

    /** Display [bitmap] full-screen.  Replaces any previously shown bitmap. */
    fun show(bitmap: Bitmap) {
        imageView.setImageBitmap(bitmap)
        if (!isAttached) {
            try {
                windowManager.addView(imageView, layoutParams)
                isAttached = true
            } catch (e: Exception) {
                Log.e(TAG, "addView: $e")
            }
        }
    }

    /** Remove the overlay if visible. */
    fun hide() {
        if (isAttached) {
            try {
                windowManager.removeView(imageView)
            } catch (e: Exception) {
                Log.w(TAG, "removeView: $e")
            }
            isAttached = false
        }
    }

    companion object {
        private const val TAG = "Backpacker.VisionDebugView"
    }
}
