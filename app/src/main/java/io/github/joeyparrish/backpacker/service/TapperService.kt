// Copyright 2026 Joey Parrish
// SPDX-License-Identifier: MIT

package io.github.joeyparrish.backpacker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import io.github.joeyparrish.backpacker.ui.HudView
import io.github.joeyparrish.backpacker.ui.OverlayView
import io.github.joeyparrish.backpacker.ui.VisionDebugView
import kotlinx.coroutines.delay

/**
 * AccessibilityService that:
 *   1. Injects tap and swipe gestures into any foreground app (including Pokémon GO)
 *   2. Draws the floating overlay button over Pokémon GO (when explicitly enabled)
 *
 * The user must enable this service manually in Android Settings → Accessibility.
 * MainActivity provides a deep-link button to open the Accessibility settings page.
 *
 * The overlay is NOT shown automatically on service connect; it is shown and hidden
 * by MainActivity via [showOverlay] / [hideOverlay] as part of the enable/disable flow.
 */
class TapperService : AccessibilityService() {

    private var overlayView: OverlayView? = null
    private var visionDebugView: VisionDebugView? = null
    private var hudView: HudView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "TapperService connected")

        // Create the overlay views so they are ready to show, but do not display them yet.
        // The overlay is shown explicitly via showOverlay() once screen-capture permission
        // has been granted by the user in MainActivity.
        try {
            overlayView = OverlayView(this) { state -> handleToggle(state) }
            visionDebugView = VisionDebugView(this)
            hudView = HudView(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create overlay: $e")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // TapperService does not process accessibility events — it only injects gestures.
    }

    override fun onInterrupt() {
        Log.w(TAG, "TapperService interrupted")
    }

    override fun onDestroy() {
        try { overlayView?.hide() } catch (e: Exception) { Log.w(TAG, "hide overlay: $e") }
        try { visionDebugView?.hide() } catch (e: Exception) { Log.w(TAG, "hide vision debug: $e") }
        try { hudView?.hide() } catch (e: Exception) { Log.w(TAG, "hide hud: $e") }
        overlayView = null
        visionDebugView = null
        hudView = null
        isOverlayShown = false
        instance = null
        Log.i(TAG, "TapperService destroyed")
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Overlay visibility
    // -------------------------------------------------------------------------

    fun showOverlay() {
        overlayView?.setState(OverlayView.State.IDLE)
        overlayView?.show()
        hudView?.show()
        isOverlayShown = true
        Log.i(TAG, "Overlay shown")
    }

    fun hideOverlay() {
        overlayView?.hide()
        visionDebugView?.hide()
        hudView?.hide()
        isOverlayShown = false
        Log.i(TAG, "Overlay hidden")
    }

    // -------------------------------------------------------------------------
    // Gesture injection
    // -------------------------------------------------------------------------

    fun tap(x: Float, y: Float) {
        Log.d(TAG, "tap($x, $y)")
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        Log.d(TAG, "swipe($x1,$y1 → $x2,$y2, ${durationMs}ms)")
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        // NOTE: The gesture time isn't actually waited on by the call to
        // dispatch it.  So we delay explicitly here.
        delay(durationMs)
    }

    fun back() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // -------------------------------------------------------------------------

    /**
     * Called by [OverlayView] when the user taps the FAB.
     * [newState] is the state the FAB just cycled into.
     * IDLE  → pause the capture loop
     * HOUSE → run at low frequency (sitting still)
     * CAR   → run at high frequency (driving)
     */
    private fun handleToggle(newState: OverlayView.State) {
        when (newState) {
            OverlayView.State.IDLE  -> { AutomationService.pause(this); hudView?.clearStatus() }
            OverlayView.State.HOUSE -> { hudView?.show(); AutomationService.run(this, AutomationService.ScanMode.HOUSE) }
            OverlayView.State.CAR   -> { hudView?.show(); AutomationService.run(this, AutomationService.ScanMode.CAR) }
        }
    }

    /**
     * Called by AutomationService when it stops unexpectedly (e.g. MediaProjection revoked)
     * so the overlay FAB is reset to IDLE rather than staying stuck in RUNNING.
     */
    fun notifyAutomationStopped() {
        overlayView?.setState(OverlayView.State.IDLE)
        hudView?.clearStatus()
    }

    /** Display a vision debug bitmap fullscreen (tap to dismiss). Resets FAB to IDLE. */
    fun showDebugImage(bitmap: Bitmap) {
        overlayView?.setState(OverlayView.State.IDLE)
        hudView?.hide()
        visionDebugView?.show(bitmap)
    }

    /** Update the HUD status and stats lines. Must be called on the main thread. */
    fun updateHud(status: String, stats: String) {
        hudView?.update(status, stats)
    }

    companion object {
        private const val TAG = "Backpacker.TapperService"

        @Volatile
        var instance: TapperService? = null
            private set

        @Volatile
        var isOverlayShown: Boolean = false
            private set

        val isConnected: Boolean get() = instance != null
    }
}
