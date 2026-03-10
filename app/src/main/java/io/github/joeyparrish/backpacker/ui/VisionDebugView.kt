// Copyright 2026 Joey Parrish
// SPDX-License-Identifier: MIT

package io.github.joeyparrish.backpacker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Fullscreen WindowManager overlay that displays a processed vision debug bitmap.
 *
 * Tap anywhere or use the back gesture to dismiss.
 *
 * The overlay takes input focus (no FLAG_NOT_FOCUSABLE) so that back gestures are
 * consumed by the overlay rather than falling through to the game underneath.
 * The container intercepts KEYCODE_BACK to dismiss; tap is handled by the ImageView.
 */
class VisionDebugView(context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val imageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        setOnClickListener { hide() }
    }

    // FrameLayout wrapper that intercepts the back key so it dismisses the overlay
    // rather than falling through to the underlying app.
    private val container = object : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                hide()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }.also { fl ->
        fl.isFocusable = true
        fl.isFocusableInTouchMode = true
        fl.addView(imageView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private var isAttached = false

    // FLAG_NOT_FOCUSABLE is intentionally omitted so the window captures key events
    // (back button / back gesture) instead of passing them to the game.
    // LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES (API 28+) lets the window extend into
    // the camera notch area so the bitmap fills the entire physical display.
    private val layoutParams = LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.MATCH_PARENT,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).also {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            it.layoutInDisplayCutoutMode =
                LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /** Display [bitmap] fullscreen.  Replaces any previously shown bitmap. */
    fun show(bitmap: Bitmap) {
        imageView.setImageBitmap(bitmap)
        if (!isAttached) {
            try {
                windowManager.addView(container, layoutParams)
                container.requestFocus()
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
                windowManager.removeView(container)
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
