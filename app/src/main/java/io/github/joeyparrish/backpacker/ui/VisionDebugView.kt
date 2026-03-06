// Copyright 2026 Joey Parrish
// SPDX-License-Identifier: MIT

package io.github.joeyparrish.backpacker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.util.Log
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.ImageView

/**
 * Fullscreen WindowManager overlay that displays a processed vision debug
 * bitmap and dismisses on tap.
 *
 * Used by both stop-detection debug (cyan mask + bounding boxes painted on the
 * 720p screenshot) and spinner debug (ring mask painted on the screenshot).
 *
 * Unlike [DebugOverlayView] there is no auto-hide timer: the image stays until
 * the user taps it.  [show] can be called repeatedly to replace the current
 * image (e.g. before/after a swipe in spinner debug mode).
 *
 * The overlay uses FLAG_LAYOUT_IN_SCREEN so the window covers the status bar
 * area.  The ImageView is translated up by the status bar height so its pixel
 * coordinates align with the VirtualDisplay capture which starts at physical y=0.
 */
class VisionDebugView(context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val statusBarOffset: Float = run {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        (if (id > 0) context.resources.getDimensionPixelSize(id) else 0).toFloat()
    }

    private val imageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        translationY = -statusBarOffset
        setOnClickListener { hide() }
    }

    private var isAttached = false

    private val layoutParams = LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.MATCH_PARENT,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

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
